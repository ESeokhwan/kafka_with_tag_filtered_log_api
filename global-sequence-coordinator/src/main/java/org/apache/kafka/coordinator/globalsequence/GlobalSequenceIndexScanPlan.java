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

import java.util.Objects;

/**
 * An immutable, fenced plan for resolving a global offset range from the index log.
 */
public record GlobalSequenceIndexScanPlan(
    Uuid topicId,
    long globalStartOffset,
    long globalEndOffsetExclusive,
    long startIndexLogOffset,
    long capturedHighWatermark,
    int coordinatorLeaderEpoch,
    int maxIndexEntries
) {
    public GlobalSequenceIndexScanPlan {
        Objects.requireNonNull(topicId, "topicId");
        if (Uuid.ZERO_UUID.equals(topicId)) {
            throw new IllegalArgumentException("topicId must not be ZERO_UUID");
        }
        if (globalStartOffset < 0) {
            throw new IllegalArgumentException("globalStartOffset must not be negative");
        }
        if (globalEndOffsetExclusive <= globalStartOffset) {
            throw new IllegalArgumentException(
                "globalEndOffsetExclusive must be greater than globalStartOffset"
            );
        }
        if (startIndexLogOffset < 0) {
            throw new IllegalArgumentException("startIndexLogOffset must not be negative");
        }
        if (capturedHighWatermark <= startIndexLogOffset) {
            throw new IllegalArgumentException(
                "capturedHighWatermark must be greater than startIndexLogOffset"
            );
        }
        if (coordinatorLeaderEpoch < 0) {
            throw new IllegalArgumentException("coordinatorLeaderEpoch must not be negative");
        }
        if (maxIndexEntries <= 0) {
            throw new IllegalArgumentException("maxIndexEntries must be positive");
        }
    }

    GlobalSequenceLookupRequest request() {
        return new GlobalSequenceLookupRequest(
            topicId,
            globalStartOffset,
            globalEndOffsetExclusive,
            maxIndexEntries
        );
    }
}
