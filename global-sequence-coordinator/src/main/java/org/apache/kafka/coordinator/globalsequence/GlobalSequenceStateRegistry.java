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
import org.apache.kafka.timeline.SnapshotRegistry;
import org.apache.kafka.timeline.TimelineHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The bounded replay-driven state owned by one global sequence coordinator shard.
 */
public class GlobalSequenceStateRegistry {
    private final SnapshotRegistry snapshotRegistry;
    private final TimelineHashMap<Uuid, GlobalSequenceState> stateMap;
    private final GlobalSequenceIndexCheckpointIndex checkpointIndex;
    private final GlobalSequencePhysicalCheckpointIndex physicalCheckpointIndex;

    public GlobalSequenceStateRegistry(SnapshotRegistry snapshotRegistry) {
        this(
            snapshotRegistry,
            GlobalSequenceCoordinatorConfig.INDEX_CHECKPOINT_INTERVAL_DEFAULT,
            GlobalSequenceCoordinatorConfig.INDEX_CHECKPOINT_LEVEL_FACTOR_DEFAULT
        );
    }

    public GlobalSequenceStateRegistry(
        SnapshotRegistry snapshotRegistry,
        int checkpointInterval,
        int checkpointLevelFactor
    ) {
        this.snapshotRegistry = Objects.requireNonNull(snapshotRegistry, "snapshotRegistry");
        this.stateMap = new TimelineHashMap<>(snapshotRegistry, 0);
        this.checkpointIndex = new GlobalSequenceIndexCheckpointIndex(
            snapshotRegistry,
            checkpointInterval,
            checkpointLevelFactor
        );
        this.physicalCheckpointIndex = new GlobalSequencePhysicalCheckpointIndex(
            snapshotRegistry,
            checkpointInterval,
            checkpointLevelFactor
        );
    }

    public GlobalSequenceState getState(Uuid topicId) {
        return stateMap.get(topicId);
    }

    public boolean contains(Uuid topicId) {
        return stateMap.containsKey(topicId);
    }

    private void createNewTopicState(Uuid topicId) {
        validateTopicId(topicId);
        if (!stateMap.containsKey(topicId)) {
            stateMap.put(topicId, new GlobalSequenceState(snapshotRegistry));
        }
    }

    PreparedAppend prepareAppend(GlobalSequenceAppendRequest request) {
        Objects.requireNonNull(request, "request");
        GlobalSequenceState state = stateMap.get(request.topicId());
        if (state == null) {
            return new PreparedAppend(new GlobalSequenceIndexRecord(
                request.topicId(),
                0L,
                request.recordCount(),
                request.partitionIndex(),
                request.partitionBaseOffset()
            ), false);
        }
        return state.prepareAppend(request);
    }

    boolean replay(GlobalSequenceIndexRecord indexRecord) {
        return replay(indexRecord, indexRecord.globalBaseOffset(), true);
    }

    boolean replay(GlobalSequenceIndexRecord indexRecord, long indexLogOffset) {
        return replay(indexRecord, indexLogOffset, true);
    }

    boolean replay(
        GlobalSequenceIndexRecord indexRecord,
        long indexLogOffset,
        boolean retainInUncommittedOverlay
    ) {
        Objects.requireNonNull(indexRecord, "indexRecord");
        if (indexLogOffset < 0) {
            throw new IllegalArgumentException("indexLogOffset must not be negative");
        }
        createNewTopicState(indexRecord.topicId());
        boolean added = stateMap.get(indexRecord.topicId()).replay(
            indexRecord,
            indexLogOffset,
            retainInUncommittedOverlay
        );
        if (!added) {
            return false;
        }

        if (!retainInUncommittedOverlay) {
            replayCommittedAllocation(indexRecord, indexLogOffset);
        }
        return true;
    }

    boolean replayTombstone(Uuid topicId, long globalBaseOffset) {
        validateTopicId(topicId);
        if (globalBaseOffset < 0) {
            throw new IllegalArgumentException("globalBaseOffset must not be negative");
        }

        GlobalSequenceState state = stateMap.get(topicId);
        return state != null && state.replayTombstone(globalBaseOffset);
    }

    List<GlobalSequenceIndexRecord> promoteCommittedAllocations(long indexLogHighWatermark) {
        List<OverlayAllocation> committedAllocations = new ArrayList<>();
        for (GlobalSequenceState state : stateMap.values()) {
            state.removeCommittedAllocations(indexLogHighWatermark, committedAllocations);
        }
        committedAllocations.sort(Comparator.comparingLong(OverlayAllocation::indexLogOffset));

        List<GlobalSequenceIndexRecord> committed = new ArrayList<>(committedAllocations.size());
        for (OverlayAllocation allocation : committedAllocations) {
            replayCommittedAllocation(allocation.indexRecord(), allocation.indexLogOffset());
            committed.add(allocation.indexRecord());
        }
        return committed;
    }

