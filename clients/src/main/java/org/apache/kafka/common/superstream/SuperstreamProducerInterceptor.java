package org.apache.kafka.common.superstream;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class SuperstreamProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {
    Superstream superstreamConnection;

    public SuperstreamProducerInterceptor() {
    }

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        if (this.superstreamConnection != null) {
            if (record != null) {
                this.superstreamConnection.updateTopicPartitions(record.topic(), record.partition());
            }
        }
        record.value();
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (this.superstreamConnection != null && metadata != null) {
            if (exception == null) {
                int serializedValueSize = metadata.serializedValueSize();
                if (serializedValueSize > 0) {
                    this.superstreamConnection.clientCounters.incrementTotalWriteBytesReduced(serializedValueSize);(serializedValueSize);
                }
            }
        }
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Superstream superstreamConn = (Superstream) configs.get(Consts.superstreamConnectionKey);
        if (superstreamConn != null) {
            this.superstreamConnection = superstreamConn;
        }
    }
}
