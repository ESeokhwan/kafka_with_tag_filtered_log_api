/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.server

import org.apache.kafka.common.{TopicPartition, Uuid}
import org.apache.kafka.common.compress.Compression
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.record.{MemoryRecords, SimpleRecord}
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord
import org.apache.kafka.coordinator.globalsequence.generated.{GlobalSequenceIndexLogKey, GlobalSequenceIndexLogValue}
import org.apache.kafka.coordinator.globalsequence.{GlobalSequenceCoordinatorRecordSerde, GlobalSequenceIndexLogEntry, GlobalSequenceIndexRecord}
import org.apache.kafka.server.common.ApiMessageAndVersion
import org.apache.kafka.server.storage.log.FetchIsolation
import org.apache.kafka.storage.internals.log.{FetchDataInfo, LogOffsetMetadata, UnifiedLog}
import org.apache.kafka.test.TestUtils.assertFutureThrows
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{Test, Timeout}
import org.mockito.Mockito.{mock, verify, when}

import java.util.concurrent.TimeUnit
import scala.util.Using

@Timeout(30)
class ReplicaManagerGlobalSequenceIndexLogReaderTest {
  private val topicPartition = new TopicPartition("__global_sequence_index", 0)
  private val topicId = Uuid.randomUuid()
  private val serde = new GlobalSequenceCoordinatorRecordSerde

  @Test
  def testReadsAllocationsAndTombstonesWithConfiguredByteBound(): Unit = {
    val replicaManager = mock(classOf[ReplicaManager])
    val log = mock(classOf[UnifiedLog])
    val allocation = new GlobalSequenceIndexRecord(topicId, 10L, 3, 2, 50L)
    val records = MemoryRecords.withRecords(
      5L,
      Compression.NONE,
      serialized(allocationRecord(allocation)),
      serialized(tombstoneRecord(topicId, 20L))
    )

    when(replicaManager.getLog(topicPartition)).thenReturn(Some(log))
    when(log.read(5L, 128, FetchIsolation.HIGH_WATERMARK, true))
      .thenReturn(new FetchDataInfo(new LogOffsetMetadata(5L), records))

    Using.resource(new ReplicaManagerGlobalSequenceIndexLogReader(replicaManager, 128)) { reader =>
      val result = reader.read(topicPartition, 5L, 7L, 1024).get(10, TimeUnit.SECONDS)

      assertEquals(Seq(
        GlobalSequenceIndexLogEntry.allocation(5L, allocation),
        GlobalSequenceIndexLogEntry.tombstone(6L, topicId, 20L)
      ), result.entries.toArray.toSeq)
      assertEquals(7L, result.nextLogOffset)
      assertTrue(result.reachedEndOffset)
      assertEquals(records.sizeInBytes, result.bytesRead)
      verify(log).read(5L, 128, FetchIsolation.HIGH_WATERMARK, true)
    }
  }

  @Test
  def testReportsContinuationForPartialChunk(): Unit = {
    val replicaManager = mock(classOf[ReplicaManager])
    val log = mock(classOf[UnifiedLog])
    val allocation = new GlobalSequenceIndexRecord(topicId, 10L, 3, 2, 50L)
    val records = MemoryRecords.withRecords(5L, Compression.NONE, serialized(allocationRecord(allocation)))

    when(replicaManager.getLog(topicPartition)).thenReturn(Some(log))
    when(log.read(5L, 128, FetchIsolation.HIGH_WATERMARK, true))
      .thenReturn(new FetchDataInfo(new LogOffsetMetadata(5L), records))

    Using.resource(new ReplicaManagerGlobalSequenceIndexLogReader(replicaManager, 128)) { reader =>
      val result = reader.read(topicPartition, 5L, 10L, 128).get(10, TimeUnit.SECONDS)

      assertEquals(6L, result.nextLogOffset)
      assertFalse(result.reachedEndOffset)
    }
  }

  @Test
  def testFailsWhenLocalLogIsUnavailable(): Unit = {
    val replicaManager = mock(classOf[ReplicaManager])
    when(replicaManager.getLog(topicPartition)).thenReturn(None)

    Using.resource(new ReplicaManagerGlobalSequenceIndexLogReader(replicaManager, 128)) { reader =>
      assertFutureThrows(
        classOf[NotLeaderOrFollowerException],
        reader.read(topicPartition, 0L, 1L, 128)
      )
    }
  }

  private def serialized(record: CoordinatorRecord): SimpleRecord = {
    new SimpleRecord(serde.serializeKey(record), serde.serializeValue(record))
  }

  private def allocationRecord(indexRecord: GlobalSequenceIndexRecord): CoordinatorRecord = {
    CoordinatorRecord.record(
      new GlobalSequenceIndexLogKey()
        .setTopicId(indexRecord.topicId)
        .setGlobalOffset(indexRecord.globalBaseOffset),
      new ApiMessageAndVersion(
        new GlobalSequenceIndexLogValue()
          .setRecordsCount(indexRecord.recordCount)
          .setPartitionIndex(indexRecord.partitionIndex)
          .setPartitionOffset(indexRecord.partitionBaseOffset),
        0.toShort
      )
    )
  }

  private def tombstoneRecord(topicId: Uuid, globalBaseOffset: Long): CoordinatorRecord = {
    CoordinatorRecord.tombstone(
      new GlobalSequenceIndexLogKey()
        .setTopicId(topicId)
        .setGlobalOffset(globalBaseOffset)
    )
  }
}
