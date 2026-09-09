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

import java.util.Objects;
import java.util.Optional;

/**
 * A committed in-memory lookup result or a bounded index-log scan range.
 */
public record GlobalSequenceIndexLookupPlan(
    Optional<GlobalSequenceLookupResult> cachedResult,
    long startIndexLogOffset,
    long endIndexLogOffsetExclusive
) {
    public GlobalSequenceIndexLookupPlan {
        Objects.requireNonNull(cachedResult, "cachedResult");
        if (startIndexLogOffset < 0) {
            throw new IllegalArgumentException("startIndexLogOffset must not be negative");
        }
        if (endIndexLogOffsetExclusive < startIndexLogOffset) {
            throw new IllegalArgumentException(
                "endIndexLogOffsetExclusive must not be smaller than startIndexLogOffset"
            );
        }
    }

    public static GlobalSequenceIndexLookupPlan cached(GlobalSequenceLookupResult result, long highWatermark) {
        return new GlobalSequenceIndexLookupPlan(Optional.of(result), highWatermark, highWatermark);
    }

    public static GlobalSequenceIndexLookupPlan scan(long startIndexLogOffset, long highWatermark) {
        return new GlobalSequenceIndexLookupPlan(Optional.empty(), startIndexLogOffset, highWatermark);
    }
}