    int rollbackUncommittedAllocations(long indexLogEndOffset) {
        int rolledBack = 0;
        for (GlobalSequenceState state : stateMap.values()) {
            rolledBack = Math.addExact(
                rolledBack,
                state.rollbackUncommittedAllocations(indexLogEndOffset)
            );
        }
        return rolledBack;
    }

    int clearUncommittedAllocations() {
        int cleared = 0;
        for (GlobalSequenceState state : stateMap.values()) {
            cleared = Math.addExact(cleared, state.clearUncommittedAllocations());
        }
        return cleared;
    }

    long scanStartIndexLogOffset(
        GlobalSequenceLookupRequest request,
        long indexLogHighWatermark
    ) {
        Objects.requireNonNull(request, "request");
        GlobalSequenceIndexCheckpoint checkpoint = checkpointIndex.floor(
            request.topicId(),
            request.globalStartOffset(),
            indexLogHighWatermark
        ).orElseThrow(() -> outOfRange(request, request.globalStartOffset()));
        if (checkpoint.indexLogOffset() >= indexLogHighWatermark) {
            throw outOfRange(request, request.globalStartOffset());
        }
        return checkpoint.indexLogOffset();
    }

    boolean isNewPhysicalBatch(GlobalSequenceAppendRequest request, long indexLogHighWatermark) {
        Objects.requireNonNull(request, "request");
        GlobalSequenceState state = stateMap.get(request.topicId(), indexLogHighWatermark);
        if (state == null) {
            return true;
        }
        OptionalLong lastPhysicalBaseOffset = state.lastPhysicalBaseOffset(
            request.partitionIndex(),
            indexLogHighWatermark
        );
        return lastPhysicalBaseOffset.isEmpty() ||
            request.partitionBaseOffset() > lastPhysicalBaseOffset.getAsLong();
    }

    long physicalScanStartIndexLogOffset(
        GlobalSequenceAppendRequest request,
        long indexLogHighWatermark
    ) {
        Objects.requireNonNull(request, "request");
        return physicalCheckpointIndex.floor(
            request.topicId(),
            request.partitionIndex(),
            request.partitionBaseOffset(),
            indexLogHighWatermark
        ).map(GlobalSequencePhysicalCheckpoint::indexLogOffset).orElse(0L);
    }

    int uncommittedAllocationCount() {
        int count = 0;
        for (GlobalSequenceState state : stateMap.values()) {
            count = Math.addExact(count, state.uncommittedAllocationCount());
        }
        return count;
    }

    int checkpointCount(Uuid topicId, long epoch) {
        return checkpointIndex.numCheckpoints(topicId, epoch);
    }

    int physicalCheckpointCount(Uuid topicId, int partitionIndex, long epoch) {
        return physicalCheckpointIndex.numCheckpoints(topicId, partitionIndex, epoch);
    }

    private void replayCommittedAllocation(
        GlobalSequenceIndexRecord indexRecord,
        long indexLogOffset
    ) {
        checkpointIndex.replayAllocation(
            indexRecord.topicId(),
            indexRecord.globalBaseOffset(),
            indexLogOffset
        );
        physicalCheckpointIndex.replayAllocation(
            indexRecord.topicId(),
            indexRecord.partitionIndex(),
            indexRecord.partitionBaseOffset(),
            indexLogOffset
        );
    }

    static OffsetOutOfRangeException outOfRange(
        GlobalSequenceLookupRequest request,
        long missingOffset
    ) {
        return new OffsetOutOfRangeException(
            "Global offset " + missingOffset + " is not indexed for topic ID " + request.topicId() +
                " in requested range [" + request.globalStartOffset() + ", " +
                request.globalEndOffsetExclusive() + ")"
        );
    }

    private static void validateTopicId(Uuid topicId) {
        Objects.requireNonNull(topicId, "topicId");
        if (Uuid.ZERO_UUID.equals(topicId)) {
            throw new IllegalArgumentException("topicId must not be ZERO_UUID");
        }
    }

    record PreparedAppend(GlobalSequenceIndexRecord indexRecord, boolean duplicate) { }

    private record OverlayAllocation(GlobalSequenceIndexRecord indexRecord, long indexLogOffset) { }

    public static class GlobalSequenceState {
        private final TimelineHashMap<Integer, Long> lastPhysicalBaseOffsetByPartition;
        private final Map<PhysicalBatchId, OverlayAllocation> uncommittedAllocations;
        private final GlobalOffsetSequencer offsetSequencer;

        GlobalSequenceState(SnapshotRegistry snapshotRegistry) {
            this.lastPhysicalBaseOffsetByPartition = new TimelineHashMap<>(snapshotRegistry, 0);
            this.uncommittedAllocations = new HashMap<>();
            this.offsetSequencer = new BasicGlobalOffsetSequencer(snapshotRegistry);
        }

