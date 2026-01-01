package com.study.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(
	classes = {KafkaApplication.class, KafkaMessagingIntegrationTest.TestConfig.class},
	properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"app.kafka.topic=test-topic"
	}
)
@EmbeddedKafka(partitions = 1, topics = {"test-topic"})
class KafkaMessagingIntegrationTest {

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private TestListener testListener;

	@Test
	void sendsAndReceivesMessage() throws Exception {
		kafkaTemplate.send("test-topic", "hello kafka");
		assertThat(testListener.awaitMessage("hello kafka")).isTrue();
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		TestListener testListener() {
			return new TestListener();
		}
	}

	static class TestListener {

		private final CountDownLatch latch = new CountDownLatch(1);
		private final AtomicReference<String> payload = new AtomicReference<>();

		@KafkaListener(topics = "${app.kafka.topic}", groupId = "test-group")
		void listen(String message) {
			payload.set(message);
			latch.countDown();
		}

		boolean awaitMessage(String expected) throws InterruptedException {
			return latch.await(10, TimeUnit.SECONDS) && expected.equals(payload.get());
		}
	}
}
