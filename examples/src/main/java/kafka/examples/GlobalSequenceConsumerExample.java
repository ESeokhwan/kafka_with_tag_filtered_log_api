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
package kafka.examples;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.GlobalSequenceFetchResult;
import org.apache.kafka.clients.consumer.GlobalSequenceRecord;
import org.apache.kafka.clients.consumer.KafkaGlobalSequenceConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.Exit;

import java.time.Duration;
import java.util.Properties;

/**
 * Fetches an explicit global offset range, following response pages until the
 * requested end offset is reached.
 */
public final class GlobalSequenceConsumerExample {
    private GlobalSequenceConsumerExample() {
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: GlobalSequenceConsumerExample " +
                "<bootstrap-servers> <topic> <global-start-offset> <global-end-offset-exclusive>");
            Exit.exit(1);
        }

        String bootstrapServers = args[0];
        String topic = args[1];
        long nextGlobalOffset = Long.parseLong(args[2]);
        long globalEndOffsetExclusive = Long.parseLong(args[3]);

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaGlobalSequenceConsumer<String, String> consumer =
                 new KafkaGlobalSequenceConsumer<>(properties)) {
            while (nextGlobalOffset < globalEndOffsetExclusive) {
                GlobalSequenceFetchResult<String, String> page = consumer.fetch(
                    topic,
                    nextGlobalOffset,
                    globalEndOffsetExclusive,
                    Duration.ofSeconds(30)
                );
                for (GlobalSequenceRecord<String, String> record : page) {
                    System.out.printf(
                        "globalOffset=%d physicalPartition=%d physicalOffset=%d key=%s value=%s%n",
                        record.globalOffset(),
                        record.physicalPartition(),
                        record.physicalOffset(),
                        record.key(),
                        record.value()
                    );
                }
                nextGlobalOffset = page.nextGlobalOffset();
            }
        }
    }
}
