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
 * The result of preparing a lookup on the coordinator event thread.
 */
record GlobalSequenceIndexLookupPreparation(
    Optional<GlobalSequenceLookupResult> cachedResult,
    Optional<GlobalSequenceIndexScanPlan> scanPlan
) {
    GlobalSequenceIndexLookupPreparation {
        Objects.requireNonNull(cachedResult, "cachedResult");
        Objects.requireNonNull(scanPlan, "scanPlan");
        if (cachedResult.isPresent() == scanPlan.isPresent()) {
            throw new IllegalArgumentException("Exactly one of cachedResult or scanPlan must be present");
        }
    }

    static GlobalSequenceIndexLookupPreparation cached(GlobalSequenceLookupResult result) {
        return new GlobalSequenceIndexLookupPreparation(
            Optional.of(Objects.requireNonNull(result, "result")),
            Optional.empty()
        );
    }

    static GlobalSequenceIndexLookupPreparation scan(GlobalSequenceIndexScanPlan plan) {
        return new GlobalSequenceIndexLookupPreparation(
            Optional.empty(),
            Optional.of(Objects.requireNonNull(plan, "plan"))
        );
    }
}
