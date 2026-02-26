package com.study.kafka.infra.kafka;

import com.study.messaging.MessagePublisher;
import com.study.messaging.dto.MessagePayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.mq.type", havingValue = "kafka", matchIfMissing = true)
public class KafkaMessagePublisher implements MessagePublisher {

	private final KafkaTemplate<Object, Object> kafkaTemplate;
	private final String topic;

	public KafkaMessagePublisher(
		KafkaTemplate<Object, Object> kafkaTemplate,
		@Value("${app.kafka.topic}") String topic
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	@Override
	public void publish(String message, String key) {
		MessagePayload payload = new MessagePayload(message);
		kafkaTemplate.executeInTransaction(ops -> {
			if (key == null || key.isBlank()) {
				ops.send(topic, payload);
			}
			else {
				ops.send(topic, key, payload);
			}
			return null;
		});
	}
}
