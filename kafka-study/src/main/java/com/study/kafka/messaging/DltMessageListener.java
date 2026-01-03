package com.study.kafka.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT에 쌓인 실패 레코드를 관찰하는 리스너 (재처리는 별도 로직에서 수행).
 */
@Slf4j
@Service
public class DltMessageListener {

	@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "study-dlt-group")
	public void listenDlt(ConsumerRecord<String, String> record) {
		log.warn("DLT 레코드 소비: value={}, key={}, dltPartition={}, dltOffset={}, 원본파티션={}, 원본오프셋={}",
			record.value(),
			record.key(),
			record.partition(),
			record.offset(),
			record.headers().lastHeader("kafka_dlt-original-partition") != null
				? new String(record.headers().lastHeader("kafka_dlt-original-partition").value())
				: "unknown",
			record.headers().lastHeader("kafka_dlt-original-offset") != null
				? new String(record.headers().lastHeader("kafka_dlt-original-offset").value())
				: "unknown"
		);
	}
}
