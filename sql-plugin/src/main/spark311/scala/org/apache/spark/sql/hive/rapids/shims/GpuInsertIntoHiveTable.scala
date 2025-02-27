/*
 * Copyright (c) 2023, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*** spark-rapids-shim-json-lines
{"spark": "311"}
{"spark": "312"}
{"spark": "313"}
{"spark": "320"}
{"spark": "321"}
{"spark": "321cdh"}
{"spark": "321db"}
{"spark": "322"}
{"spark": "323"}
{"spark": "330"}
{"spark": "330cdh"}
{"spark": "330db"}
{"spark": "331"}
{"spark": "332"}
spark-rapids-shim-json-lines ***/
package org.apache.spark.sql.hive.rapids.shims

import com.nvidia.spark.rapids.{ColumnarFileFormat, DataFromReplacementRule, DataWritingCommandMeta, GpuDataWritingCommand, RapidsConf, RapidsMeta}
import java.util.Locale
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.ErrorMsg
import org.apache.hadoop.hive.ql.plan.TableDesc

import org.apache.spark.SparkException
import org.apache.spark.internal.io.FileCommitProtocol
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogTable, CatalogTableType, ExternalCatalog, ExternalCatalogUtils, ExternalCatalogWithListener}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.expressions.{Attribute, Literal}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.command.CommandUtils
import org.apache.spark.sql.execution.datasources.FileFormatWriter
import org.apache.spark.sql.hive.HiveExternalCatalog
import org.apache.spark.sql.hive.HiveShim.{ShimFileSinkDesc => FileSinkDesc}
import org.apache.spark.sql.hive.client.HiveClientImpl
import org.apache.spark.sql.hive.client.hive._
import org.apache.spark.sql.hive.execution.{InsertIntoHiveTable, SaveAsHiveFile}
import org.apache.spark.sql.rapids.GpuFileFormatWriter
import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, StringType}
import org.apache.spark.sql.vectorized.ColumnarBatch

private object RapidsHiveErrors {
  // Lifted from org.apache.spark.sql.errors.QueryErrorsBase.
  // Converts an error class parameter to its SQL representation
  def toSQLValue(v: Any, t: DataType): String = Literal.create(v, t) match {
    case Literal(null, _) => "NULL"
    case Literal(v: Float, FloatType) =>
      if (v.isNaN) "NaN"
      else if (v.isPosInfinity) "Infinity"
      else if (v.isNegInfinity) "-Infinity"
      else v.toString
    case l @ Literal(v: Double, DoubleType) =>
      if (v.isNaN) "NaN"
      else if (v.isPosInfinity) "Infinity"
      else if (v.isNegInfinity) "-Infinity"
      else l.sql
    case l => l.sql
  }

  def requestedPartitionsMismatchTablePartitionsError(
      table: CatalogTable, partition: Map[String, Option[String]]): Throwable = {
    new SparkException(
      s"""
         |Requested partitioning does not match the ${table.identifier.table} table:
         |Requested partitions: ${partition.keys.mkString(",")}
         |Table partitions: ${table.partitionColumnNames.mkString(",")}
       """.stripMargin)
  }

  def cannotResolveAttributeError(name: String, outputStr: String): Throwable = {
    new AnalysisException(
      s"Unable to resolve $name given [$outputStr]")
  }

  def writePartitionExceedConfigSizeWhenDynamicPartitionError(
      numWrittenParts: Int,
      maxDynamicPartitions: Int,
      maxDynamicPartitionsKey: String): Throwable = {
    new SparkException(
      s"Number of dynamic partitions created is $numWrittenParts" +
        s", which is more than $maxDynamicPartitions" +
        s". To solve this try to set $maxDynamicPartitionsKey" +
        s" to at least $numWrittenParts.")
  }

  // Lifted from QueryExecutionErrors.
  def dynamicPartitionKeyNotAmongWrittenPartitionPathsError(key: String): Throwable = {
    new SparkException(
      s"Dynamic partition key ${toSQLValue(key, StringType)} is not among written partition paths.")
  }

