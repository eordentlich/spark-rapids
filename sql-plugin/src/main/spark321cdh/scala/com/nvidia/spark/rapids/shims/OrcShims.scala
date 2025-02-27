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
{"spark": "321cdh"}
spark-rapids-shim-json-lines ***/
package com.nvidia.spark.rapids.shims

import org.apache.orc.Reader

import org.apache.spark.sql.execution.datasources.orc.OrcUtils
import org.apache.spark.sql.types.DataType

// 320+ ORC shims
object OrcShims extends OrcShims321CDHBase {

  // orcTypeDescriptionString is renamed to getOrcSchemaString from 3.3+
  override def getOrcSchemaString(dt: DataType): String = {
    OrcUtils.orcTypeDescriptionString(dt)
  }

  // ORC Reader of the 321cdh Spark has no close method.
  // The resource is closed internally.
  def withReader[V](r: Reader)(block: Reader => V): V = {
    block(r)
  }

  // ORC Reader of the 321cdh Spark has no close method.
  // The resource is closed internally.
  def closeReader(reader: Reader): Unit = {
  }

}
