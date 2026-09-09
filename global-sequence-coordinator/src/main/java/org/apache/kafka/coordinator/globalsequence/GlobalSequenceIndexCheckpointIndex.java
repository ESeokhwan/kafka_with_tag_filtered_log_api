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
import org.apache.kafka.timeline.SnapshotRegistry;
import org.apache.kafka.timeline.TimelineHashMap;
import org.apache.kafka.timeline.TimelineLong;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot-aware leveled sparse checkpoints for global sequence index log positions.
 *
 * <p>The newest level retains one checkpoint per {@code checkpointInterval} allocations. Once a
 * checkpoint gets older than {@code levelFactor} intervals, the next level retains one out of each
 * {@code levelFactor} checkpoints. This repeats geometrically, keeping old data addressable while
 * bounding retained checkpoints to O(levelFactor * log(allocationCount)).</p>
 */
public class GlobalSequenceIndexCheckpointIndex {
    private final SnapshotRegistry snapshotRegistry;
    private final int checkpointInterval;
    private final int levelFactor;
    private final TimelineHashMap<Uuid, TopicCheckpoints> checkpointsByTopic;

    public GlobalSequenceIndexCheckpointIndex(
        SnapshotRegistry snapshotRegistry,
        int checkpointInterval,
        int levelFactor
    ) {
        this.snapshotRegistry = Objects.requireNonNull(snapshotRegistry, "snapshotRegistry");
        if (checkpointInterval <= 0) {
            throw new IllegalArgumentException("checkpointInterval must be positive");
        }
        if (levelFactor < 2) {
            throw new IllegalArgumentException("levelFactor must be at least 2");
        }
        this.checkpointInterval = checkpointInterval;
        this.levelFactor = levelFactor;
        this.checkpointsByTopic = new TimelineHashMap<>(snapshotRegistry, 0);
    }

    public boolean replayAllocation(Uuid topicId, long globalBaseOffset, long indexLogOffset) {
        validateTopicId(topicId);
        if (globalBaseOffset < 0) {
            throw new IllegalArgumentException("globalBaseOffset must not be negative");
        }
        if (indexLogOffset < 0) {
            throw new IllegalArgumentException("indexLogOffset must not be negative");
        }

        TopicCheckpoints topicCheckpoints = checkpointsByTopic.get(topicId);
        if (topicCheckpoints == null) {
            topicCheckpoints = new TopicCheckpoints(snapshotRegistry);
            checkpointsByTopic.put(topicId, topicCheckpoints);
        }
        return topicCheckpoints.replayAllocation(globalBaseOffset, indexLogOffset);
    }

    public Optional<GlobalSequenceIndexCheckpoint> floor(
        Uuid topicId,
        long globalOffset,
        long epoch
    ) {
        validateTopicId(topicId);
        if (globalOffset < 0) {
            throw new IllegalArgumentException("globalOffset must not be negative");
        }
        TopicCheckpoints topicCheckpoints = checkpointsByTopic.get(topicId, epoch);
        if (topicCheckpoints == null) {
            return Optional.empty();
        }
        return topicCheckpoints.floor(globalOffset, epoch);
    }

    public long allocationCount(Uuid topicId, long epoch) {
        validateTopicId(topicId);
        TopicCheckpoints topicCheckpoints = checkpointsByTopic.get(topicId, epoch);
        return topicCheckpoints == null ? 0L : topicCheckpoints.allocationCount.get(epoch);
    }

    public int numCheckpoints(Uuid topicId, long epoch) {
        validateTopicId(topicId);
        TopicCheckpoints topicCheckpoints = checkpointsByTopic.get(topicId, epoch);
        return topicCheckpoints == null ? 0 : topicCheckpoints.byOrdinal.size(epoch);
    }

    List<GlobalSequenceIndexCheckpoint> checkpoints(Uuid topicId, long epoch) {
        validateTopicId(topicId);
        TopicCheckpoints topicCheckpoints = checkpointsByTopic.get(topicId, epoch);
        if (topicCheckpoints == null) {
            return List.of();
        }
        List<GlobalSequenceIndexCheckpoint> result = new ArrayList<>(topicCheckpoints.byOrdinal.values(epoch));
        result.sort(Comparator.comparingLong(GlobalSequenceIndexCheckpoint::allocationOrdinal));
        return result;
    }

    private static void validateTopicId(Uuid topicId) {
        Objects.requireNonNull(topicId, "topicId");
        if (Uuid.ZERO_UUID.equals(topicId)) {
            throw new IllegalArgumentException("topicId must not be ZERO_UUID");
        }
    }

    private final class TopicCheckpoints {
        private final TimelineHashMap<Long, GlobalSequenceIndexCheckpoint> byOrdinal;
        private final TimelineLong allocationCount;

        private TopicCheckpoints(SnapshotRegistry snapshotRegistry) {
            this.byOrdinal = new TimelineHashMap<>(snapshotRegistry, 0);
            this.allocationCount = new TimelineLong(snapshotRegistry);
        }

        private boolean replayAllocation(long globalBaseOffset, long indexLogOffset) {
            long ordinal = allocationCount.get();
            allocationCount.set(Math.addExact(ordinal, 1L));
            if (ordinal % checkpointInterval != 0) {
                return false;
            }

            byOrdinal.put(
                ordinal,
                new GlobalSequenceIndexCheckpoint(ordinal, globalBaseOffset, indexLogOffset)
            );
            prune(ordinal);
            return true;
        }

        private Optional<GlobalSequenceIndexCheckpoint> floor(long globalOffset, long epoch) {
            return byOrdinal.values(epoch).stream()
                .filter(checkpoint -> checkpoint.globalBaseOffset() <= globalOffset)
                .max(Comparator.comparingLong(GlobalSequenceIndexCheckpoint::globalBaseOffset));
        }

        private void prune(long newestOrdinal) {
            Collection<Long> ordinals = new ArrayList<>(byOrdinal.keySet());
            for (long ordinal : ordinals) {
                long requiredInterval = requiredInterval(newestOrdinal - ordinal);
                if (ordinal % requiredInterval != 0) {
                    byOrdinal.remove(ordinal);
                }
            }
        }

        private long requiredInterval(long age) {
            long interval = checkpointInterval;
            long levelWidth = saturatedMultiply(interval, levelFactor);
            while (age >= levelWidth && interval < Long.MAX_VALUE) {
                interval = saturatedMultiply(interval, levelFactor);
                levelWidth = saturatedMultiply(interval, levelFactor);
            }
            return interval;
        }

        private long saturatedMultiply(long value, long multiplier) {
            if (value > Long.MAX_VALUE / multiplier) {
                return Long.MAX_VALUE;
            }
            return value * multiplier;
        }
    }
}
