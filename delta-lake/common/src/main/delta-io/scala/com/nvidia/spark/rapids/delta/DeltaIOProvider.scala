/*
 * Copyright (c) 2022-2023, NVIDIA CORPORATION.
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

package com.nvidia.spark.rapids.delta

import scala.util.Try

import com.nvidia.spark.rapids._

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.delta.DeltaLog
import org.apache.spark.sql.delta.rapids.DeltaRuntimeShim
import org.apache.spark.sql.delta.sources.DeltaDataSource
import org.apache.spark.sql.execution.datasources.SaveIntoDataSourceCommand
import org.apache.spark.sql.rapids.ExternalSource
import org.apache.spark.sql.rapids.execution.UnshimmedTrampolineUtil
import org.apache.spark.sql.sources.CreatableRelationProvider

/**
 * Implements the DeltaProvider interface for open source delta.io Delta Lake.
 */
abstract class DeltaIOProvider extends DeltaProviderImplBase {
  override def getCreatableRelationRules: Map[Class[_ <: CreatableRelationProvider],
      CreatableRelationProviderRule[_ <: CreatableRelationProvider]] = {
    Seq(
      ExternalSource.toCreatableRelationProviderRule[DeltaDataSource](
        "Write to Delta Lake table",
        (a, conf, p, r) => {
          require(p.isDefined, "Must provide parent meta")
          new DeltaCreatableRelationProviderMeta(a, conf, p, r)
        })
    ).map(r => (r.getClassFor.asSubclass(classOf[CreatableRelationProvider]), r)).toMap
  }
}

class DeltaCreatableRelationProviderMeta(
    source: DeltaDataSource,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: DataFromReplacementRule)
    extends CreatableRelationProviderMeta[DeltaDataSource](source, conf, parent, rule) {
  require(parent.isDefined, "Must provide parent meta")
  private val saveCmd = parent.get.wrapped match {
    case s: SaveIntoDataSourceCommand => s
    case s =>
      throw new IllegalStateException(s"Expected SaveIntoDataSourceCommand, found ${s.getClass}")
  }

  override def tagSelfForGpu(): Unit = {
    if (!conf.isDeltaWriteEnabled) {
      willNotWorkOnGpu("Delta Lake output acceleration has been disabled. To enable set " +
          s"${RapidsConf.ENABLE_DELTA_WRITE} to true")
    }
    val path = saveCmd.options.get("path")
    if (path.isDefined) {
      val deltaLog = DeltaLog.forTable(SparkSession.active, path.get, saveCmd.options)
      RapidsDeltaUtils.tagForDeltaWrite(this, saveCmd.query.schema, deltaLog, saveCmd.options,
        SparkSession.active)
    } else {
      willNotWorkOnGpu("no path specified for Delta Lake table")
    }
  }

  override def convertToGpu(): GpuCreatableRelationProvider = new GpuDeltaDataSource(conf)
}

/**
 * Implements the Delta Probe interface for probing the Delta Lake provider for
 * open source delta.io Delta Lake.
 * @note This is instantiated via reflection from ShimLoader.
 */
class DeltaProbeImpl extends DeltaProbe {
  override def getDeltaProvider: DeltaProvider = {
    val cpuClassName = "org.apache.spark.sql.delta.sources.DeltaDataSource"
    val hasDeltaJar = UnshimmedTrampolineUtil.classIsLoadable(cpuClassName) &&
        Try(ShimReflectionUtils.loadClass(cpuClassName)).isSuccess
    if (hasDeltaJar) {
      DeltaRuntimeShim.getDeltaProvider
    } else {
      NoDeltaProvider
    }
  }
}
