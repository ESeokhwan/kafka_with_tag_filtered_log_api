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
import java.util.Optional;

/**
 * One decoded record from the global sequence index log.
 */
public record GlobalSequenceIndexLogEntry(
    long logOffset,
    Uuid topicId,
    long globalBaseOffset,
    Optional<GlobalSequenceIndexRecord> indexRecord
) {
    public GlobalSequenceIndexLogEntry {
        if (logOffset < 0) {
            throw new IllegalArgumentException("logOffset must not be negative");
        }
        Objects.requireNonNull(topicId, "topicId");
        if (Uuid.ZERO_UUID.equals(topicId)) {
            throw new IllegalArgumentException("topicId must not be ZERO_UUID");
        }
        if (globalBaseOffset < 0) {
            throw new IllegalArgumentException("globalBaseOffset must not be negative");
        }
        Objects.requireNonNull(indexRecord, "indexRecord");
        indexRecord.ifPresent(record -> {
            if (!topicId.equals(record.topicId()) || globalBaseOffset != record.globalBaseOffset()) {
                throw new IllegalArgumentException("The index record must match the log entry key");
            }
        });
    }

    public static GlobalSequenceIndexLogEntry allocation(
        long logOffset,
        GlobalSequenceIndexRecord indexRecord
    ) {
        Objects.requireNonNull(indexRecord, "indexRecord");
        return new GlobalSequenceIndexLogEntry(
            logOffset,
            indexRecord.topicId(),
            indexRecord.globalBaseOffset(),
            Optional.of(indexRecord)
        );
    }

    public static GlobalSequenceIndexLogEntry tombstone(
        long logOffset,
        Uuid topicId,
        long globalBaseOffset
    ) {
        return new GlobalSequenceIndexLogEntry(logOffset, topicId, globalBaseOffset, Optional.empty());
    }
}
