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

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.errors.NotCoordinatorException;
import org.apache.kafka.common.errors.OffsetOutOfRangeException;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetricsShard;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorResult;
import org.apache.kafka.coordinator.common.runtime.MockCoordinatorTimer;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceIndexLogKey;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceIndexLogValue;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceTopicMetadataKey;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceTopicMetadataValue;
import org.apache.kafka.image.MetadataDelta;
import org.apache.kafka.image.MetadataImage;
import org.apache.kafka.image.TopicImage;
import org.apache.kafka.image.TopicsDelta;
import org.apache.kafka.image.TopicsImage;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.timeline.SnapshotRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalSequenceCoordinatorShardTest {
    private static final Uuid TOPIC_ID = Uuid.randomUuid();

    private GlobalSequenceStateRegistry stateRegistry;
    private GlobalSequenceIndexCache indexCache;
    private SnapshotRegistry snapshotRegistry;
    private MockCoordinatorTimer<Void, CoordinatorRecord> timer;
    private GlobalSequenceCoordinatorShard shard;

    @BeforeEach
    void setUp() {
        LogContext logContext = new LogContext();
        MockTime time = new MockTime();
        snapshotRegistry = new SnapshotRegistry(logContext);
        stateRegistry = new GlobalSequenceStateRegistry(snapshotRegistry);
        timer = new MockCoordinatorTimer<>(time);

        GlobalSequenceCoordinatorConfig config = new GlobalSequenceCoordinatorConfig(
            new AbstractConfig(GlobalSequenceCoordinatorConfig.CONFIG_DEF, Map.of())
        );
        indexCache = new GlobalSequenceIndexCache(config.indexCacheMaxEntries());
        shard = new GlobalSequenceCoordinatorShard(
            logContext,
            stateRegistry,
            indexCache,
            time,
            timer,
            config,
            mock(CoordinatorMetrics.class),
            mock(CoordinatorMetricsShard.class)
        );
    }

    @Test
    void testAppendIndexCreatesVersionZeroRecordAndResult() {
        GlobalSequenceAppendRequest request = new GlobalSequenceAppendRequest(
            TOPIC_ID,
            1,
            20L,
            3
        );

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> result = shard.appendIndex(request);

        CoordinatorRecord expectedRecord = CoordinatorRecord.record(
            new GlobalSequenceIndexLogKey()
                .setTopicId(TOPIC_ID)
                .setGlobalOffset(0L),
            new ApiMessageAndVersion(
                new GlobalSequenceIndexLogValue()
                    .setRecordsCount(3)
                    .setPartitionIndex(1)
                    .setPartitionOffset(20L),
                (short) 0
            )
        );
        CoordinatorRecord expectedMetadataRecord = topicMetadataRecord(TOPIC_ID, 3L);

        assertEquals(new GlobalSequenceAppendResult(0L, 3, false), result.response());
        assertEquals(2, result.records().size());
        assertEquals(expectedRecord, result.records().get(0));
        assertEquals(expectedMetadataRecord, result.records().get(1));
        assertTrue(result.replayRecords());
        assertFalse(stateRegistry.contains(TOPIC_ID));

        replay(expectedRecord);
        replay(expectedMetadataRecord);

        assertEquals(
            new GlobalSequenceLookupResult(List.of(
                new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L)
            )),
            shard.lookupIndex(
                new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 3L),
                SnapshotRegistry.LATEST_EPOCH
            )
        );
    }

    @Test
    void testAppendAllocatesContiguousRangesAndDeduplicatesRetry() {
        GlobalSequenceAppendRequest firstRequest = request(TOPIC_ID, 1, 20L, 3);
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> first = shard.appendIndex(firstRequest);
        replay(first.records().get(0));

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> duplicate = shard.appendIndex(firstRequest);
        assertEquals(new GlobalSequenceAppendResult(0L, 3, true), duplicate.response());
        assertEquals(0, duplicate.records().size());

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> second = shard.appendIndex(
            request(TOPIC_ID, 0, 8L, 2)
        );
        assertEquals(new GlobalSequenceAppendResult(3L, 2, false), second.response());
        replay(second.records().get(0));

        assertEquals(
            new GlobalSequenceLookupResult(List.of(
                new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L),
                new GlobalSequenceIndexRecord(TOPIC_ID, 3L, 2, 0, 8L)
            )),
            shard.lookupIndex(
                new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 5L),
                SnapshotRegistry.LATEST_EPOCH
            )
        );
    }

    @Test
    void testOldPhysicalBatchRetryBuildsScanPlanFromPhysicalCheckpoint() {
        LogContext logContext = new LogContext();
        MockTime time = new MockTime();
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(logContext);
        GlobalSequenceCoordinatorConfig config = new GlobalSequenceCoordinatorConfig(new AbstractConfig(
            GlobalSequenceCoordinatorConfig.CONFIG_DEF,
            Map.of(GlobalSequenceCoordinatorConfig.INDEX_CACHE_MAX_ENTRIES_CONFIG, 1)
        ));
        GlobalSequenceCoordinatorShard boundedShard = new GlobalSequenceCoordinatorShard(
            logContext,
            new GlobalSequenceStateRegistry(snapshotRegistry, 1, 2),
            new GlobalSequenceIndexCache(config.indexCacheMaxEntries()),
            time,
            new MockCoordinatorTimer<>(time),
            config,
            mock(CoordinatorMetrics.class),
            mock(CoordinatorMetricsShard.class)
        );
        GlobalSequenceIndexRecord existing = new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 1, 1, 20L);
        boundedShard.replay(
            0L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            coordinatorRecord(existing)
        );
        boundedShard.replay(
            1L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            coordinatorRecord(new GlobalSequenceIndexRecord(TOPIC_ID, 1L, 1, 0, 30L))
        );
        snapshotRegistry.idempotentCreateSnapshot(2L);
        GlobalSequenceAppendRequest request = request(TOPIC_ID, 1, 20L, 1);

        assertEquals(
            GlobalSequenceAppendPreparation.scan(new GlobalSequencePhysicalIndexScanPlan(
                TOPIC_ID,
                1,
                20L,
                1,
                0L,
                2L,
                7
            )),
            boundedShard.prepareAppend(request, 2L, 7)
        );
    }

    @Test
    void testConcurrentPhysicalBatchRetryIsRecheckedAfterScan() {
        GlobalSequencePhysicalIndexScanPlan plan = new GlobalSequencePhysicalIndexScanPlan(
            TOPIC_ID,
            1,
            20L,
            1,
            0L,
            1L,
            7
        );

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> first =
            shard.appendIndexAfterScan(plan, Optional.empty(), 1L, 7);
        replay(first.records().get(0));
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> concurrentRetry =
            shard.appendIndexAfterScan(plan, Optional.empty(), 1L, 7);

        assertEquals(new GlobalSequenceAppendResult(0L, 1, false), first.response());
        assertEquals(List.of(), concurrentRetry.records());
        assertEquals(new GlobalSequenceAppendResult(0L, 1, true), concurrentRetry.response());
    }

    @Test
    void testPhysicalRetryScanIsDiscardedAfterCoordinatorReelection() {
        GlobalSequencePhysicalIndexScanPlan plan = new GlobalSequencePhysicalIndexScanPlan(
            TOPIC_ID,
            1,
            20L,
            1,
            0L,
            1L,
            7
        );

        assertThrows(
            NotCoordinatorException.class,
            () -> shard.appendIndexAfterScan(plan, Optional.empty(), 1L, 8)
        );
    }

    @Test
    void testAppendRejectsConflictingRetryWithoutAdvancing() {
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> first = shard.appendIndex(
            request(TOPIC_ID, 1, 20L, 3)
        );
        replay(first.records().get(0));

        assertThrows(
            IllegalArgumentException.class,
            () -> shard.appendIndex(request(TOPIC_ID, 1, 20L, 4))
        );

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> next = shard.appendIndex(
            request(TOPIC_ID, 1, 21L, 1)
        );
        assertEquals(new GlobalSequenceAppendResult(3L, 1, false), next.response());
    }

    @Test
    void testReplayRestoresAllocationAndNextOffset() {
        CoordinatorRecord recoveredRecord = coordinatorRecord(
            new GlobalSequenceIndexRecord(TOPIC_ID, 7L, 3, 2, 30L)
        );

        replay(recoveredRecord);

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> duplicate = shard.appendIndex(
            request(TOPIC_ID, 2, 30L, 3)
        );
        assertEquals(new GlobalSequenceAppendResult(7L, 3, true), duplicate.response());
        assertEquals(0, duplicate.records().size());

        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> next = shard.appendIndex(
            request(TOPIC_ID, 0, 40L, 2)
        );
        assertEquals(new GlobalSequenceAppendResult(10L, 2, false), next.response());
    }

    @Test
    void testOnLoadedBootstrapsMetadataForLegacyAllocationLog() {
        replay(coordinatorRecord(new GlobalSequenceIndexRecord(TOPIC_ID, 7L, 3, 2, 30L)));

        shard.onLoaded(metadataImage(TOPIC_ID));

        List<MockCoordinatorTimer.ExpiredTimeout<Void, CoordinatorRecord>> expired = timer.poll();
        assertEquals(1, expired.size());
        assertEquals(
            GlobalSequenceCoordinatorShard.GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX + TOPIC_ID,
            expired.get(0).key
        );
        assertEquals(List.of(topicMetadataRecord(TOPIC_ID, 10L)), expired.get(0).result.records());

        replay(expired.get(0).result.records().get(0));
        assertEquals(Map.of(), stateRegistry.topicsMissingDurableMetadata());
    }

    @Test
    void testReplayRejectsOverlappingGlobalRanges() {
        GlobalSequenceIndexRecord existing = new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L);
        CoordinatorRecord record = coordinatorRecord(existing);
        replay(record);

        assertEquals(
            new GlobalSequenceLookupResult(List.of(existing)),
            shard.lookupIndex(
                new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 3L),
                SnapshotRegistry.LATEST_EPOCH
            )
        );
        assertThrows(
            IllegalStateException.class,
            () -> replay(coordinatorRecord(new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 2, 50L)))
        );
        assertThrows(
            IllegalStateException.class,
            () -> replay(coordinatorRecord(new GlobalSequenceIndexRecord(TOPIC_ID, 2L, 2, 3, 60L)))
        );
        assertEquals(
            new GlobalSequenceLookupResult(List.of(existing)),
            shard.lookupIndex(
                new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 3L),
                SnapshotRegistry.LATEST_EPOCH
            )
        );
    }

    @Test
    void testReplayTombstoneRemovesAllocationWithoutRewinding() {
        replay(coordinatorRecord(new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L)));

        replay(CoordinatorRecord.tombstone(
            new GlobalSequenceIndexLogKey()
                .setTopicId(TOPIC_ID)
                .setGlobalOffset(0L)
        ));
        replay(CoordinatorRecord.tombstone(
            new GlobalSequenceIndexLogKey()
                .setTopicId(TOPIC_ID)
                .setGlobalOffset(0L)
        ));

        assertThrows(
            OffsetOutOfRangeException.class,
            () -> shard.lookupIndex(
                new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 3L),
                SnapshotRegistry.LATEST_EPOCH
            )
        );
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> replacement = shard.appendIndex(
            request(TOPIC_ID, 1, 20L, 3)
        );
        assertEquals(new GlobalSequenceAppendResult(3L, 3, false), replacement.response());
    }

    @Test
    void testTombstoneDoesNotInvalidateCommittedCacheBeforeHighWatermark() {
        GlobalSequenceIndexRecord existing = new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L);
        replay(coordinatorRecord(existing));
        shard.onLoaded(metadataImage(TOPIC_ID));

        shard.replay(
            1L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            CoordinatorRecord.tombstone(
                new GlobalSequenceIndexLogKey().setTopicId(TOPIC_ID).setGlobalOffset(0L)
            )
        );

        GlobalSequenceLookupRequest request = new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 3L);
        assertEquals(new GlobalSequenceLookupResult(List.of(existing)), shard.lookupIndex(request, 1L));

        shard.onHighWatermarkUpdated(2L);
        assertThrows(OffsetOutOfRangeException.class, () -> shard.lookupIndex(request, 2L));
    }

    @Test
    void testRolledBackTombstoneDoesNotInvalidateCommittedCache() {
        GlobalSequenceIndexRecord existing = new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L);
        replay(coordinatorRecord(existing));
        shard.onLoaded(metadataImage(TOPIC_ID));
        shard.replay(
            1L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            CoordinatorRecord.tombstone(
                new GlobalSequenceIndexLogKey().setTopicId(TOPIC_ID).setGlobalOffset(0L)
            )
        );

        shard.onWrittenOffsetReverted(1L);
        shard.onHighWatermarkUpdated(2L);

        assertEquals(
            new GlobalSequenceLookupResult(List.of(existing)),
            shard.lookupIndex(new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 3L), 2L)
        );
    }

    @Test
    void testTopicsHaveIndependentSequences() {
        Uuid otherTopicId = Uuid.randomUuid();
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> first = shard.appendIndex(
            request(TOPIC_ID, 0, 0L, 2)
        );
        replay(first.records().get(0));
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> other = shard.appendIndex(
            request(otherTopicId, 0, 0L, 4)
        );

        assertEquals(0L, first.response().globalBaseOffset());
        assertEquals(0L, other.response().globalBaseOffset());
    }

    @Test
    void testDeletedTopicClearsAllInMemoryState() {
        Uuid otherTopicId = Uuid.randomUuid();
        GlobalSequenceIndexRecord deletedTopicRecord = new GlobalSequenceIndexRecord(
            TOPIC_ID,
            0L,
            1,
            0,
            10L
        );
        GlobalSequenceIndexRecord otherTopicRecord = new GlobalSequenceIndexRecord(
            otherTopicId,
            0L,
            1,
            0,
            20L
        );
        replay(coordinatorRecord(deletedTopicRecord));
        replay(coordinatorRecord(otherTopicRecord));
        shard.onLoaded(metadataImage(TOPIC_ID, otherTopicId));
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> uncommitted = shard.appendIndex(
            request(TOPIC_ID, 0, 11L, 1)
        );
        replay(uncommitted.records().get(0));
        assertEquals(1, stateRegistry.uncommittedAllocationCount());

        MetadataDelta delta = mock(MetadataDelta.class);
        TopicsDelta topicsDelta = mock(TopicsDelta.class);
        when(delta.topicsDelta()).thenReturn(topicsDelta);
        when(topicsDelta.deletedTopicIds()).thenReturn(Set.of(TOPIC_ID));
        shard.onNewMetadataImage(MetadataImage.EMPTY, delta);

        assertFalse(stateRegistry.contains(TOPIC_ID));
        assertTrue(stateRegistry.contains(otherTopicId));
        assertEquals(0, stateRegistry.uncommittedAllocationCount());
        assertEquals(1, indexCache.size());
        assertFalse(timer.contains(
            GlobalSequenceCoordinatorShard.GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX + TOPIC_ID
        ));
        assertEquals(
            new GlobalSequenceLookupResult(List.of(otherTopicRecord)),
            shard.lookupIndex(new GlobalSequenceLookupRequest(otherTopicId, 0L, 1L), 2L)
        );

        Uuid replacementTopicId = Uuid.randomUuid();
        assertEquals(
            0L,
            shard.appendIndex(request(replacementTopicId, 0, 0L, 1)).response().globalBaseOffset()
        );
    }

    @Test
    void testOnLoadedClearsStateForTopicsMissingFromMetadata() {
        replay(coordinatorRecord(new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 1, 0, 10L)));

        shard.onLoaded(MetadataImage.EMPTY);

        assertFalse(stateRegistry.contains(TOPIC_ID));
        assertEquals(0, indexCache.size());
        assertFalse(timer.contains(
            GlobalSequenceCoordinatorShard.GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX + TOPIC_ID
        ));
    }

    @Test
    void testLookupIndexUsesReplayedState() {
        GlobalSequenceIndexRecord first = new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 3, 1, 20L);
        GlobalSequenceIndexRecord second = new GlobalSequenceIndexRecord(TOPIC_ID, 3L, 2, 0, 8L);
        replay(coordinatorRecord(first));
        replay(coordinatorRecord(second));

        assertEquals(
            new GlobalSequenceLookupResult(List.of(first, second)),
            shard.lookupIndex(
                new GlobalSequenceLookupRequest(TOPIC_ID, 1L, 5L),
                SnapshotRegistry.LATEST_EPOCH
            )
        );
    }

    @Test
    void testCompleteLookupDiscardsResultFromOldCoordinatorEpoch() {
        GlobalSequenceLookupRequest request = new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 1L);
        GlobalSequenceIndexScanPlan plan = new GlobalSequenceIndexScanPlan(
            TOPIC_ID,
            0L,
            1L,
            0L,
            10L,
            7,
            1
        );
        GlobalSequenceLookupResult result = new GlobalSequenceLookupResult(List.of(
            new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 1, 0, 20L)
        ));

        assertThrows(NotCoordinatorException.class, () -> shard.completeLookup(plan, result, 8));
        assertThrows(
            OffsetOutOfRangeException.class,
            () -> shard.prepareLookup(request, SnapshotRegistry.LATEST_EPOCH, 8)
        );
    }

    @Test
    void testPrepareLookupBuildsSelfContainedScanPlanAfterCacheMiss() {
        LogContext logContext = new LogContext();
        MockTime time = new MockTime();
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(logContext);
        GlobalSequenceCoordinatorConfig config = new GlobalSequenceCoordinatorConfig(new AbstractConfig(
            GlobalSequenceCoordinatorConfig.CONFIG_DEF,
            Map.of(GlobalSequenceCoordinatorConfig.MAX_LOOKUP_INDEX_ENTRIES_CONFIG, 2)
        ));
        GlobalSequenceCoordinatorShard boundedShard = new GlobalSequenceCoordinatorShard(
            logContext,
            new GlobalSequenceStateRegistry(snapshotRegistry, 1, 2),
            new GlobalSequenceIndexCache(1),
            time,
            new MockCoordinatorTimer<>(time),
            config,
            mock(CoordinatorMetrics.class),
            mock(CoordinatorMetricsShard.class)
        );
        GlobalSequenceIndexRecord first = new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 1, 0, 20L);
        GlobalSequenceIndexRecord second = new GlobalSequenceIndexRecord(TOPIC_ID, 1L, 1, 1, 30L);
        boundedShard.replay(
            4L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            coordinatorRecord(first)
        );
        boundedShard.replay(
            5L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            coordinatorRecord(second)
        );
        snapshotRegistry.idempotentCreateSnapshot(6L);

        GlobalSequenceLookupRequest request = new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 2L, 10);
        assertEquals(
            GlobalSequenceIndexLookupPreparation.scan(new GlobalSequenceIndexScanPlan(
                TOPIC_ID,
                0L,
                2L,
                4L,
                6L,
                7,
                2
            )),
            boundedShard.prepareLookup(request, 6L, 7)
        );
    }

    @Test
    void testCommittedReplayMovesFromOverlayToBoundedCache() {
        shard.onLoaded(MetadataImage.EMPTY);
        GlobalSequenceAppendRequest firstRequest = request(TOPIC_ID, 0, 20L, 1);
        CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> first = shard.appendIndex(firstRequest);
        replay(first.records().get(0));
        replay(first.records().get(1));

        assertEquals(1, stateRegistry.uncommittedAllocationCount());
        assertEquals(
            new GlobalSequenceAppendResult(0L, 1, true),
            shard.appendIndex(firstRequest).response()
        );

        shard.onHighWatermarkUpdated(2L);

        assertEquals(0, stateRegistry.uncommittedAllocationCount());
        assertEquals(
            new GlobalSequenceAppendResult(0L, 1, true),
            shard.appendIndex(firstRequest).response()
        );
    }

    @Test
    void testPrepareLookupChecksPageCacheBeforeSnapshotState() {
        GlobalSequenceLookupRequest request = new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 1L);
        GlobalSequenceIndexScanPlan plan = new GlobalSequenceIndexScanPlan(
            TOPIC_ID,
            0L,
            1L,
            0L,
            10L,
            7,
            1
        );
        GlobalSequenceLookupResult result = new GlobalSequenceLookupResult(List.of(
            new GlobalSequenceIndexRecord(TOPIC_ID, 0L, 1, 0, 20L)
        ));
        shard.completeLookup(plan, result, 7);

        assertEquals(
            GlobalSequenceIndexLookupPreparation.cached(result),
            shard.prepareLookup(request, 10L, 7)
        );
    }

    private void replay(CoordinatorRecord record) {
        shard.replay(
            0L,
            RecordBatch.NO_PRODUCER_ID,
            RecordBatch.NO_PRODUCER_EPOCH,
            record
        );
    }

    private static GlobalSequenceAppendRequest request(
        Uuid topicId,
        int partitionIndex,
        long partitionBaseOffset,
        int recordCount
    ) {
        return new GlobalSequenceAppendRequest(
            topicId,
            partitionIndex,
            partitionBaseOffset,
            recordCount
        );
    }

    private static CoordinatorRecord coordinatorRecord(GlobalSequenceIndexRecord indexRecord) {
        return CoordinatorRecord.record(
            new GlobalSequenceIndexLogKey()
                .setTopicId(indexRecord.topicId())
                .setGlobalOffset(indexRecord.globalBaseOffset()),
            new ApiMessageAndVersion(
                new GlobalSequenceIndexLogValue()
                    .setRecordsCount(indexRecord.recordCount())
                    .setPartitionIndex(indexRecord.partitionIndex())
                    .setPartitionOffset(indexRecord.partitionBaseOffset()),
                (short) 0
            )
        );
    }

    private static CoordinatorRecord topicMetadataRecord(Uuid topicId, long nextGlobalOffset) {
        return CoordinatorRecord.record(
            new GlobalSequenceTopicMetadataKey().setTopicId(topicId),
            new ApiMessageAndVersion(
                new GlobalSequenceTopicMetadataValue().setNextGlobalOffset(nextGlobalOffset),
                (short) 0
            )
        );
    }

    private static MetadataImage metadataImage(Uuid... topicIds) {
        MetadataImage image = mock(MetadataImage.class);
        TopicsImage topicsImage = mock(TopicsImage.class);
        when(image.topics()).thenReturn(topicsImage);
        for (Uuid topicId : topicIds) {
            when(topicsImage.getTopic(topicId)).thenReturn(mock(TopicImage.class));
        }
        return image;
    }
}
