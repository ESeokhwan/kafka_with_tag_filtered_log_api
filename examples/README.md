# Kafka client examples

This module contains some Kafka client examples.

1. Start a Kafka 2.5+ local cluster with a plain listener configured on port 9092.
2. Run `examples/bin/java-producer-consumer-demo.sh 10000` to asynchronously send 10k records to topic1 and consume them.
3. Run `examples/bin/java-producer-consumer-demo.sh 10000 sync` to synchronous send 10k records to topic1 and consume them.
4. Run `examples/bin/exactly-once-demo.sh 6 3 10000` to create input-topic and output-topic with 6 partitions each,
   start 3 transactional application instances and process 10k records.

## Global sequence consumer

`GlobalSequenceConsumerExample` fetches a half-open global offset range from a topic configured with
`global.sequence.enabled=true`. Unlike `KafkaConsumer`, the global sequence consumer does not join a consumer group,
track a position, or commit offsets. It returns one size-bounded page at a time and the example continues from each
page's `nextGlobalOffset`.

After building the examples, run:

```shell
bin/kafka-run-class.sh kafka.examples.GlobalSequenceConsumerExample \
  localhost:9092 globally-sequenced-topic 0 100
```
