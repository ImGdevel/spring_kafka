package com.study.rediscompare.config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.study.rediscompare.listener.RedisStreamListener;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableScheduling
public class RedisStreamConfig {

	private final String groupName = "redis-demo-group";
	private final String consumerName = "poller-" + UUID.randomUUID();
	private final StringRedisTemplate redisTemplate;
	private final RedisStreamListener listener;
	private final String streamKey;

	public RedisStreamConfig(
		StringRedisTemplate redisTemplate,
		RedisStreamListener listener,
		@Value("${app.redis.stream}") String streamKey
	) {
		this.redisTemplate = redisTemplate;
		this.listener = listener;
		this.streamKey = streamKey;
	}

	@Bean
	InitializingBean createGroupIfNotExists() {
		return () -> {
			try {
				redisTemplate.opsForStream().createGroup(streamKey, groupName);
			} catch (Exception ignore) {
				// 이미 존재하는 경우 등은 무시
			}
		};
	}

	@Scheduled(fixedDelay = 1000)
	void pollStream() {
		var records = redisTemplate.opsForStream().read(
			Consumer.from(groupName, consumerName),
			StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
			StreamOffset.create(streamKey, ReadOffset.lastConsumed())
		);
		if (records == null || records.isEmpty()) {
			return;
		}
		records.forEach(record -> {
			listener.onMessage(record);
			redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
		});
	}
}
