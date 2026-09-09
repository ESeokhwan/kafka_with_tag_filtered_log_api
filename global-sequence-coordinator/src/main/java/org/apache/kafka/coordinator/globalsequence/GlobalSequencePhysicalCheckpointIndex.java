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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Committed leveled sparse checkpoints for physical batch lookups in the index log.
 */
public class GlobalSequencePhysicalCheckpointIndex {
    private final int checkpointInterval;
    private final int levelFactor;
    private final Map<PhysicalPartitionId, PartitionCheckpoints> checkpointsByPartition;

    public GlobalSequencePhysicalCheckpointIndex(
        SnapshotRegistry snapshotRegistry,
        int checkpointInterval,
        int levelFactor
    ) {
        Objects.requireNonNull(snapshotRegistry, "snapshotRegistry");
        if (checkpointInterval <= 0) {
            throw new IllegalArgumentException("checkpointInterval must be positive");
        }
        if (levelFactor < 2) {
            throw new IllegalArgumentException("levelFactor must be at least 2");
        }
        this.checkpointInterval = checkpointInterval;
        this.levelFactor = levelFactor;
        this.checkpointsByPartition = new HashMap<>();
    }

    public boolean replayAllocation(
        Uuid topicId,
        int partitionIndex,
        long physicalBaseOffset,
        long indexLogOffset
    ) {
        PhysicalPartitionId partitionId = new PhysicalPartitionId(topicId, partitionIndex);
        if (physicalBaseOffset < 0) {
            throw new IllegalArgumentException("physicalBaseOffset must not be negative");
        }
        if (indexLogOffset < 0) {
            throw new IllegalArgumentException("indexLogOffset must not be negative");
        }

        PartitionCheckpoints partitionCheckpoints = checkpointsByPartition.get(partitionId);
        if (partitionCheckpoints == null) {
            partitionCheckpoints = new PartitionCheckpoints();
            checkpointsByPartition.put(partitionId, partitionCheckpoints);
        }
        return partitionCheckpoints.replayAllocation(physicalBaseOffset, indexLogOffset);
    }

    public Optional<GlobalSequencePhysicalCheckpoint> floor(
        Uuid topicId,
        int partitionIndex,
        long physicalBaseOffset,
        long epoch
    ) {
        PhysicalPartitionId partitionId = new PhysicalPartitionId(topicId, partitionIndex);
        if (physicalBaseOffset < 0) {
            throw new IllegalArgumentException("physicalBaseOffset must not be negative");
        }
        PartitionCheckpoints partitionCheckpoints = checkpointsByPartition.get(partitionId);
        if (partitionCheckpoints == null) {
            return Optional.empty();
        }
        return partitionCheckpoints.floor(physicalBaseOffset);
    }

    public int numCheckpoints(Uuid topicId, int partitionIndex, long epoch) {
        PartitionCheckpoints partitionCheckpoints = checkpointsByPartition.get(
            new PhysicalPartitionId(topicId, partitionIndex)
        );
        return partitionCheckpoints == null ? 0 : partitionCheckpoints.byOrdinal.size();
    }

    public void removeTopic(Uuid topicId) {
        Objects.requireNonNull(topicId, "topicId");
        checkpointsByPartition.keySet().removeIf(partitionId -> partitionId.topicId().equals(topicId));
    }

    List<GlobalSequencePhysicalCheckpoint> checkpoints(Uuid topicId, int partitionIndex, long epoch) {
        PartitionCheckpoints partitionCheckpoints = checkpointsByPartition.get(
            new PhysicalPartitionId(topicId, partitionIndex)
        );
        if (partitionCheckpoints == null) {
            return List.of();
        }
        List<GlobalSequencePhysicalCheckpoint> result = new ArrayList<>(
            partitionCheckpoints.byOrdinal.values()
        );
        result.sort(Comparator.comparingLong(GlobalSequencePhysicalCheckpoint::allocationOrdinal));
        return result;
    }

    private record PhysicalPartitionId(Uuid topicId, int partitionIndex) {
        private PhysicalPartitionId {
            PhysicalBatchId.validate(topicId, partitionIndex, 0L);
        }
    }

    private final class PartitionCheckpoints {
        private final Map<Long, GlobalSequencePhysicalCheckpoint> byOrdinal;
        private long allocationCount;

        private PartitionCheckpoints() {
            this.byOrdinal = new HashMap<>();
        }

        private boolean replayAllocation(long physicalBaseOffset, long indexLogOffset) {
            long ordinal = allocationCount;
            allocationCount = Math.addExact(ordinal, 1L);
            if (ordinal % checkpointInterval != 0) {
                return false;
            }

            byOrdinal.put(
                ordinal,
                new GlobalSequencePhysicalCheckpoint(ordinal, physicalBaseOffset, indexLogOffset)
            );
            prune(ordinal);
            return true;
        }

        private Optional<GlobalSequencePhysicalCheckpoint> floor(long physicalBaseOffset) {
            return byOrdinal.values().stream()
                .filter(checkpoint -> checkpoint.physicalBaseOffset() <= physicalBaseOffset)
                .max(Comparator.comparingLong(GlobalSequencePhysicalCheckpoint::physicalBaseOffset));
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
