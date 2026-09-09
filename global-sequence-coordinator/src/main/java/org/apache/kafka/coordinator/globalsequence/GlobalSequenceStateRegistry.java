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
import org.apache.kafka.timeline.TimelineLong;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The replay-driven state owned by one global sequence coordinator shard.
 */
public class GlobalSequenceStateRegistry {
    private final SnapshotRegistry snapshotRegistry;
    private final TimelineHashMap<Uuid, GlobalSequenceState> stateMap;
    private final int maxLookupIndexEntries;
    private final int maxRetainedIndexEntries;
    private final GlobalSequenceIndexCheckpointIndex checkpointIndex;
    private final TimelineHashMap<Long, RetainedAllocation> retainedAllocations;
    private final TimelineLong replaySequence;

    public GlobalSequenceStateRegistry(SnapshotRegistry snapshotRegistry) {
        this(
            snapshotRegistry,
            GlobalSequenceCoordinatorConfig.MAX_LOOKUP_INDEX_ENTRIES_DEFAULT,
            GlobalSequenceCoordinatorConfig.INDEX_CACHE_MAX_ENTRIES_DEFAULT,
            GlobalSequenceCoordinatorConfig.INDEX_CHECKPOINT_INTERVAL_DEFAULT,
            GlobalSequenceCoordinatorConfig.INDEX_CHECKPOINT_LEVEL_FACTOR_DEFAULT
        );
    }

    public GlobalSequenceStateRegistry(SnapshotRegistry snapshotRegistry, int maxLookupIndexEntries) {
        this(
            snapshotRegistry,
            maxLookupIndexEntries,
            GlobalSequenceCoordinatorConfig.INDEX_CACHE_MAX_ENTRIES_DEFAULT,
            GlobalSequenceCoordinatorConfig.INDEX_CHECKPOINT_INTERVAL_DEFAULT,
            GlobalSequenceCoordinatorConfig.INDEX_CHECKPOINT_LEVEL_FACTOR_DEFAULT
        );
    }

    public GlobalSequenceStateRegistry(
        SnapshotRegistry snapshotRegistry,
        int maxLookupIndexEntries,
        int maxRetainedIndexEntries,
        int checkpointInterval,
        int checkpointLevelFactor
    ) {
        this.snapshotRegistry = Objects.requireNonNull(snapshotRegistry, "snapshotRegistry");
        if (maxLookupIndexEntries <= 0) {
            throw new IllegalArgumentException("maxLookupIndexEntries must be positive");
        }
        if (maxRetainedIndexEntries <= 0) {
            throw new IllegalArgumentException("maxRetainedIndexEntries must be positive");
        }
        this.maxLookupIndexEntries = maxLookupIndexEntries;
        this.maxRetainedIndexEntries = maxRetainedIndexEntries;
        this.stateMap = new TimelineHashMap<>(snapshotRegistry, 0);
        this.checkpointIndex = new GlobalSequenceIndexCheckpointIndex(
            snapshotRegistry,
            checkpointInterval,
            checkpointLevelFactor
        );
        this.retainedAllocations = new TimelineHashMap<>(snapshotRegistry, 0);
        this.replaySequence = new TimelineLong(snapshotRegistry);
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
        return replay(indexRecord, indexRecord.globalBaseOffset());
    }

    boolean replay(GlobalSequenceIndexRecord indexRecord, long indexLogOffset) {
        Objects.requireNonNull(indexRecord, "indexRecord");
        if (indexLogOffset < 0) {
            throw new IllegalArgumentException("indexLogOffset must not be negative");
        }
        createNewTopicState(indexRecord.topicId());
        ReplayResult replayResult = stateMap.get(indexRecord.topicId()).replay(indexRecord);
        if (!replayResult.added()) {
            return false;
        }

        checkpointIndex.replayAllocation(
            indexRecord.topicId(),
            indexRecord.globalBaseOffset(),
            indexLogOffset
        );
        long sequence = replaySequence.get();
        retainedAllocations.put(
            sequence,
            new RetainedAllocation(indexRecord.topicId(), replayResult.allocationOrdinal(), indexRecord)
        );
        replaySequence.set(Math.addExact(sequence, 1L));
        if (sequence >= maxRetainedIndexEntries) {
            RetainedAllocation evicted = retainedAllocations.remove(sequence - maxRetainedIndexEntries);
            if (evicted == null) {
                throw new IllegalStateException("Missing retained global sequence allocation during eviction");
            }
            GlobalSequenceState state = stateMap.get(evicted.topicId());
            if (state == null) {
                throw new IllegalStateException("Missing global sequence topic state during eviction");
            }
            state.evict(evicted.allocationOrdinal(), evicted.indexRecord());
        }
        return true;
    }