  // Lifted from QueryExecutionErrors.
  def cannotRemovePartitionDirError(partitionPath: Path): Throwable = {
    new RuntimeException(s"Cannot remove partition directory '$partitionPath'")
  }
}

// Base trait from which all hive insert statement physical execution extends.
private[hive] trait GpuSaveAsHiveFile extends GpuDataWritingCommand with SaveAsHiveFile {

  // TODO(future): Examine compressions options.
  // - Apache Spark 3.1-3 has code to examine Hadoop compression settings
  //   (and takes a FileSinkDesc instead of FileFormat).
  // - Apache Spark 3.4 has removed all that logic.
  // - GPU Hive text writer does not support compression for output.
  protected def gpuSaveAsHiveFile(sparkSession: SparkSession,
                               plan: SparkPlan,
                               hadoopConf: Configuration,
                               fileFormat: ColumnarFileFormat,
                               outputLocation: String,
                               customPartitionLocations: Map[TablePartitionSpec,String] = Map.empty,
                               partitionAttributes: Seq[Attribute] = Nil,
                               bucketSpec: Option[BucketSpec] = None,
                               options: Map[String, String] = Map.empty): Set[String] = {

    val committer = FileCommitProtocol.instantiate(
      sparkSession.sessionState.conf.fileCommitProtocolClass,
      jobId = java.util.UUID.randomUUID().toString,
      outputPath = outputLocation)

    GpuFileFormatWriter.write(
      sparkSession = sparkSession,
      plan = plan,
      fileFormat = fileFormat,
      committer = committer,
      outputSpec =
        FileFormatWriter.OutputSpec(outputLocation, customPartitionLocations, outputColumns),
      hadoopConf = hadoopConf,
      partitionColumns = partitionAttributes,
      bucketSpec = bucketSpec,
      statsTrackers = Seq(gpuWriteJobStatsTracker(hadoopConf)),
      options = options,
      useStableSort = false,                  // TODO: Fetch from RapidsConf.
      concurrentWriterPartitionFlushSize = 0L // TODO: Fetch from RapidsConf.
     )
  }
}

final class GpuInsertIntoHiveTableMeta(cmd: InsertIntoHiveTable,
                                       conf: RapidsConf,
                                       parent: Option[RapidsMeta[_,_,_]],
                                       rule: DataFromReplacementRule)
  extends DataWritingCommandMeta[InsertIntoHiveTable](cmd, conf, parent, rule) {

  private var fileFormat: Option[ColumnarFileFormat] = None

  override def tagSelfForGpu(): Unit = {
    // Only Hive delimited text writes are currently supported.
    // Check whether that is the format currently in play.
    fileFormat = GpuHiveTextFileFormat.tagGpuSupport(this)
  }

  override def convertToGpu(): GpuDataWritingCommand = {
    GpuInsertIntoHiveTable(
      table = wrapped.table,
      partition = wrapped.partition,
      fileFormat = this.fileFormat.get,
      query = wrapped.query,
      overwrite = wrapped.overwrite,
      ifPartitionNotExists = wrapped.ifPartitionNotExists,
      outputColumnNames = wrapped.outputColumnNames
    )
  }

  def getCannotBeReplacedReasons(): Option[collection.mutable.Set[String]] = cannotBeReplacedReasons
}

