package com.study.kafka.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "app.mq.type", havingValue = "kafka", matchIfMissing = true)
public class KafkaErrorHandlerConfig {

	@Bean
	CommonErrorHandler commonErrorHandler(
		KafkaTemplate<Object, Object> kafkaTemplate,
		@Value("${app.kafka.dlt-topic}") String dltTopic
	) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, ex) -> new TopicPartition(dltTopic, record.partition())
		);
		// 1회 재시도 후 DLT 전송 (처음 실패 + 재시도 1회 = 총 2회 처리)
		return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 1));

		// 실무 패턴 예시 (지수 백오프): 재시도 간격을 점점 늘려 다운스트림 부하를 완화한다.
		// org.springframework.util.backoff.ExponentialBackOff backOff =
		//     new org.springframework.util.backoff.ExponentialBackOff(1000L, 2.0);
		// backOff.setMaxAttempts(3); // 1초 → 2초 → 4초 간격으로 최대 3회 재시도
		// return new DefaultErrorHandler(recoverer, backOff);
	}
}
