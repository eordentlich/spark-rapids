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
{"spark": "321cdh"}
spark-rapids-shim-json-lines ***/
package com.nvidia.spark.rapids.shims.spark321cdh;

import com.nvidia.spark.rapids._
import org.scalatest.FunSuite

import org.apache.spark.sql.types.{DayTimeIntervalType, YearMonthIntervalType}

class SparkShimsSuite extends FunSuite with FQSuiteName {
  test("spark shims version") {
    assert(VersionUtils.cmpSparkVersion(3, 2, 1) === 0)
  }

  test("shuffle manager class") {
    assert(ShimLoader.getRapidsShuffleManagerClass ===
      classOf[com.nvidia.spark.rapids.spark321cdh.RapidsShuffleManager].getCanonicalName)
  }

  test("TypeSig") {
    val check = TypeSig.DAYTIME + TypeSig.YEARMONTH
    assert(check.isSupportedByPlugin(DayTimeIntervalType()) == true)
    assert(check.isSupportedByPlugin(YearMonthIntervalType()) == true)
  }

}