case class GpuInsertIntoHiveTable(
    table: CatalogTable,
    partition: Map[String, Option[String]],
    fileFormat: ColumnarFileFormat,
    query: LogicalPlan,
    overwrite: Boolean,
    ifPartitionNotExists: Boolean,
    outputColumnNames: Seq[String]) extends GpuSaveAsHiveFile {

  /**
   * Inserts all the rows in the table into Hive.  Row objects are properly serialized with the
   * `org.apache.hadoop.hive.serde2.SerDe` and the
   * `org.apache.hadoop.mapred.OutputFormat` provided by the table definition.
   */
  override def runColumnar(sparkSession: SparkSession, child: SparkPlan): Seq[ColumnarBatch] = {
    val externalCatalog = sparkSession.sharedState.externalCatalog
    val hadoopConf = sparkSession.sessionState.newHadoopConf()

    val hiveQlTable = HiveClientImpl.toHiveTable(table)
    // Have to pass the TableDesc object to RDD.mapPartitions and then instantiate new serializer
    // instances within the closure, since Serializer is not serializable while TableDesc is.
    val tableDesc = new TableDesc(
      hiveQlTable.getInputFormatClass,
      // The class of table should be org.apache.hadoop.hive.ql.metadata.Table because
      // getOutputFormatClass will use HiveFileFormatUtils.getOutputFormatSubstitute to
      // substitute some output formats, e.g. substituting SequenceFileOutputFormat to
      // HiveSequenceFileOutputFormat.
      hiveQlTable.getOutputFormatClass,
      hiveQlTable.getMetadata
    )
    val tableLocation = hiveQlTable.getDataLocation
    val tmpLocation = getExternalTmpPath(sparkSession, hadoopConf, tableLocation)

    try {
      processInsert(sparkSession,
                    externalCatalog,
                    hadoopConf,
                    tableDesc,
                    fileFormat,
                    tmpLocation,
                    child)
    } finally {
      // Attempt to delete the staging directory and the inclusive files. If failed, the files are
      // expected to be dropped at the normal termination of VM since deleteOnExit is used.
      deleteExternalTmpPath(hadoopConf)
    }

    // un-cache this table.
    CommandUtils.uncacheTableOrView(sparkSession, table.identifier.quotedString)
    sparkSession.sessionState.catalog.refreshTable(table.identifier)

    CommandUtils.updateTableStats(sparkSession, table)

    // It would be nice to just return the childRdd unchanged so insert operations could be chained,
    // however for now we return an empty list to simplify compatibility checks with hive, which
    // does not return anything for insert operations.
    // TODO (From Apache Spark): implement hive compatibility as rules.
    Seq.empty[ColumnarBatch]
  }

  private def processInsert(
      sparkSession: SparkSession,
      externalCatalog: ExternalCatalog,
      hadoopConf: Configuration,
      tableDesc: TableDesc,
      fileFormat: ColumnarFileFormat,
      tmpLocation: Path,
      child: SparkPlan): Unit = {
    val fileSinkConf = new FileSinkDesc(tmpLocation.toString, tableDesc, false)

    val numDynamicPartitions = partition.values.count(_.isEmpty)
    val numStaticPartitions = partition.values.count(_.nonEmpty)
    val partitionSpec = partition.map {
      case (key, Some(null)) => key -> ExternalCatalogUtils.DEFAULT_PARTITION_NAME
      case (key, Some(value)) => key -> value
      case (key, None) => key -> ""
    }

    // All partition column names in the format of "<column name 1>/<column name 2>/..."
    val partitionColumns = fileSinkConf.getTableInfo.getProperties.getProperty("partition_columns")
    val partitionColumnNames = Option(partitionColumns).map(_.split("/")).getOrElse(Array.empty)

    // By this time, the partition map must match the table's partition columns
    if (partitionColumnNames.toSet != partition.keySet) {
      throw RapidsHiveErrors.requestedPartitionsMismatchTablePartitionsError(table, partition)
    }

    // Validate partition spec if there exist any dynamic partitions
    if (numDynamicPartitions > 0) {
      // Report error if dynamic partitioning is not enabled
      if (!hadoopConf.get("hive.exec.dynamic.partition", "true").toBoolean) {
        throw new SparkException(ErrorMsg.DYNAMIC_PARTITION_DISABLED.getMsg)
      }

      // Report error if dynamic partition strict mode is on but no static partition is found
      if (numStaticPartitions == 0 &&
        hadoopConf.get("hive.exec.dynamic.partition.mode", "strict").equalsIgnoreCase("strict")) {
        throw new SparkException(ErrorMsg.DYNAMIC_PARTITION_STRICT_MODE.getMsg)
      }

      // Report error if any static partition appears after a dynamic partition
      val isDynamic = partitionColumnNames.map(partitionSpec(_).isEmpty)
      if (isDynamic.init.zip(isDynamic.tail).contains((true, false))) {
        throw new AnalysisException(ErrorMsg.PARTITION_DYN_STA_ORDER.getMsg)
      }
    }

    val partitionAttributes = partitionColumnNames.takeRight(numDynamicPartitions).map { name =>
      val attr = query.resolve(name :: Nil, sparkSession.sessionState.analyzer.resolver).getOrElse {
        throw RapidsHiveErrors.cannotResolveAttributeError(
          name, query.output.map(_.name).mkString(", "))
      }.asInstanceOf[Attribute]
      // SPARK-28054: Hive metastore is not case preserving and keeps partition columns
      // with lower cased names. Hive will validate the column names in the partition directories
      // during `loadDynamicPartitions`. Spark needs to write partition directories with lower-cased
      // column names in order to make `loadDynamicPartitions` work.
      attr.withName(name.toLowerCase(Locale.ROOT))
    }

    val writtenParts = gpuSaveAsHiveFile(
      sparkSession = sparkSession,
      plan = child,
      hadoopConf = hadoopConf,
      fileFormat = fileFormat,
      outputLocation = tmpLocation.toString,
      partitionAttributes = partitionAttributes)

    if (partition.nonEmpty) {
      if (numDynamicPartitions > 0) {
        if (overwrite && table.tableType == CatalogTableType.EXTERNAL) {
          val numWrittenParts = writtenParts.size
          val maxDynamicPartitionsKey = HiveConf.ConfVars.DYNAMICPARTITIONMAXPARTS.varname
          val maxDynamicPartitions = hadoopConf.getInt(maxDynamicPartitionsKey,
            HiveConf.ConfVars.DYNAMICPARTITIONMAXPARTS.defaultIntVal)
          if (numWrittenParts > maxDynamicPartitions) {
            throw RapidsHiveErrors.writePartitionExceedConfigSizeWhenDynamicPartitionError(
              numWrittenParts, maxDynamicPartitions, maxDynamicPartitionsKey)
          }
          // SPARK-29295: When insert overwrite to a Hive external table partition, if the
          // partition does not exist, Hive will not check if the external partition directory
          // exists or not before copying files. So if users drop the partition, and then do
          // insert overwrite to the same partition, the partition will have both old and new
          // data. We construct partition path. If the path exists, we delete it manually.
          writtenParts.foreach { partPath =>
            val dpMap = partPath.split("/").map { part =>
              val splitPart = part.split("=")
              assert(splitPart.size == 2, s"Invalid written partition path: $part")
              ExternalCatalogUtils.unescapePathName(splitPart(0)) ->
                ExternalCatalogUtils.unescapePathName(splitPart(1))
            }.toMap

            val caseInsensitiveDpMap = CaseInsensitiveMap(dpMap)

            val updatedPartitionSpec = partition.map {
              case (key, Some(null)) => key -> ExternalCatalogUtils.DEFAULT_PARTITION_NAME
              case (key, Some(value)) => key -> value
              case (key, None) if caseInsensitiveDpMap.contains(key) =>
                key -> caseInsensitiveDpMap(key)
              case (key, _) =>
                throw RapidsHiveErrors.dynamicPartitionKeyNotAmongWrittenPartitionPathsError(
                  key)
            }
            val partitionColumnNames = table.partitionColumnNames
            val tablePath = new Path(table.location)
            val partitionPath = ExternalCatalogUtils.generatePartitionPath(updatedPartitionSpec,
              partitionColumnNames, tablePath)

            val fs = partitionPath.getFileSystem(hadoopConf)
            if (fs.exists(partitionPath)) {
              if (!fs.delete(partitionPath, true)) {
                throw RapidsHiveErrors.cannotRemovePartitionDirError(partitionPath)
              }
            }
          }
        }

        externalCatalog.loadDynamicPartitions(
          db = table.database,
          table = table.identifier.table,
          tmpLocation.toString,
          partitionSpec,
          overwrite,
          numDynamicPartitions)
      } else {
        // scalastyle:off
        // ifNotExists is only valid with static partition, refer to
        // https://cwiki.apache.org/confluence/display/Hive/LanguageManual+DML#LanguageManualDML-InsertingdataintoHiveTablesfromqueries
        // scalastyle:on
        val oldPart =
          externalCatalog.getPartitionOption(
            table.database,
            table.identifier.table,
            partitionSpec)

        var doHiveOverwrite = overwrite

        if (oldPart.isEmpty || !ifPartitionNotExists) {
          // SPARK-29295: When insert overwrite to a Hive external table partition, if the
          // partition does not exist, Hive will not check if the external partition directory
          // exists or not before copying files. So if users drop the partition, and then do
          // insert overwrite to the same partition, the partition will have both old and new
          // data. We construct partition path. If the path exists, we delete it manually.
          val partitionPath = if (oldPart.isEmpty && overwrite
              && table.tableType == CatalogTableType.EXTERNAL) {
            val partitionColumnNames = table.partitionColumnNames
            val tablePath = new Path(table.location)
            Some(ExternalCatalogUtils.generatePartitionPath(partitionSpec,
              partitionColumnNames, tablePath))
          } else {
            oldPart.flatMap(_.storage.locationUri.map(uri => new Path(uri)))
          }

          // SPARK-18107: Insert overwrite runs much slower than hive-client.
          // Newer Hive largely improves insert overwrite performance. As Spark uses older Hive
          // version and we may not want to catch up new Hive version every time. We delete the
          // Hive partition first and then load data file into the Hive partition.
          val hiveVersion = externalCatalog.asInstanceOf[ExternalCatalogWithListener]
            .unwrapped.asInstanceOf[HiveExternalCatalog]
            .client
            .version
          // SPARK-31684:
          // For Hive 2.0.0 and onwards, as https://issues.apache.org/jira/browse/HIVE-11940
          // has been fixed, and there is no performance issue anymore. We should leave the
          // overwrite logic to hive to avoid failure in `FileSystem#checkPath` when the table
          // and partition locations do not belong to the same `FileSystem`
          // TODO(SPARK-31675): For Hive 2.2.0 and earlier, if the table and partition locations
          // do not belong together, we will still get the same error thrown by hive encryption
          // check. see https://issues.apache.org/jira/browse/HIVE-14380.
          // So we still disable for Hive overwrite for Hive 1.x for better performance because
          // the partition and table are on the same cluster in most cases.
          if (partitionPath.nonEmpty && overwrite && hiveVersion < v2_0) {
            partitionPath.foreach { path =>
              val fs = path.getFileSystem(hadoopConf)
              if (fs.exists(path)) {
                if (!fs.delete(path, true)) {
                  throw RapidsHiveErrors.cannotRemovePartitionDirError(path)
                }
                // Don't let Hive do overwrite operation since it is slower.
                doHiveOverwrite = false
              }
            }
          }

          // inheritTableSpecs is set to true. It should be set to false for an IMPORT query
          // which is currently considered as a Hive native command.
          val inheritTableSpecs = true
          externalCatalog.loadPartition(
            table.database,
            table.identifier.table,
            tmpLocation.toString,
            partitionSpec,
            isOverwrite = doHiveOverwrite,
            inheritTableSpecs = inheritTableSpecs,
            isSrcLocal = false)
        }
      }
    } else {
      externalCatalog.loadTable(
        table.database,
        table.identifier.table,
        tmpLocation.toString, // TODO (From Apache Spark): URI
        overwrite,
        isSrcLocal = false)
    }
  }

  override def requireSingleBatch: Boolean = false // TODO: Re-evaluate. If partitioned or bucketed?
}