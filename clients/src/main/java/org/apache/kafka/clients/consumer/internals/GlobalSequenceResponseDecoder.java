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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.GlobalSequenceFetchResult;
import org.apache.kafka.clients.consumer.GlobalSequenceRecord;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.CorruptRecordException;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.errors.RecordDeserializationException.DeserializationExceptionOrigin;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.message.FetchGlobalSequenceResponseData;
import org.apache.kafka.common.record.BaseRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.CloseableIterator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Validates and deserializes global sequence fetch responses.
 */
public final class GlobalSequenceResponseDecoder<K, V> implements AutoCloseable {
    private final Deserializer<K> keyDeserializer;
    private final Deserializer<V> valueDeserializer;
    private final IsolationLevel isolationLevel;
    private final boolean checkCrcs;
    private final BufferSupplier decompressionBufferSupplier;

    public GlobalSequenceResponseDecoder(
        Deserializer<K> keyDeserializer,
        Deserializer<V> valueDeserializer,
        IsolationLevel isolationLevel,
        boolean checkCrcs
    ) {
        this.keyDeserializer = keyDeserializer;
        this.valueDeserializer = valueDeserializer;
        this.isolationLevel = isolationLevel;
        this.checkCrcs = checkCrcs;
        this.decompressionBufferSupplier = BufferSupplier.create();
    }

    public GlobalSequenceFetchResult<K, V> decode(
        String topic,
        long requestedStartOffset,
        long requestedEndOffsetExclusive,
        FetchGlobalSequenceResponseData response
    ) {
        long expectedNextOffset = requestedStartOffset;
        List<GlobalSequenceRecord<K, V>> decoded = new ArrayList<>();

        for (FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch fetchBatch : response.batches()) {
            validateFetchBatch(fetchBatch, requestedStartOffset, requestedEndOffsetExclusive, expectedNextOffset);
            decodeFetchBatch(topic, fetchBatch, decoded);
            expectedNextOffset = Math.addExact(fetchBatch.globalBaseOffset(), fetchBatch.lastRecordIndexExclusive());
        }

        long nextGlobalOffset = response.nextGlobalOffset();
        if (nextGlobalOffset != expectedNextOffset ||
            nextGlobalOffset <= requestedStartOffset ||
            nextGlobalOffset > requestedEndOffsetExclusive) {
            throw invalidResponse("Invalid next global offset " + nextGlobalOffset +
                " for requested range [" + requestedStartOffset + ", " + requestedEndOffsetExclusive +
                ") and decoded batch end " + expectedNextOffset);
        }
        return new GlobalSequenceFetchResult<>(decoded, nextGlobalOffset);
    }

    private void validateFetchBatch(
        FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch batch,
        long requestedStartOffset,
        long requestedEndOffsetExclusive,
        long expectedStartOffset
    ) {
        if (batch.globalBaseOffset() < 0 || batch.recordCount() <= 0 ||
            batch.physicalPartition() < 0 || batch.physicalBaseOffset() < 0) {
            throw invalidResponse("Invalid global sequence batch metadata: " + batch);
        }
        if (batch.firstRecordIndex() < 0 ||
            batch.firstRecordIndex() >= batch.lastRecordIndexExclusive() ||
            batch.lastRecordIndexExclusive() > batch.recordCount()) {
            throw invalidResponse("Invalid record index range in global sequence batch: " + batch);
        }

        long batchStartOffset = Math.addExact(batch.globalBaseOffset(), batch.firstRecordIndex());
        long batchEndOffset = Math.addExact(batch.globalBaseOffset(), batch.lastRecordIndexExclusive());
        if (batchStartOffset != expectedStartOffset ||
            batchStartOffset < requestedStartOffset ||
            batchEndOffset > requestedEndOffsetExclusive) {
            throw invalidResponse("Out-of-order global sequence batch " + batch +
                " for requested range [" + requestedStartOffset + ", " + requestedEndOffsetExclusive + ")");
        }
    }

    private void decodeFetchBatch(
        String topic,
        FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch fetchBatch,
        List<GlobalSequenceRecord<K, V>> decoded
    ) {
        BaseRecords baseRecords = fetchBatch.records();
        if (!(baseRecords instanceof Records)) {
            throw invalidResponse("Missing records for global sequence batch " + fetchBatch);
        }
        Records records = (Records) baseRecords;
        Iterator<? extends RecordBatch> batches = records.batches().iterator();
        if (!batches.hasNext()) {
            throw invalidResponse("Empty records for global sequence batch " + fetchBatch);
        }

        RecordBatch recordBatch = batches.next();
        if (batches.hasNext()) {
            throw invalidResponse("Multiple physical record batches returned for one global sequence batch");
        }
        long expectedPhysicalEndOffset = Math.addExact(fetchBatch.physicalBaseOffset(), fetchBatch.recordCount());
        if (recordBatch.baseOffset() != fetchBatch.physicalBaseOffset() ||
            recordBatch.nextOffset() != expectedPhysicalEndOffset) {
            throw invalidResponse("Physical record batch does not match global sequence batch metadata");
        }
        ensureValid(recordBatch, fetchBatch);

        if (recordBatch.isControlBatch() || isAborted(recordBatch, fetchBatch.abortedTransactions())) {
            return;
        }

        try (CloseableIterator<Record> iterator = recordBatch.streamingIterator(decompressionBufferSupplier)) {
            while (iterator.hasNext()) {
                Record record = iterator.next();
                long recordIndex = Math.subtractExact(record.offset(), fetchBatch.physicalBaseOffset());
                if (recordIndex < fetchBatch.firstRecordIndex() ||
                    recordIndex >= fetchBatch.lastRecordIndexExclusive()) {
                    continue;
                }
                ensureValid(record, fetchBatch);
                decoded.add(deserialize(topic, fetchBatch, record, recordIndex, recordBatch));
            }
        }
    }

