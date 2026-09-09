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
package org.apache.kafka.coordinator.globalsequence;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.CoordinatorNotAvailableException;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorResult;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRuntime;
import org.apache.kafka.coordinator.globalsequence.metrics.GlobalSequenceCoordinatorMetrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalSequenceCoordinatorTest {
    private static final int COMMIT_TIMEOUT_MS = 1234;
    private static final int NUM_PARTITIONS = 7;

    private CoordinatorRuntime<GlobalSequenceCoordinatorShard, CoordinatorRecord> runtime;
    private GlobalSequenceCoordinatorMetrics metrics;
    private GlobalSequenceIndexLogReader indexLogReader;
    private GlobalSequenceCoordinator coordinator;

    @BeforeEach
    void setUp() {
        runtime = mockRuntime();
        metrics = mock(GlobalSequenceCoordinatorMetrics.class);
        indexLogReader = mock(GlobalSequenceIndexLogReader.class);
        coordinator = new GlobalSequenceCoordinator(
            new LogContext(),
            config(),
            runtime,
            metrics,
            indexLogReader
        );
    }

    @Test
    void testAppendIndexRejectsRequestWhenCoordinatorIsInactive() {
        assertThrows(CoordinatorNotAvailableException.class, () -> coordinator.appendIndex(request()));
    }

    @Test
    void testAppendIndexRoutesAndDelegatesWithConfiguredTimeout() {
        GlobalSequenceAppendRequest request = request();
        GlobalSequenceAppendResult expectedResponse = new GlobalSequenceAppendResult(10L, request.recordCount(), false);
        CompletableFuture<GlobalSequenceAppendResult> expectedFuture =
            CompletableFuture.completedFuture(expectedResponse);
        TopicPartition expectedTopicPartition = new TopicPartition(
            Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME,
            Utils.abs(request.topicId().hashCode()) % NUM_PARTITIONS
        );

        when(runtime.<GlobalSequenceAppendPreparation>scheduleReadOperationWithEpoch(
            eq("prepare-global-sequence-index-append"),
            eq(expectedTopicPartition),
            any()
        )).thenReturn(CompletableFuture.completedFuture(GlobalSequenceAppendPreparation.fresh()));
        when(runtime.<GlobalSequenceAppendResult>scheduleWriteOperation(
            eq("append-global-sequence-index"),
            eq(expectedTopicPartition),
            eq(Duration.ofMillis(COMMIT_TIMEOUT_MS)),
            any()
        )).thenReturn(expectedFuture);

        coordinator.startup(() -> NUM_PARTITIONS);

        assertEquals(expectedResponse, coordinator.appendIndex(request).join());

        ArgumentCaptor<CoordinatorRuntime.CoordinatorWriteOperation<
            GlobalSequenceCoordinatorShard,
            GlobalSequenceAppendResult,
            CoordinatorRecord
            >> operationCaptor = writeOperationCaptor();
        verify(runtime).scheduleWriteOperation(
            eq("append-global-sequence-index"),
            eq(expectedTopicPartition),
            eq(Duration.ofMillis(COMMIT_TIMEOUT_MS)),
            operationCaptor.capture()
        );

        GlobalSequenceCoordinatorShard shard = mock(GlobalSequenceCoordinatorShard.class);
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> expectedResult =
            new CoordinatorResult<>(List.of(), expectedResponse);
        when(shard.appendIndex(request)).thenReturn(expectedResult);

        assertSame(expectedResult, operationCaptor.getValue().generateRecordsAndResult(shard));
    }

    @Test
    void testElectionAndResignationDelegateToRuntime() {
        assertThrows(CoordinatorNotAvailableException.class, () -> coordinator.onElection(3, 8));
        assertThrows(
            CoordinatorNotAvailableException.class,
            () -> coordinator.onResignation(3, OptionalInt.of(8))
        );

        coordinator.startup(() -> NUM_PARTITIONS);
        coordinator.onElection(3, 8);
        coordinator.onResignation(3, OptionalInt.of(9));
        coordinator.onResignation(4, OptionalInt.empty());

        verify(runtime).scheduleLoadOperation(
            new TopicPartition(Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME, 3),
            8
        );
        verify(runtime).scheduleUnloadOperation(
            new TopicPartition(Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME, 3),
            OptionalInt.of(9)
        );
        verify(runtime).scheduleUnloadOperation(
            new TopicPartition(Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME, 4),
            OptionalInt.empty()
        );
    }

    @Test
    void testOldPhysicalBatchRetryScansMixedIndexLogAndRechecksInWriteOperation() {
        GlobalSequenceAppendRequest request = new GlobalSequenceAppendRequest(
            Uuid.randomUuid(),
            3,
            42L,
            4
        );
        TopicPartition topicPartition = new TopicPartition(
            Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME,
            Utils.abs(request.topicId().hashCode()) % NUM_PARTITIONS
        );
        GlobalSequencePhysicalIndexScanPlan plan = new GlobalSequencePhysicalIndexScanPlan(
            request.topicId(),
            request.partitionIndex(),
            request.partitionBaseOffset(),
            request.recordCount(),
            4L,
            10L,
            7
        );
        GlobalSequenceIndexRecord existing = new GlobalSequenceIndexRecord(
            request.topicId(),
            8L,
            request.recordCount(),
            request.partitionIndex(),
            request.partitionBaseOffset()
        );
        GlobalSequenceAppendResult expected = existing.toAppendResult(true);
        GlobalSequenceCoordinatorShard shard = mock(GlobalSequenceCoordinatorShard.class);

        when(runtime.<GlobalSequenceAppendPreparation>scheduleReadOperationWithEpoch(
            eq("prepare-global-sequence-index-append"),
            eq(topicPartition),
            any()
        )).thenReturn(CompletableFuture.completedFuture(GlobalSequenceAppendPreparation.scan(plan)));
        when(indexLogReader.read(
            eq(topicPartition),
            eq(4L),
            eq(10L),
            eq(GlobalSequenceCoordinatorConfig.INDEX_LOG_READ_MAX_BYTES_DEFAULT)
        )).thenReturn(CompletableFuture.completedFuture(new GlobalSequenceIndexLogReadResult(
            List.of(
                GlobalSequenceIndexLogEntry.allocation(4L, new GlobalSequenceIndexRecord(
                    Uuid.randomUuid(), 0L, 1, request.partitionIndex(), request.partitionBaseOffset()
                )),
                GlobalSequenceIndexLogEntry.allocation(5L, new GlobalSequenceIndexRecord(
                    request.topicId(), 4L, 2, request.partitionIndex() + 1, request.partitionBaseOffset()
                )),
                GlobalSequenceIndexLogEntry.allocation(6L, existing)
            ),
            10L,
            true,
            256
        )));
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> writeResult =
            new CoordinatorResult<>(List.of(), expected);
        when(shard.appendIndexAfterScan(plan, Optional.of(existing), 10L, 7)).thenReturn(writeResult);
        when(runtime.<GlobalSequenceAppendResult>scheduleWriteOperationWithEpoch(
            eq("append-global-sequence-index-after-physical-scan"),
            eq(topicPartition),
            eq(Duration.ofMillis(COMMIT_TIMEOUT_MS)),
            any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            CoordinatorRuntime.CoordinatorWriteOperationWithEpoch<
                GlobalSequenceCoordinatorShard,
                GlobalSequenceAppendResult,
                CoordinatorRecord
                > operation = invocation.getArgument(3);
            return CompletableFuture.completedFuture(operation.generateRecordsAndResult(shard, 10L, 7).response());
        });

        coordinator.startup(() -> NUM_PARTITIONS);

        assertEquals(expected, coordinator.appendIndex(request).join());
        verify(indexLogReader).read(
            topicPartition,
            4L,
            10L,
            GlobalSequenceCoordinatorConfig.INDEX_LOG_READ_MAX_BYTES_DEFAULT
        );
        verify(shard).appendIndexAfterScan(plan, Optional.of(existing), 10L, 7);
    }

    @Test
    void testLookupRoutesAndDelegatesToReadOperation() {
        GlobalSequenceLookupRequest request = new GlobalSequenceLookupRequest(
            Uuid.randomUuid(),
            2L,
            5L
        );
        GlobalSequenceLookupResult expectedResult = new GlobalSequenceLookupResult(List.of(
            new GlobalSequenceIndexRecord(request.topicId(), 0L, 5, 1, 10L)
        ));
        GlobalSequenceIndexLookupPreparation expectedPreparation =
            GlobalSequenceIndexLookupPreparation.cached(expectedResult);
        CompletableFuture<GlobalSequenceIndexLookupPreparation> expectedFuture =
            CompletableFuture.completedFuture(expectedPreparation);
        TopicPartition expectedTopicPartition = new TopicPartition(
            Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME,
            Utils.abs(request.topicId().hashCode()) % NUM_PARTITIONS
        );

        when(runtime.<GlobalSequenceIndexLookupPreparation>scheduleReadOperationWithEpoch(
            eq("prepare-global-sequence-index-lookup"),
            eq(expectedTopicPartition),
            any()
        )).thenReturn(expectedFuture);

        coordinator.startup(() -> NUM_PARTITIONS);
        assertEquals(expectedResult, coordinator.lookupIndex(request).join());

        ArgumentCaptor<CoordinatorRuntime.CoordinatorReadOperationWithEpoch<
            GlobalSequenceCoordinatorShard,
            GlobalSequenceIndexLookupPreparation
            >> operationCaptor = lookupPreparationOperationCaptor();
        verify(runtime).scheduleReadOperationWithEpoch(
            eq("prepare-global-sequence-index-lookup"),
            eq(expectedTopicPartition),
            operationCaptor.capture()
        );

        GlobalSequenceCoordinatorShard shard = mock(GlobalSequenceCoordinatorShard.class);
        when(shard.prepareLookup(request, 10L, 7)).thenReturn(expectedPreparation);
        assertSame(expectedPreparation, operationCaptor.getValue().generateResponse(shard, 10L, 7));
    }

    @Test
    void testLookupScansIndexLogFromCheckpointAndCompletesOnCoordinatorShard() {
        GlobalSequenceLookupRequest request = new GlobalSequenceLookupRequest(
            Uuid.randomUuid(),
            2L,
            5L
        );
        TopicPartition topicPartition = new TopicPartition(
            Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME,
            Utils.abs(request.topicId().hashCode()) % NUM_PARTITIONS
        );
        GlobalSequenceIndexScanPlan plan = new GlobalSequenceIndexScanPlan(
            request.topicId(),
            request.globalStartOffset(),
            request.globalEndOffsetExclusive(),
            4L,
            10L,
            7,
            request.maxIndexEntries()
        );
        GlobalSequenceIndexRecord first = new GlobalSequenceIndexRecord(
            request.topicId(), 0L, 3, 0, 20L
        );
        GlobalSequenceIndexRecord second = new GlobalSequenceIndexRecord(
            request.topicId(), 3L, 2, 1, 30L
        );
        GlobalSequenceLookupResult expected = new GlobalSequenceLookupResult(List.of(first, second));
        GlobalSequenceCoordinatorShard shard = mock(GlobalSequenceCoordinatorShard.class);

        when(runtime.<GlobalSequenceIndexLookupPreparation>scheduleReadOperationWithEpoch(
            eq("prepare-global-sequence-index-lookup"),
            eq(topicPartition),
            any()
        )).thenReturn(CompletableFuture.completedFuture(GlobalSequenceIndexLookupPreparation.scan(plan)));
        when(indexLogReader.read(
            eq(topicPartition),
            eq(4L),
            eq(10L),
            eq(GlobalSequenceCoordinatorConfig.INDEX_LOG_READ_MAX_BYTES_DEFAULT)
        ))
            .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceIndexLogReadResult(
                List.of(
                    GlobalSequenceIndexLogEntry.allocation(4L, new GlobalSequenceIndexRecord(
                        Uuid.randomUuid(), 0L, 1, 0, 0L
                    )),
                    GlobalSequenceIndexLogEntry.allocation(5L, first)
                ),
                6L,
                false,
                128
            )));
        when(indexLogReader.read(
            eq(topicPartition),
            eq(6L),
            eq(10L),
            eq(GlobalSequenceCoordinatorConfig.INDEX_LOG_READ_MAX_BYTES_DEFAULT)
        ))
            .thenReturn(CompletableFuture.completedFuture(new GlobalSequenceIndexLogReadResult(
                List.of(GlobalSequenceIndexLogEntry.allocation(6L, second)),
                7L,
                false,
                128
            )));
        when(shard.completeLookup(eq(plan), eq(expected), eq(7))).thenReturn(expected);
        when(runtime.<GlobalSequenceLookupResult>scheduleReadOperationWithEpoch(
            eq("complete-global-sequence-index-lookup"),
            eq(topicPartition),
            any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            CoordinatorRuntime.CoordinatorReadOperationWithEpoch<
                GlobalSequenceCoordinatorShard,
                GlobalSequenceLookupResult
                > operation = invocation.getArgument(2);
            return CompletableFuture.completedFuture(operation.generateResponse(shard, 10L, 7));
        });

        coordinator.startup(() -> NUM_PARTITIONS);

        assertEquals(expected, coordinator.lookupIndex(request).join());
        verify(indexLogReader).read(
            topicPartition,
            4L,
            10L,
            GlobalSequenceCoordinatorConfig.INDEX_LOG_READ_MAX_BYTES_DEFAULT
        );
        verify(indexLogReader).read(
            topicPartition,
            6L,
            10L,
            GlobalSequenceCoordinatorConfig.INDEX_LOG_READ_MAX_BYTES_DEFAULT
        );
        verify(shard).completeLookup(plan, expected, 7);
    }

    @Test
    void testGlobalSequenceIndexTopicConfigs() {
        assertEquals(
            TopicConfig.CLEANUP_POLICY_COMPACT,
            coordinator.globalSequenceIndexTopicConfigs().get(TopicConfig.CLEANUP_POLICY_CONFIG)
        );
        assertEquals(
            GlobalSequenceCoordinatorConfig.INDEX_TOPIC_MIN_ISR_DEFAULT,
            coordinator.globalSequenceIndexTopicConfigs().get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG)
        );
        assertEquals(
            GlobalSequenceCoordinatorConfig.INDEX_TOPIC_SEGMENT_BYTES_DEFAULT,
            coordinator.globalSequenceIndexTopicConfigs().get(TopicConfig.SEGMENT_BYTES_CONFIG)
        );
    }

    @Test
    void testStartupAndShutdownAreIdempotent() throws Exception {
        coordinator.startup(() -> NUM_PARTITIONS);
        coordinator.startup(() -> NUM_PARTITIONS + 1);

        coordinator.shutdown();
        coordinator.shutdown();

        verify(runtime, times(1)).close();
        verify(metrics, times(1)).close();
        verify(indexLogReader, times(1)).close();
        assertThrows(CoordinatorNotAvailableException.class, () -> coordinator.appendIndex(request()));
    }

    private static GlobalSequenceCoordinatorConfig config() {
        return new GlobalSequenceCoordinatorConfig(new AbstractConfig(
            GlobalSequenceCoordinatorConfig.CONFIG_DEF,
            Map.of(GlobalSequenceCoordinatorConfig.COMMIT_TIMEOUT_MS_CONFIG, COMMIT_TIMEOUT_MS)
        ));
    }

    private static GlobalSequenceAppendRequest request() {
        return new GlobalSequenceAppendRequest(Uuid.randomUuid(), 2, 100L, 3);
    }

    @SuppressWarnings("unchecked")
    private static CoordinatorRuntime<GlobalSequenceCoordinatorShard, CoordinatorRecord> mockRuntime() {
        return (CoordinatorRuntime<GlobalSequenceCoordinatorShard, CoordinatorRecord>) mock(CoordinatorRuntime.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<CoordinatorRuntime.CoordinatorWriteOperation<
        GlobalSequenceCoordinatorShard,
        GlobalSequenceAppendResult,
        CoordinatorRecord
        >> writeOperationCaptor() {
        return ArgumentCaptor.forClass(CoordinatorRuntime.CoordinatorWriteOperation.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<CoordinatorRuntime.CoordinatorReadOperationWithEpoch<
        GlobalSequenceCoordinatorShard,
        GlobalSequenceIndexLookupPreparation
        >> lookupPreparationOperationCaptor() {
        return ArgumentCaptor.forClass(CoordinatorRuntime.CoordinatorReadOperationWithEpoch.class);
    }
}