        long nextGlobalOffset() {
            return offsetSequencer.nextOffset();
        }

        PreparedAppend prepareAppend(GlobalSequenceAppendRequest request) {
            OverlayAllocation existing = uncommittedAllocations.get(request.physicalBatchId());
            if (existing != null) {
                return new PreparedAppend(validateExisting(existing.indexRecord(), request), true);
            }

            return new PreparedAppend(new GlobalSequenceIndexRecord(
                request.topicId(),
                offsetSequencer.nextOffset(),
                request.recordCount(),
                request.partitionIndex(),
                request.partitionBaseOffset()
            ), false);
        }

        boolean replay(
            GlobalSequenceIndexRecord indexRecord,
            long indexLogOffset,
            boolean retainInUncommittedOverlay
        ) {
            PhysicalBatchId physicalBatchId = new PhysicalBatchId(
                indexRecord.topicId(),
                indexRecord.partitionIndex(),
                indexRecord.partitionBaseOffset()
            );
            OverlayAllocation existing = uncommittedAllocations.get(physicalBatchId);
            if (existing != null) {
                if (!existing.indexRecord().equals(indexRecord)) {
                    throw new IllegalStateException(
                        "Conflicting allocation for physical batch " + physicalBatchId + ": existing=" +
                            existing.indexRecord() + ", replayed=" + indexRecord
                    );
                }
                return false;
            }

            if (indexRecord.globalBaseOffset() < offsetSequencer.nextOffset()) {
                throw new IllegalStateException(
                    "Overlapping or out-of-order global sequence allocation: nextGlobalOffset=" +
                        offsetSequencer.nextOffset() + ", replayed=" + indexRecord
                );
            }
            offsetSequencer.replayAllocation(indexRecord.globalBaseOffset(), indexRecord.recordCount());

            Long lastPhysicalBaseOffset = lastPhysicalBaseOffsetByPartition.get(indexRecord.partitionIndex());
            if (lastPhysicalBaseOffset == null || indexRecord.partitionBaseOffset() > lastPhysicalBaseOffset) {
                lastPhysicalBaseOffsetByPartition.put(
                    indexRecord.partitionIndex(),
                    indexRecord.partitionBaseOffset()
                );
            }
            if (retainInUncommittedOverlay) {
                uncommittedAllocations.put(
                    physicalBatchId,
                    new OverlayAllocation(indexRecord, indexLogOffset)
                );
            }
            return true;
        }

        private GlobalSequenceIndexRecord validateExisting(
            GlobalSequenceIndexRecord existing,
            GlobalSequenceAppendRequest request
        ) {
            if (existing.recordCount() != request.recordCount()) {
                throw new IllegalArgumentException(
                    "Physical batch " + request.physicalBatchId() + " is already allocated with recordCount=" +
                        existing.recordCount() + ", but the retry has recordCount=" + request.recordCount()
                );
            }
            return existing;
        }

        OptionalLong lastPhysicalBaseOffset(int partitionIndex, long epoch) {
            Long offset = lastPhysicalBaseOffsetByPartition.get(partitionIndex, epoch);
            return offset == null ? OptionalLong.empty() : OptionalLong.of(offset);
        }

        boolean replayTombstone(long globalBaseOffset) {
            Optional<PhysicalBatchId> matchingBatch = uncommittedAllocations.entrySet().stream()
                .filter(entry -> entry.getValue().indexRecord().globalBaseOffset() == globalBaseOffset)
                .map(Map.Entry::getKey)
                .findFirst();
            return matchingBatch.map(uncommittedAllocations::remove).isPresent();
        }

        void removeCommittedAllocations(
            long indexLogHighWatermark,
            List<OverlayAllocation> committed
        ) {
            List<PhysicalBatchId> committedBatches = uncommittedAllocations.entrySet().stream()
                .filter(entry -> entry.getValue().indexLogOffset() < indexLogHighWatermark)
                .map(Map.Entry::getKey)
                .toList();
            for (PhysicalBatchId physicalBatchId : committedBatches) {
                OverlayAllocation allocation = uncommittedAllocations.remove(physicalBatchId);
                if (allocation != null) {
                    committed.add(allocation);
                }
            }
        }

        int rollbackUncommittedAllocations(long indexLogEndOffset) {
            List<PhysicalBatchId> rolledBackBatches = uncommittedAllocations.entrySet().stream()
                .filter(entry -> entry.getValue().indexLogOffset() >= indexLogEndOffset)
                .map(Map.Entry::getKey)
                .toList();
            rolledBackBatches.forEach(uncommittedAllocations::remove);
            return rolledBackBatches.size();
        }

        int clearUncommittedAllocations() {
            int size = uncommittedAllocations.size();
            uncommittedAllocations.clear();
            return size;
        }

        int uncommittedAllocationCount() {
            return uncommittedAllocations.size();
        }
    }
}