    void replayTombstone(Uuid topicId, long globalBaseOffset) {
        validateTopicId(topicId);
        if (globalBaseOffset < 0) {
            throw new IllegalArgumentException("globalBaseOffset must not be negative");
        }

        GlobalSequenceState state = stateMap.get(topicId);
        if (state != null) {
            state.replayTombstone(globalBaseOffset);
        }
    }

    GlobalSequenceLookupResult lookup(GlobalSequenceLookupRequest request, long indexLogHighWatermark) {
        return lookupRetained(request, indexLogHighWatermark).orElseThrow(() ->
            outOfRange(request, request.globalStartOffset())
        );
    }

    Optional<GlobalSequenceLookupResult> lookupRetained(
        GlobalSequenceLookupRequest request,
        long indexLogHighWatermark
    ) {
        Objects.requireNonNull(request, "request");
        GlobalSequenceState state = stateMap.get(request.topicId(), indexLogHighWatermark);
        if (state == null) {
            throw outOfRange(request, request.globalStartOffset());
        }
        return state.lookupRetained(
            request,
            indexLogHighWatermark,
            Math.min(request.maxIndexEntries(), maxLookupIndexEntries)
        );
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

    int retainedAllocationCount(long epoch) {
        return retainedAllocations.size(epoch);
    }

    int checkpointCount(Uuid topicId, long epoch) {
        return checkpointIndex.numCheckpoints(topicId, epoch);
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

    private record ReplayResult(boolean added, long allocationOrdinal) { }

    private record RetainedAllocation(
        Uuid topicId,
        long allocationOrdinal,
        GlobalSequenceIndexRecord indexRecord
    ) { }

    public static class GlobalSequenceState {
        private final TimelineHashMap<Long, GlobalSequenceIndexRecord> sequenceByGlobalBaseOffset;
        // Allocations are replayed in increasing global-offset order, so their dense ordinals provide
        // a snapshot-aware binary-search index without rebuilding and sorting every lookup.
        private final TimelineHashMap<Long, GlobalSequenceIndexRecord> sequenceByAllocationOrdinal;
        private final TimelineHashMap<PhysicalBatchId, GlobalSequenceIndexRecord> sequenceByPhysicalBatch;
        private final GlobalOffsetSequencer offsetSequencer;
        private final TimelineLong allocationCount;
        private final TimelineLong firstRetainedAllocationOrdinal;

        GlobalSequenceState(SnapshotRegistry snapshotRegistry) {
            this.sequenceByGlobalBaseOffset = new TimelineHashMap<>(snapshotRegistry, 0);
            this.sequenceByAllocationOrdinal = new TimelineHashMap<>(snapshotRegistry, 0);
            this.sequenceByPhysicalBatch = new TimelineHashMap<>(snapshotRegistry, 0);
            this.offsetSequencer = new BasicGlobalOffsetSequencer(snapshotRegistry);
            this.allocationCount = new TimelineLong(snapshotRegistry);
            this.firstRetainedAllocationOrdinal = new TimelineLong(snapshotRegistry);
        }

        long nextGlobalOffset() {
            return offsetSequencer.nextOffset();
        }

        PreparedAppend prepareAppend(GlobalSequenceAppendRequest request) {
            GlobalSequenceIndexRecord existing = sequenceByPhysicalBatch.get(request.physicalBatchId());
            if (existing != null) {
                if (existing.recordCount() != request.recordCount()) {
                    throw new IllegalArgumentException(
                        "Physical batch " + request.physicalBatchId() + " is already allocated with recordCount=" +
                            existing.recordCount() + ", but the retry has recordCount=" + request.recordCount()
                    );
                }
                return new PreparedAppend(existing, true);
            }

            return new PreparedAppend(new GlobalSequenceIndexRecord(
                request.topicId(),
                offsetSequencer.nextOffset(),
                request.recordCount(),
                request.partitionIndex(),
                request.partitionBaseOffset()
            ), false);
        }

        ReplayResult replay(GlobalSequenceIndexRecord indexRecord) {
            PhysicalBatchId physicalBatchId = new PhysicalBatchId(
                indexRecord.topicId(),
                indexRecord.partitionIndex(),
                indexRecord.partitionBaseOffset()
            );
            GlobalSequenceIndexRecord byPhysicalBatch = sequenceByPhysicalBatch.get(physicalBatchId);
            GlobalSequenceIndexRecord byGlobalBaseOffset = sequenceByGlobalBaseOffset.get(
                indexRecord.globalBaseOffset()
            );

            if (byPhysicalBatch != null && !byPhysicalBatch.equals(indexRecord)) {
                throw new IllegalStateException(
                    "Conflicting allocation for physical batch " + physicalBatchId + ": existing=" +
                        byPhysicalBatch + ", replayed=" + indexRecord
                );
            }
            if (byGlobalBaseOffset != null && !byGlobalBaseOffset.equals(indexRecord)) {
                throw new IllegalStateException(
                    "Conflicting allocation at global base offset " + indexRecord.globalBaseOffset() +
                        ": existing=" + byGlobalBaseOffset + ", replayed=" + indexRecord
                );
            }
            if (byPhysicalBatch != null || byGlobalBaseOffset != null) {
                if (byPhysicalBatch == null || byGlobalBaseOffset == null) {
                    throw new IllegalStateException("Global sequence indexes are inconsistent for " + indexRecord);
                }
                return new ReplayResult(false, -1L);
            }

            validateReplayOrder(indexRecord);
            long allocationOrdinal = allocationCount.get();
            long nextAllocationCount = Math.addExact(allocationOrdinal, 1L);
            GlobalSequenceIndexRecord byAllocationOrdinal = sequenceByAllocationOrdinal.get(allocationOrdinal);
            if (byAllocationOrdinal != null) {
                throw new IllegalStateException(
                    "Conflicting allocation at ordinal " + allocationOrdinal + ": existing=" +
                        byAllocationOrdinal + ", replayed=" + indexRecord
                );
            }

            sequenceByGlobalBaseOffset.put(indexRecord.globalBaseOffset(), indexRecord);
            sequenceByPhysicalBatch.put(physicalBatchId, indexRecord);
            sequenceByAllocationOrdinal.put(allocationOrdinal, indexRecord);
            offsetSequencer.replayAllocation(indexRecord.globalBaseOffset(), indexRecord.recordCount());
            allocationCount.set(nextAllocationCount);
            return new ReplayResult(true, allocationOrdinal);
        }

        void evict(long allocationOrdinal, GlobalSequenceIndexRecord indexRecord) {
            GlobalSequenceIndexRecord ordered = sequenceByAllocationOrdinal.remove(allocationOrdinal);
            if (!indexRecord.equals(ordered)) {
                throw new IllegalStateException(
                    "Global sequence allocation eviction order is inconsistent at ordinal " + allocationOrdinal
                );
            }

            GlobalSequenceIndexRecord active = sequenceByGlobalBaseOffset.get(indexRecord.globalBaseOffset());
            if (active != null) {
                if (!indexRecord.equals(active)) {
                    throw new IllegalStateException("Global sequence offset index is inconsistent during eviction");
                }
                sequenceByGlobalBaseOffset.remove(indexRecord.globalBaseOffset());
                sequenceByPhysicalBatch.remove(new PhysicalBatchId(
                    indexRecord.topicId(),
                    indexRecord.partitionIndex(),
                    indexRecord.partitionBaseOffset()
                ));
            }
            if (firstRetainedAllocationOrdinal.get() != allocationOrdinal) {
                throw new IllegalStateException(
                    "Global sequence allocations must be evicted in topic allocation order"
                );
            }
            firstRetainedAllocationOrdinal.set(Math.addExact(allocationOrdinal, 1L));
        }

        void replayTombstone(long globalBaseOffset) {
            GlobalSequenceIndexRecord existing = sequenceByGlobalBaseOffset.get(globalBaseOffset);
            if (existing == null) {
                return;
            }

            PhysicalBatchId physicalBatchId = new PhysicalBatchId(
                existing.topicId(),
                existing.partitionIndex(),
                existing.partitionBaseOffset()
            );
            if (!existing.equals(sequenceByPhysicalBatch.get(physicalBatchId))) {
                throw new IllegalStateException("Global sequence indexes are inconsistent for " + existing);
            }

            // Tombstones define the end of the retry-safety window for this physical batch. They
            // must not be emitted until a durable per-topic watermark is added to the log schema;
            // otherwise compaction could make the in-memory next offset unrecoverable on restart.
            // The ordinal entry remains so lookups can locate and reject the deleted global range.
            sequenceByGlobalBaseOffset.remove(globalBaseOffset);
            sequenceByPhysicalBatch.remove(physicalBatchId);
        }

        Optional<GlobalSequenceLookupResult> lookupRetained(
            GlobalSequenceLookupRequest request,
            long indexLogHighWatermark,
            int maxIndexEntries
        ) {
            List<GlobalSequenceIndexRecord> matches = new ArrayList<>();
            long nextOffsetToCover = request.globalStartOffset();
            long count = allocationCount.get(indexLogHighWatermark);
            long firstRetainedOrdinal = firstRetainedAllocationOrdinal.get(indexLogHighWatermark);
            if (firstRetainedOrdinal >= count) {
                return Optional.empty();
            }
            GlobalSequenceIndexRecord firstRetained = indexRecordAt(
                firstRetainedOrdinal,
                indexLogHighWatermark
            );
            if (request.globalStartOffset() < firstRetained.globalBaseOffset()) {
                return Optional.empty();
            }
            long firstOrdinal = findFirstCandidateOrdinal(
                request.globalStartOffset(),
                firstRetainedOrdinal,
                count,
                indexLogHighWatermark
            );

            for (long ordinal = firstOrdinal; ordinal < count; ordinal++) {
                GlobalSequenceIndexRecord indexRecord = indexRecordAt(ordinal, indexLogHighWatermark);
                if (indexRecord.globalEndOffsetExclusive() <= nextOffsetToCover) {
                    continue;
                }
                if (indexRecord.globalBaseOffset() > nextOffsetToCover) {
                    throw outOfRange(request, nextOffsetToCover);
                }
                if (!isActive(indexRecord, indexLogHighWatermark)) {
                    throw outOfRange(request, nextOffsetToCover);
                }

                matches.add(indexRecord);
                nextOffsetToCover = Math.min(
                    indexRecord.globalEndOffsetExclusive(),
                    request.globalEndOffsetExclusive()
                );
                if (nextOffsetToCover == request.globalEndOffsetExclusive() ||
                    matches.size() == maxIndexEntries) {
                    return Optional.of(new GlobalSequenceLookupResult(matches));
                }
            }

            throw outOfRange(request, nextOffsetToCover);
        }

        private long findFirstCandidateOrdinal(
            long globalStartOffset,
            long firstRetainedOrdinal,
            long count,
            long indexLogHighWatermark
        ) {
            long low = firstRetainedOrdinal;
            long high = count;
            while (low < high) {
                long middle = low + ((high - low) >>> 1);
                GlobalSequenceIndexRecord indexRecord = indexRecordAt(middle, indexLogHighWatermark);
                if (indexRecord.globalBaseOffset() <= globalStartOffset) {
                    low = middle + 1L;
                } else {
                    high = middle;
                }
            }
            return low == firstRetainedOrdinal ? firstRetainedOrdinal : low - 1L;
        }

        private GlobalSequenceIndexRecord indexRecordAt(long ordinal, long indexLogHighWatermark) {
            GlobalSequenceIndexRecord indexRecord = sequenceByAllocationOrdinal.get(
                ordinal,
                indexLogHighWatermark
            );
            if (indexRecord == null) {
                throw new IllegalStateException("Missing global sequence allocation at ordinal " + ordinal);
            }
            return indexRecord;
        }

        private boolean isActive(GlobalSequenceIndexRecord indexRecord, long indexLogHighWatermark) {
            GlobalSequenceIndexRecord active = sequenceByGlobalBaseOffset.get(
                indexRecord.globalBaseOffset(),
                indexLogHighWatermark
            );
            if (active == null) {
                return false;
            }
            if (!active.equals(indexRecord)) {
                throw new IllegalStateException(
                    "Global sequence indexes are inconsistent at global base offset " +
                        indexRecord.globalBaseOffset() + ": ordered=" + indexRecord + ", active=" + active
                );
            }
            return true;
        }

        private void validateReplayOrder(GlobalSequenceIndexRecord candidate) {
            if (candidate.globalBaseOffset() < offsetSequencer.nextOffset()) {
                throw new IllegalStateException(
                    "Overlapping or out-of-order global sequence allocation: nextGlobalOffset=" +
                        offsetSequencer.nextOffset() + ", replayed=" + candidate
                );
            }
        }
    }
}
