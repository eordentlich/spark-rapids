/*
 * Copyright (c) 2022, NVIDIA CORPORATION.
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

package org.apache.spark.sql.rapids

import ai.rapids.cudf.{ColumnVector}
import com.nvidia.spark.rapids.{Arm, GpuColumnVector, GpuUnaryExpression}

import org.apache.spark.sql.catalyst.expressions.{ExpectsInputTypes, Expression}
import org.apache.spark.sql.types.{AbstractDataType, DataType, NullType, StringType}

case class GpuRaiseError(child: Expression) extends GpuUnaryExpression with ExpectsInputTypes
  with Arm {

  override def dataType: DataType = NullType
  override def inputTypes: Seq[AbstractDataType] = Seq(StringType)
  override def toString: String = s"raise_error($child)"

  /** Could evaluating this expression cause side-effects, such as throwing an exception? */
  override def hasSideEffects: Boolean = true

  override protected def doColumnar(input: GpuColumnVector): ColumnVector = {
    if (input.getRowCount <= 0) {
      // For the case: when(condition, raise_error(col("a"))
      return GpuColumnVector.columnVectorFromNull(0, NullType)
    }

    // Take the first one as the error message
    withResource(input.getBase.getScalarElement(0)) { scalarMsg =>
      if (!scalarMsg.isValid()) {
        throw new RuntimeException()
      } else {
        throw new RuntimeException(scalarMsg.getJavaString())
      }
    }
  }

}
