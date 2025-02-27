/*
 * Copyright (c) 2020-2023, NVIDIA CORPORATION.
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

package com.nvidia.spark.rapids

import java.io.File
import java.math.RoundingMode

import ai.rapids.cudf.{ContiguousTable, DeviceMemoryBuffer, HostMemoryBuffer, Table}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{spy, times, verify}
import org.scalatest.mockito.MockitoSugar

import org.apache.spark.sql.rapids.RapidsDiskBlockManager
import org.apache.spark.sql.types.{DataType, DecimalType, DoubleType, IntegerType, StringType}

class RapidsDiskStoreSuite extends FunSuiteWithTempDir with Arm with MockitoSugar {

  private def buildContiguousTable(): ContiguousTable = {
    withResource(new Table.TestBuilder()
        .column(5, null.asInstanceOf[java.lang.Integer], 3, 1)
        .column("five", "two", null, null)
        .column(5.0, 2.0, 3.0, 1.0)
        .decimal64Column(-5, RoundingMode.UNNECESSARY, 0, null, -1.4, 10.123)
        .build()) { table =>
      table.contiguousSplit()(0)
    }
  }

  test("spill updates catalog") {
    val bufferId = MockRapidsBufferId(7, canShareDiskPaths = false)
    val spillPriority = -7
    val hostStoreMaxSize = 1L * 1024 * 1024
    withResource(new RapidsDeviceMemoryStore) { devStore =>
      val catalog = spy(new RapidsBufferCatalog(devStore))
      withResource(new RapidsHostMemoryStore(hostStoreMaxSize, hostStoreMaxSize)) {
        hostStore =>
          devStore.setSpillStore(hostStore)
          withResource(new RapidsDiskStore(mock[RapidsDiskBlockManager])) { diskStore =>
            assertResult(0)(diskStore.currentSize)
            hostStore.setSpillStore(diskStore)
            val (bufferSize, handle) =
              addTableToCatalog(catalog, bufferId, spillPriority)
            val path = handle.id.getDiskPath(null)
            assert(!path.exists())
            catalog.synchronousSpill(devStore, 0)
            catalog.synchronousSpill(hostStore, 0)
            assertResult(0)(hostStore.currentSize)
            assertResult(bufferSize)(diskStore.currentSize)
            assert(path.exists)
            assertResult(bufferSize)(path.length)
            verify(catalog, times(3)).registerNewBuffer(ArgumentMatchers.any[RapidsBuffer])
            verify(catalog).removeBufferTier(
              ArgumentMatchers.eq(handle.id), ArgumentMatchers.eq(StorageTier.DEVICE))
            withResource(catalog.acquireBuffer(handle)) { buffer =>
              assertResult(StorageTier.DISK)(buffer.storageTier)
              assertResult(bufferSize)(buffer.size)
              assertResult(handle.id)(buffer.id)
              assertResult(spillPriority)(buffer.getSpillPriority)
            }
          }
      }
    }
  }

  test("get columnar batch") {
    val bufferId = MockRapidsBufferId(1, canShareDiskPaths = false)
    val bufferPath = bufferId.getDiskPath(null)
    assert(!bufferPath.exists)
    val sparkTypes = Array[DataType](IntegerType, StringType, DoubleType,
      DecimalType(ai.rapids.cudf.DType.DECIMAL64_MAX_PRECISION, 5))
    val spillPriority = -7
    val hostStoreMaxSize = 1L * 1024 * 1024
    withResource(new RapidsDeviceMemoryStore) { devStore =>
      val catalog = new RapidsBufferCatalog(devStore)
      withResource(new RapidsHostMemoryStore(hostStoreMaxSize, hostStoreMaxSize)) {
        hostStore =>
          devStore.setSpillStore(hostStore)
          withResource(new RapidsDiskStore(mock[RapidsDiskBlockManager])) {
            diskStore =>
              hostStore.setSpillStore(diskStore)
              val (_, handle) = addTableToCatalog(catalog, bufferId, spillPriority)
              assert(!handle.id.getDiskPath(null).exists())
              val expectedTable = withResource(catalog.acquireBuffer(handle)) { buffer =>
                assertResult(StorageTier.DEVICE)(buffer.storageTier)
                withResource(buffer.getColumnarBatch(sparkTypes)) { beforeSpill =>
                  withResource(GpuColumnVector.from(beforeSpill)) { table =>
                    table.contiguousSplit()(0)
                  }
                } // closing the batch from the store so that we can spill it
              }
              withResource(expectedTable) { _ =>
                withResource(
                    GpuColumnVector.from(expectedTable.getTable, sparkTypes)) { expectedBatch =>
                  catalog.synchronousSpill(devStore, 0)
                  catalog.synchronousSpill(hostStore, 0)
                  withResource(catalog.acquireBuffer(handle)) { buffer =>
                    assertResult(StorageTier.DISK)(buffer.storageTier)
                    withResource(buffer.getColumnarBatch(sparkTypes)) { actualBatch =>
                      TestUtils.compareBatches(expectedBatch, actualBatch)
                    }
                  }
                }
              }
          }
      }
    }
  }

  test("get memory buffer") {
    val bufferId = MockRapidsBufferId(1, canShareDiskPaths = false)
    val bufferPath = bufferId.getDiskPath(null)
    assert(!bufferPath.exists)
    val spillPriority = -7
    val hostStoreMaxSize = 1L * 1024 * 1024
    withResource(new RapidsDeviceMemoryStore) { devStore =>
      val catalog = new RapidsBufferCatalog(devStore)
      withResource(new RapidsHostMemoryStore(hostStoreMaxSize, hostStoreMaxSize)) {
        hostStore =>
          devStore.setSpillStore(hostStore)
          withResource(new RapidsDiskStore(mock[RapidsDiskBlockManager])) { diskStore =>
            hostStore.setSpillStore(diskStore)
            val (_, handle) = addTableToCatalog(catalog, bufferId, spillPriority)
            assert(!handle.id.getDiskPath(null).exists())
            val expectedBuffer = withResource(catalog.acquireBuffer(handle)) { buffer =>
              assertResult(StorageTier.DEVICE)(buffer.storageTier)
              withResource(buffer.getMemoryBuffer) { devbuf =>
                closeOnExcept(HostMemoryBuffer.allocate(devbuf.getLength)) { hostbuf =>
                  hostbuf.copyFromDeviceBuffer(devbuf.asInstanceOf[DeviceMemoryBuffer])
                  hostbuf
                }
              }
            }
            withResource(expectedBuffer) { expectedBuffer =>
              catalog.synchronousSpill(devStore, 0)
              catalog.synchronousSpill(hostStore, 0)
              withResource(catalog.acquireBuffer(handle)) { buffer =>
                assertResult(StorageTier.DISK)(buffer.storageTier)
                withResource(buffer.getMemoryBuffer) { actualBuffer =>
                  assert(actualBuffer.isInstanceOf[HostMemoryBuffer])
                  val actualHostBuffer = actualBuffer.asInstanceOf[HostMemoryBuffer]
                  assertResult(expectedBuffer.asByteBuffer)(actualHostBuffer.asByteBuffer)
                }
              }
            }
          }
      }
    }
  }

  test("exclusive spill files are deleted when buffer deleted") {
    testBufferFileDeletion(canShareDiskPaths = false)
  }

  test("shared spill files are not deleted when a buffer is deleted") {
    testBufferFileDeletion(canShareDiskPaths = true)
  }

  private def testBufferFileDeletion(canShareDiskPaths: Boolean): Unit = {
    val bufferId = MockRapidsBufferId(1, canShareDiskPaths)
    val bufferPath = bufferId.getDiskPath(null)
    assert(!bufferPath.exists)
    val spillPriority = -7
    val hostStoreMaxSize = 1L * 1024 * 1024
    withResource(new RapidsDeviceMemoryStore) { devStore =>
      val catalog = new RapidsBufferCatalog(devStore)
      withResource(new RapidsHostMemoryStore(hostStoreMaxSize, hostStoreMaxSize)) {
        hostStore =>
          devStore.setSpillStore(hostStore)
          withResource(new RapidsDiskStore(mock[RapidsDiskBlockManager])) { diskStore =>
            hostStore.setSpillStore(diskStore)
            val (_, handle) = addTableToCatalog(catalog, bufferId, spillPriority)
            val bufferPath = handle.id.getDiskPath(null)
            assert(!bufferPath.exists())
            catalog.synchronousSpill(devStore, 0)
            catalog.synchronousSpill(hostStore, 0)
            assert(bufferPath.exists)
            handle.close()
            if (canShareDiskPaths) {
              assert(bufferPath.exists())
            } else {
              assert(!bufferPath.exists)
            }
          }
      }
    }
  }

  private def addTableToCatalog(
      catalog: RapidsBufferCatalog,
      bufferId: RapidsBufferId,
      spillPriority: Long): (Long, RapidsBufferHandle) = {
    withResource(buildContiguousTable()) { ct =>
      val bufferSize = ct.getBuffer.getLength
      // store takes ownership of the table
      val handle = catalog.addContiguousTable(
        bufferId,
        ct,
        spillPriority,
        false)
      (bufferSize, handle)
    }
  }

  case class MockRapidsBufferId(
      tableId: Int,
      override val canShareDiskPaths: Boolean) extends RapidsBufferId {
    override def getDiskPath(diskBlockManager: RapidsDiskBlockManager): File =
      new File(TEST_FILES_ROOT, s"diskbuffer-$tableId")
  }
}
