package com.study.kafka.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 학습용 리스너. payload에 "fail"이 포함되면 예외를 던져 DLT 전송을 확인한다.
 */
@Slf4j
@Service
public class MessageListener {

	@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
	public void listen(ConsumerRecord<String, String> record) {
		if (record.value() != null && record.value().contains("fail")) {
			throw new IllegalStateException("실패를 시뮬레이션한 메시지: " + record.value());
		}
		log.info("메시지 소비 완료: value={}, key={}, partition={}, offset={}",
			record.value(), record.key(), record.partition(), record.offset());
	}
}
