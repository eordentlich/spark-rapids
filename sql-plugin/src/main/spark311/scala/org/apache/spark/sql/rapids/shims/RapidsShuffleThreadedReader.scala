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

/*** spark-rapids-shim-json-lines
{"spark": "311"}
{"spark": "312"}
{"spark": "313"}
spark-rapids-shim-json-lines ***/
package org.apache.spark.sql.rapids.shims

import ai.rapids.cudf.{NvtxColor, NvtxRange}

import org.apache.spark.{MapOutputTracker, SparkEnv, TaskContext}
import org.apache.spark.serializer.SerializerManager
import org.apache.spark.shuffle.ShuffleReadMetricsReporter
import org.apache.spark.sql.rapids.{RapidsShuffleThreadedReaderBase, ShuffleHandleWithMetrics}
import org.apache.spark.storage.BlockManager

class RapidsShuffleThreadedReader[K, C] (
    startMapIndex: Int,
    endMapIndex: Int,
    startPartition: Int,
    endPartition: Int,
    handle: ShuffleHandleWithMetrics[K, C, C],
    context: TaskContext,
    readMetrics: ShuffleReadMetricsReporter,
    maxBytesInFlight: Long,
    serializerManager: SerializerManager = SparkEnv.get.serializerManager,
    blockManager: BlockManager = SparkEnv.get.blockManager,
    mapOutputTracker: MapOutputTracker = SparkEnv.get.mapOutputTracker,
    canUseBatchFetch: Boolean = false,
    numReaderThreads: Int = 0)
    extends RapidsShuffleThreadedReaderBase[K, C](
      handle,
      context,
      readMetrics,
      maxBytesInFlight,
      serializerManager = serializerManager,
      blockManager = blockManager,
      mapOutputTracker = mapOutputTracker,
      canUseBatchFetch = canUseBatchFetch,
      numReaderThreads = numReaderThreads) {

  override protected def getMapSizes: GetMapSizesResult = {
    val shuffleId = handle.shuffleId
    val mapSizes = withResource(new NvtxRange("getMapSizesByExecId", NvtxColor.CYAN)) { _ =>
      mapOutputTracker.getMapSizesByExecutorId(
        shuffleId, startMapIndex, endMapIndex, startPartition, endPartition)
    }
    GetMapSizesResult(mapSizes, canEnableBatchFetch = true)
  }
}
