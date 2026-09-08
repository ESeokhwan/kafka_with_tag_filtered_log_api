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
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.consumer.GlobalSequenceFetchResult;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.FetchGlobalSequenceResponseData;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.serialization.StringDeserializer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlobalSequenceResponseDecoderTest {
    private static final String TOPIC = "global-topic";

    @Test
    void testDecodesPartialBatchesInGlobalOrder() {
        MemoryRecords firstRecords = records(42L, "a", "b", "c", "d");
        MemoryRecords secondRecords = records(10L, "e", "f", "g");
        FetchGlobalSequenceResponseData response = new FetchGlobalSequenceResponseData()
            .setNextGlobalOffset(6L)
            .setBatches(List.of(
                batch(0L, 4, 2, 4, 3, 42L, firstRecords),
                batch(4L, 3, 0, 2, 1, 10L, secondRecords)
            ));

        try (GlobalSequenceResponseDecoder<String, String> decoder = decoder(IsolationLevel.READ_UNCOMMITTED)) {
            GlobalSequenceFetchResult<String, String> result = decoder.decode(TOPIC, 2L, 6L, response);

            assertEquals(List.of(2L, 3L, 4L, 5L),
                result.records().stream().map(record -> record.globalOffset()).collect(Collectors.toList()));
            assertEquals(List.of("c", "d", "e", "f"),
                result.records().stream().map(record -> record.value()).collect(Collectors.toList()));
            assertEquals(List.of(44L, 45L, 10L, 11L),
                result.records().stream().map(record -> record.physicalOffset()).collect(Collectors.toList()));
            assertEquals(List.of(3, 3, 1, 1),
                result.records().stream().map(record -> record.physicalPartition()).collect(Collectors.toList()));
            assertEquals(6L, result.nextGlobalOffset());
        }
    }

    @Test
    void testFiltersAbortedTransactionalBatchForReadCommitted() {
        long producerId = 7L;
        MemoryRecords records = MemoryRecords.withTransactionalRecords(
            20L,
            Compression.NONE,
            producerId,
            (short) 0,
            0,
            RecordBatch.NO_PARTITION_LEADER_EPOCH,
            new SimpleRecord(bytes("a")),
            new SimpleRecord(bytes("b"))
        );
        FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch batch =
            batch(0L, 2, 0, 2, 0, 20L, records)
                .setAbortedTransactions(List.of(
                    new FetchGlobalSequenceResponseData.AbortedTransaction()
                        .setProducerId(producerId)
                        .setFirstOffset(20L)
                ));
        FetchGlobalSequenceResponseData response = new FetchGlobalSequenceResponseData()
            .setNextGlobalOffset(2L)
            .setBatches(List.of(batch));

        try (GlobalSequenceResponseDecoder<String, String> committed = decoder(IsolationLevel.READ_COMMITTED);
             GlobalSequenceResponseDecoder<String, String> uncommitted = decoder(IsolationLevel.READ_UNCOMMITTED)) {
            assertEquals(0, committed.decode(TOPIC, 0L, 2L, response).count());
            assertEquals(2, uncommitted.decode(TOPIC, 0L, 2L, response).count());
        }
    }

    @Test
    void testRejectsNonProgressingResponse() {
        FetchGlobalSequenceResponseData response = new FetchGlobalSequenceResponseData()
            .setNextGlobalOffset(0L);

        try (GlobalSequenceResponseDecoder<String, String> decoder = decoder(IsolationLevel.READ_UNCOMMITTED)) {
            assertThrows(KafkaException.class, () -> decoder.decode(TOPIC, 0L, 2L, response));
        }
    }

    @Test
    void testRejectsOutOfOrderBatches() {
        FetchGlobalSequenceResponseData response = new FetchGlobalSequenceResponseData()
            .setNextGlobalOffset(3L)
            .setBatches(List.of(batch(1L, 2, 0, 2, 0, 10L, records(10L, "a", "b"))));

        try (GlobalSequenceResponseDecoder<String, String> decoder = decoder(IsolationLevel.READ_UNCOMMITTED)) {
            assertThrows(KafkaException.class, () -> decoder.decode(TOPIC, 0L, 3L, response));
        }
    }

    private static GlobalSequenceResponseDecoder<String, String> decoder(IsolationLevel isolationLevel) {
        return new GlobalSequenceResponseDecoder<>(
            new StringDeserializer(),
            new StringDeserializer(),
            isolationLevel,
            true
        );
    }

    private static FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch batch(
        long globalBaseOffset,
        int recordCount,
        int firstRecordIndex,
        int lastRecordIndexExclusive,
        int physicalPartition,
        long physicalBaseOffset,
        MemoryRecords records
    ) {
        return new FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch()
            .setGlobalBaseOffset(globalBaseOffset)
            .setRecordCount(recordCount)
            .setFirstRecordIndex(firstRecordIndex)
            .setLastRecordIndexExclusive(lastRecordIndexExclusive)
            .setPhysicalPartition(physicalPartition)
            .setPhysicalBaseOffset(physicalBaseOffset)
            .setRecords(records);
    }

    private static MemoryRecords records(long baseOffset, String... values) {
        SimpleRecord[] records = new SimpleRecord[values.length];
        for (int index = 0; index < values.length; index++) {
            records[index] = new SimpleRecord(bytes(values[index]));
        }
        return MemoryRecords.withRecords(baseOffset, Compression.NONE, records);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
