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
import org.apache.kafka.common.errors.OffsetOutOfRangeException;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.timeline.SnapshotRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSequenceStateRegistryTest {
    private static final Uuid TOPIC_ID = Uuid.randomUuid();
    private static final Uuid OTHER_TOPIC_ID = Uuid.randomUuid();

    @Test
    void testPrepareAppendDoesNotMutateState() {
        GlobalSequenceStateRegistry registry = newRegistry();
        GlobalSequenceAppendRequest request = request(TOPIC_ID, 1, 20L, 3);
        GlobalSequenceIndexRecord expected = record(TOPIC_ID, 0L, 3, 1, 20L);

        assertEquals(
            new GlobalSequenceStateRegistry.PreparedAppend(expected, false),
            registry.prepareAppend(request)
        );
        assertFalse(registry.contains(TOPIC_ID));
        assertEquals(
            new GlobalSequenceStateRegistry.PreparedAppend(expected, false),
            registry.prepareAppend(request)
        );
    }

    @Test
    void testReplayAdvancesWatermarkAndDeduplicatesUncommittedBatch() {
        GlobalSequenceStateRegistry registry = newRegistry();
        GlobalSequenceAppendRequest request = request(TOPIC_ID, 1, 20L, 3);
        GlobalSequenceIndexRecord indexRecord = record(TOPIC_ID, 0L, 3, 1, 20L);

        assertTrue(registry.replay(indexRecord, 10L));
        assertFalse(registry.replay(indexRecord, 10L));
        assertEquals(3L, registry.getState(TOPIC_ID).nextGlobalOffset());
        assertEquals(1, registry.uncommittedAllocationCount());
        assertEquals(
            new GlobalSequenceStateRegistry.PreparedAppend(indexRecord, true),
            registry.prepareAppend(request)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.prepareAppend(request(TOPIC_ID, 1, 20L, 4))
        );
    }

    @Test
    void testCommittedAllocationsLeaveOnlyBoundedConsumers() {
        GlobalSequenceStateRegistry registry = new GlobalSequenceStateRegistry(
            new SnapshotRegistry(new LogContext()),
            1,
            2
        );
        GlobalSequenceIndexRecord first = record(TOPIC_ID, 0L, 1, 0, 10L);
        GlobalSequenceIndexRecord second = record(TOPIC_ID, 1L, 1, 0, 11L);
        registry.replay(first, 10L);
        registry.replay(second, 11L);

        assertEquals(0, registry.checkpointCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(List.of(first), registry.promoteCommittedAllocations(11L));
        assertEquals(1, registry.checkpointCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(1, registry.uncommittedAllocationCount());
        assertEquals(List.of(second), registry.promoteCommittedAllocations(12L));
        assertEquals(2, registry.checkpointCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(0, registry.uncommittedAllocationCount());

        GlobalSequenceStateRegistry.PreparedAppend retry = registry.prepareAppend(
            request(TOPIC_ID, 0, 10L, 1)
        );
        assertFalse(retry.duplicate());
        assertEquals(2L, retry.indexRecord().globalBaseOffset());
    }

    @Test
    void testLoadingReplayDoesNotRetainFullAllocationHistory() {
        GlobalSequenceStateRegistry registry = new GlobalSequenceStateRegistry(
            new SnapshotRegistry(new LogContext()),
            4,
            2
        );
        int allocations = 10_000;
        for (int ordinal = 0; ordinal < allocations; ordinal++) {
            registry.replay(
                record(TOPIC_ID, ordinal, 1, ordinal % 4, ordinal / 4L),
                ordinal,
                false
            );
        }

        assertEquals(0, registry.uncommittedAllocationCount());
        assertEquals(allocations, registry.getState(TOPIC_ID).nextGlobalOffset());
        assertTrue(registry.checkpointCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH) < 64);
        for (int partitionIndex = 0; partitionIndex < 4; partitionIndex++) {
            assertTrue(
                registry.physicalCheckpointCount(
                    TOPIC_ID,
                    partitionIndex,
                    SnapshotRegistry.LATEST_EPOCH
                ) < 64
            );
            assertTrue(registry.isNewPhysicalBatch(
                request(TOPIC_ID, partitionIndex, allocations, 1),
                SnapshotRegistry.LATEST_EPOCH
            ));
        }
    }

    @Test
    void testGlobalLookupUsesSparseCheckpointFloor() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        GlobalSequenceStateRegistry registry = new GlobalSequenceStateRegistry(snapshotRegistry, 2, 2);
        registry.replay(record(TOPIC_ID, 0L, 3, 0, 10L), 100L, false);
        registry.replay(record(TOPIC_ID, 3L, 2, 1, 20L), 101L, false);
        registry.replay(record(TOPIC_ID, 5L, 4, 0, 13L), 102L, false);
        snapshotRegistry.idempotentCreateSnapshot(103L);

        assertEquals(
            100L,
            registry.scanStartIndexLogOffset(
                new GlobalSequenceLookupRequest(TOPIC_ID, 2L, 5L),
                103L
            )
        );
        assertEquals(
            102L,
            registry.scanStartIndexLogOffset(
                new GlobalSequenceLookupRequest(TOPIC_ID, 5L, 9L),
                103L
            )
        );
        assertThrows(
            OffsetOutOfRangeException.class,
            () -> registry.scanStartIndexLogOffset(
                new GlobalSequenceLookupRequest(OTHER_TOPIC_ID, 0L, 1L),
                103L
            )
        );
    }

    @Test
    void testTracksLatestPhysicalOffsetsAndCheckpointsPerPartition() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        GlobalSequenceStateRegistry registry = new GlobalSequenceStateRegistry(snapshotRegistry, 1, 2);
        registry.replay(record(TOPIC_ID, 0L, 1, 0, 10L), 100L, false);
        registry.replay(record(TOPIC_ID, 1L, 1, 1, 50L), 101L, false);
        registry.replay(record(TOPIC_ID, 2L, 1, 0, 11L), 102L, false);
        snapshotRegistry.idempotentCreateSnapshot(103L);

        assertTrue(registry.isNewPhysicalBatch(request(TOPIC_ID, 0, 12L, 1), 103L));
        assertFalse(registry.isNewPhysicalBatch(request(TOPIC_ID, 0, 10L, 1), 103L));
        assertFalse(registry.isNewPhysicalBatch(request(TOPIC_ID, 1, 49L, 1), 103L));
        assertTrue(registry.isNewPhysicalBatch(request(OTHER_TOPIC_ID, 0, 0L, 1), 103L));
        assertEquals(
            100L,
            registry.physicalScanStartIndexLogOffset(request(TOPIC_ID, 0, 10L, 1), 103L)
        );
        assertEquals(2, registry.physicalCheckpointCount(TOPIC_ID, 0, 103L));
        assertEquals(1, registry.physicalCheckpointCount(TOPIC_ID, 1, 103L));
    }

    @Test
    void testTopicsHaveIndependentWatermarksAndPhysicalState() {
        GlobalSequenceStateRegistry registry = newRegistry();
        registry.replay(record(TOPIC_ID, 0L, 3, 0, 10L), 10L, false);
        registry.replay(record(OTHER_TOPIC_ID, 0L, 2, 0, 50L), 11L, false);

        assertEquals(3L, registry.getState(TOPIC_ID).nextGlobalOffset());
        assertEquals(2L, registry.getState(OTHER_TOPIC_ID).nextGlobalOffset());
        assertFalse(registry.isNewPhysicalBatch(request(TOPIC_ID, 0, 10L, 3), 12L));
        assertTrue(registry.isNewPhysicalBatch(request(TOPIC_ID, 0, 11L, 1), 12L));
        assertFalse(registry.isNewPhysicalBatch(request(OTHER_TOPIC_ID, 0, 50L, 2), 12L));
    }

    @Test
    void testDurableMetadataRestoresNextOffsetAfterAllocationCompaction() {
        GlobalSequenceStateRegistry registry = newRegistry();

        registry.replayTopicMetadata(TOPIC_ID, 100L);

        assertEquals(100L, registry.getState(TOPIC_ID).nextGlobalOffset());
        assertEquals(Map.of(), registry.topicsMissingDurableMetadata());
        assertEquals(
            100L,
            registry.prepareAppend(request(TOPIC_ID, 0, 20L, 3)).indexRecord().globalBaseOffset()
        );
    }

    @Test
    void testLegacyAllocationIsReportedForMetadataBootstrap() {
        GlobalSequenceStateRegistry registry = newRegistry();
        registry.replay(record(TOPIC_ID, 7L, 3, 0, 20L), 10L, false);

        assertEquals(Map.of(TOPIC_ID, 10L), registry.topicsMissingDurableMetadata());

        registry.replayTopicMetadata(TOPIC_ID, 10L);
        assertEquals(Map.of(), registry.topicsMissingDurableMetadata());
        assertThrows(
            IllegalStateException.class,
            () -> registry.replayTopicMetadata(TOPIC_ID, 9L)
        );
    }

    @Test
    void testUncommittedDurableMetadataIsRolledBackWithSnapshot() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        GlobalSequenceStateRegistry registry = new GlobalSequenceStateRegistry(snapshotRegistry);
        registry.replay(record(TOPIC_ID, 7L, 3, 0, 20L), 10L, false);
        snapshotRegistry.idempotentCreateSnapshot(11L);

        registry.replayTopicMetadata(TOPIC_ID, 10L);
        assertEquals(Map.of(), registry.topicsMissingDurableMetadata());

        snapshotRegistry.revertToSnapshot(11L);
        assertEquals(Map.of(TOPIC_ID, 10L), registry.topicsMissingDurableMetadata());
        assertEquals(10L, registry.getState(TOPIC_ID).nextGlobalOffset());
    }

    @Test
    void testTombstoneRemovesOverlayWithoutRewindingWatermark() {
        GlobalSequenceStateRegistry registry = newRegistry();
        GlobalSequenceIndexRecord indexRecord = record(TOPIC_ID, 0L, 3, 1, 20L);
        registry.replay(indexRecord, 10L);

        assertTrue(registry.replayTombstone(TOPIC_ID, 0L));
        assertFalse(registry.replayTombstone(TOPIC_ID, 0L));
        assertEquals(0, registry.uncommittedAllocationCount());
        assertEquals(3L, registry.getState(TOPIC_ID).nextGlobalOffset());

        GlobalSequenceStateRegistry.PreparedAppend replacement = registry.prepareAppend(
            request(TOPIC_ID, 1, 20L, 3)
        );
        assertFalse(replacement.duplicate());
        assertEquals(3L, replacement.indexRecord().globalBaseOffset());
        assertFalse(registry.replayTombstone(OTHER_TOPIC_ID, 100L));
    }

    @Test
    void testReplayRejectsConflictsAndOverlaps() {
        GlobalSequenceStateRegistry registry = newRegistry();
        GlobalSequenceIndexRecord existing = record(TOPIC_ID, 5L, 5, 0, 10L);
        registry.replay(existing, 10L);

        assertThrows(
            IllegalStateException.class,
            () -> registry.replay(record(TOPIC_ID, 10L, 1, 0, 10L), 11L)
        );
        assertThrows(
            IllegalStateException.class,
            () -> registry.replay(record(TOPIC_ID, 5L, 2, 1, 20L), 11L)
        );
        assertThrows(
            IllegalStateException.class,
            () -> registry.replay(record(TOPIC_ID, 9L, 2, 1, 20L), 11L)
        );
        assertEquals(10L, registry.getState(TOPIC_ID).nextGlobalOffset());
    }

    @Test
    void testSnapshotRollbackRestoresWatermarksCheckpointsAndOverlay() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        GlobalSequenceStateRegistry registry = new GlobalSequenceStateRegistry(snapshotRegistry, 1, 2);
        GlobalSequenceIndexRecord first = record(TOPIC_ID, 0L, 2, 0, 10L);
        registry.replay(first, 0L, false);
        snapshotRegistry.idempotentCreateSnapshot(1L);

        registry.replay(record(TOPIC_ID, 2L, 3, 1, 20L), 1L);
        registry.replay(record(OTHER_TOPIC_ID, 0L, 4, 0, 30L), 2L);
        assertEquals(2, registry.uncommittedAllocationCount());

        assertEquals(2, registry.rollbackUncommittedAllocations(1L));
        snapshotRegistry.revertToSnapshot(1L);

        assertEquals(2L, registry.getState(TOPIC_ID).nextGlobalOffset());
        assertEquals(0, registry.uncommittedAllocationCount());
        assertFalse(registry.prepareAppend(request(TOPIC_ID, 0, 10L, 2)).duplicate());
        assertTrue(registry.isNewPhysicalBatch(request(TOPIC_ID, 1, 20L, 3), 1L));
        assertFalse(registry.contains(OTHER_TOPIC_ID));
        assertEquals(1, registry.checkpointCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(1, registry.physicalCheckpointCount(TOPIC_ID, 0, SnapshotRegistry.LATEST_EPOCH));
    }

    @Test
    void testExhaustionStillAllowsUncommittedRetry() {
        GlobalSequenceStateRegistry registry = newRegistry();
        GlobalSequenceIndexRecord finalAllocation = record(
            TOPIC_ID,
            Long.MAX_VALUE - 1,
            1,
            0,
            10L
        );
        registry.replay(finalAllocation, 10L);

        assertEquals(
            new GlobalSequenceStateRegistry.PreparedAppend(finalAllocation, true),
            registry.prepareAppend(request(TOPIC_ID, 0, 10L, 1))
        );
        assertThrows(
            ArithmeticException.class,
            () -> registry.prepareAppend(request(TOPIC_ID, 0, 11L, 1))
        );
        assertEquals(Long.MAX_VALUE, registry.getState(TOPIC_ID).nextGlobalOffset());
    }

    @Test
    void testRejectsNegativeIndexLogOffset() {
        GlobalSequenceStateRegistry registry = newRegistry();

        assertThrows(
            IllegalArgumentException.class,
            () -> registry.replay(record(TOPIC_ID, 0L, 1, 0, 10L), -1L)
        );
        assertFalse(registry.contains(TOPIC_ID));
    }

    private static GlobalSequenceStateRegistry newRegistry() {
        return new GlobalSequenceStateRegistry(new SnapshotRegistry(new LogContext()));
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

    private static GlobalSequenceIndexRecord record(
        Uuid topicId,
        long globalBaseOffset,
        int recordCount,
        int partitionIndex,
        long partitionBaseOffset
    ) {
        return new GlobalSequenceIndexRecord(
            topicId,
            globalBaseOffset,
            recordCount,
            partitionIndex,
            partitionBaseOffset
        );
    }
}
