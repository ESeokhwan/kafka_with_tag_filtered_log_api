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

import kafka.utils.Logging
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.NotLeaderOrFollowerException
import org.apache.kafka.common.record.{FileRecords, MemoryRecords}
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord
import org.apache.kafka.coordinator.globalsequence.generated.{GlobalSequenceIndexLogKey, GlobalSequenceIndexLogValue, GlobalSequenceTopicMetadataKey}
import org.apache.kafka.coordinator.globalsequence.{GlobalSequenceCoordinatorRecordSerde, GlobalSequenceIndexLogEntry, GlobalSequenceIndexLogReadResult, GlobalSequenceIndexLogReader, GlobalSequenceIndexRecord}
import org.apache.kafka.server.storage.log.FetchIsolation
import org.apache.kafka.server.util.KafkaScheduler

import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, ScheduledFuture}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * Reads bounded chunks of the local committed `__global_sequence_index` log on a dedicated thread.
 */
class ReplicaManagerGlobalSequenceIndexLogReader(
  replicaManager: ReplicaManager,
  maxReadBytes: Int
) extends GlobalSequenceIndexLogReader with Logging {
  require(maxReadBytes > 0, "maxReadBytes must be positive")

  private val running = new AtomicBoolean(true)
  private val serde = new GlobalSequenceCoordinatorRecordSerde
  private val scheduler = new KafkaScheduler(1, true, "global-sequence-index-log-reader-")
  scheduler.startup()

  override def read(
    topicPartition: TopicPartition,
    startLogOffset: Long,
    endLogOffsetExclusive: Long,
    requestedMaxBytes: Int
  ): CompletableFuture[GlobalSequenceIndexLogReadResult] = {
    if (startLogOffset < 0)
      throw new IllegalArgumentException("startLogOffset must not be negative")
    if (endLogOffsetExclusive < startLogOffset)
      throw new IllegalArgumentException("endLogOffsetExclusive must not be smaller than startLogOffset")
    if (requestedMaxBytes <= 0)
      throw new IllegalArgumentException("maxBytes must be positive")

    val future = new CompletableFuture[GlobalSequenceIndexLogReadResult]()
    if (!running.get) {
      future.completeExceptionally(new IllegalStateException("Global sequence index log reader is closed."))
      return future
    }
    if (startLogOffset == endLogOffsetExclusive) {
      future.complete(new GlobalSequenceIndexLogReadResult(
        java.util.List.of(),
        startLogOffset,
        true,
        0
      ))
      return future
    }

    val scheduled: ScheduledFuture[_] = scheduler.scheduleOnce(
      s"Read global sequence index from $topicPartition at $startLogOffset",
      () => doRead(
        topicPartition,
        startLogOffset,
        endLogOffsetExclusive,
        Math.min(requestedMaxBytes, maxReadBytes),
        future
      )
    )
    if (scheduled.isCancelled) {
      future.completeExceptionally(new IllegalStateException("Global sequence index log reader is closed."))
    }
    future
  }

  private def doRead(
    topicPartition: TopicPartition,
    startLogOffset: Long,
    endLogOffsetExclusive: Long,
    readBytes: Int,
    future: CompletableFuture[GlobalSequenceIndexLogReadResult]
  ): Unit = {
    try {
      val log = replicaManager.getLog(topicPartition).getOrElse {
        throw new NotLeaderOrFollowerException(
          s"Cannot read global sequence index from $topicPartition because its local log is unavailable."
        )
      }
      val fetchDataInfo = log.read(startLogOffset, readBytes, FetchIsolation.HIGH_WATERMARK, true)
      val memoryRecords = fetchDataInfo.records match {
        case records: MemoryRecords => records
        case fileRecords: FileRecords =>
          val buffer = ByteBuffer.allocate(fileRecords.sizeInBytes)
          fileRecords.readInto(buffer, 0)
          MemoryRecords.readableRecords(buffer)
        case other => throw new IllegalStateException(
          s"Unexpected records implementation ${other.getClass.getName}"
        )
      }

      val entries = ListBuffer.empty[GlobalSequenceIndexLogEntry]
      var nextLogOffset = Math.max(startLogOffset, fetchDataInfo.fetchOffsetMetadata.messageOffset)
      memoryRecords.batches.asScala.foreach { batch =>
        if (!batch.isControlBatch && batch.baseOffset < endLogOffsetExclusive) {
          batch.asScala.iterator
            .takeWhile(_.offset < endLogOffsetExclusive)
            .foreach { record =>
              decode(record.offset, serde.deserialize(record.key, record.value)).foreach(entries += _)
            }
        }
        nextLogOffset = Math.max(nextLogOffset, Math.min(batch.nextOffset, endLogOffsetExclusive))
      }

      if (memoryRecords.sizeInBytes == 0) {
        nextLogOffset = endLogOffsetExclusive
      }
      future.complete(new GlobalSequenceIndexLogReadResult(
        entries.asJava,
        nextLogOffset,
        nextLogOffset >= endLogOffsetExclusive,
        memoryRecords.sizeInBytes
      ))
    } catch {
      case exception: Throwable => future.completeExceptionally(exception)
    }
  }

  private def decode(logOffset: Long, record: CoordinatorRecord): Option[GlobalSequenceIndexLogEntry] = {
    record.key match {
      case key: GlobalSequenceIndexLogKey if record.value == null =>
        Some(GlobalSequenceIndexLogEntry.tombstone(logOffset, key.topicId, key.globalOffset))
      case key: GlobalSequenceIndexLogKey =>
        record.value.message match {
          case value: GlobalSequenceIndexLogValue =>
            Some(GlobalSequenceIndexLogEntry.allocation(logOffset, new GlobalSequenceIndexRecord(
              key.topicId,
              key.globalOffset,
              value.recordsCount,
              value.partitionIndex,
              value.partitionOffset
            )))
          case other => throw new IllegalStateException(s"Unexpected global sequence index value $other")
        }
      case _: GlobalSequenceTopicMetadataKey => None
      case other => throw new IllegalStateException(s"Unexpected global sequence index key $other")
    }
  }

  override def close(): Unit = {
    if (running.compareAndSet(true, false)) {
      scheduler.shutdown()
    }
  }
}