    private boolean isAborted(
        RecordBatch recordBatch,
        List<FetchGlobalSequenceResponseData.AbortedTransaction> abortedTransactions
    ) {
        if (isolationLevel != IsolationLevel.READ_COMMITTED || !recordBatch.isTransactional() ||
            abortedTransactions == null) {
            return false;
        }
        return abortedTransactions.stream().anyMatch(transaction ->
            transaction.producerId() == recordBatch.producerId() &&
                transaction.firstOffset() <= recordBatch.lastOffset()
        );
    }

    private GlobalSequenceRecord<K, V> deserialize(
        String topic,
        FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch fetchBatch,
        Record record,
        long recordIndex,
        RecordBatch recordBatch
    ) {
        ByteBuffer keyBytes = record.key();
        ByteBuffer valueBytes = record.value();
        Headers headers = new RecordHeaders(record.headers());
        TopicPartition physicalPartition = new TopicPartition(topic, fetchBatch.physicalPartition());
        TimestampType timestampType = recordBatch.timestampType();
        K key;
        V value;
        try {
            key = keyBytes == null ? null : keyDeserializer.deserialize(topic, headers, keyBytes);
        } catch (RuntimeException exception) {
            throw deserializationException(
                DeserializationExceptionOrigin.KEY,
                physicalPartition,
                timestampType,
                record,
                headers,
                exception
            );
        }
        try {
            value = valueBytes == null ? null : valueDeserializer.deserialize(topic, headers, valueBytes);
        } catch (RuntimeException exception) {
            throw deserializationException(
                DeserializationExceptionOrigin.VALUE,
                physicalPartition,
                timestampType,
                record,
                headers,
                exception
            );
        }

        Optional<Integer> leaderEpoch = recordBatch.partitionLeaderEpoch() == RecordBatch.NO_PARTITION_LEADER_EPOCH ?
            Optional.empty() : Optional.of(recordBatch.partitionLeaderEpoch());
        return new GlobalSequenceRecord<>(
            topic,
            Math.addExact(fetchBatch.globalBaseOffset(), recordIndex),
            fetchBatch.physicalPartition(),
            record.offset(),
            record.timestamp(),
            timestampType,
            keyBytes == null ? ConsumerRecord.NULL_SIZE : keyBytes.remaining(),
            valueBytes == null ? ConsumerRecord.NULL_SIZE : valueBytes.remaining(),
            key,
            value,
            headers,
            leaderEpoch
        );
    }

    private void ensureValid(
        RecordBatch recordBatch,
        FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch fetchBatch
    ) {
        if (checkCrcs && recordBatch.magic() >= RecordBatch.MAGIC_VALUE_V2) {
            try {
                recordBatch.ensureValid();
            } catch (CorruptRecordException exception) {
                throw new KafkaException("Invalid physical record batch for global sequence batch " + fetchBatch,
                    exception);
            }
        }
    }

    private void ensureValid(
        Record record,
        FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch fetchBatch
    ) {
        if (checkCrcs) {
            try {
                record.ensureValid();
            } catch (CorruptRecordException exception) {
                throw new KafkaException("Invalid record for global sequence batch " + fetchBatch, exception);
            }
        }
    }

    private RecordDeserializationException deserializationException(
        DeserializationExceptionOrigin origin,
        TopicPartition physicalPartition,
        TimestampType timestampType,
        Record record,
        Headers headers,
        RuntimeException cause
    ) {
        return new RecordDeserializationException(
            origin,
            physicalPartition,
            record.offset(),
            record.timestamp(),
            timestampType,
            record.key(),
            record.value(),
            headers,
            "Error deserializing " + origin.name() + " for physical partition " + physicalPartition +
                " at offset " + record.offset(),
            cause
        );
    }

    private KafkaException invalidResponse(String message) {
        return new KafkaException("Invalid FETCH_GLOBAL_SEQUENCE response: " + message);
    }

    @Override
    public void close() {
        decompressionBufferSupplier.close();
    }
}
