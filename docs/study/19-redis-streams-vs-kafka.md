# 19. Redis Streams vs Kafka 비교

목표: 이 프로젝트에 이미 구현된 `kafka-redis-compare` 모듈을 활용해 Redis Streams와 Kafka의 핵심 차이점을 구체적으로 비교한다. 어느 상황에 어느 쪽을 선택하는지 의사결정 기준을 정립한다.

---

## 1) 이 프로젝트에서의 구현 비교

### Kafka 방식 (kafka-study 모듈)

```java
// 발행: KafkaTemplate
kafkaTemplate.send(topic, key, message);

// 소비: @KafkaListener (Push 방식, 내부적으로 poll)
@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
public void listen(String message, Acknowledgment ack) {
    processMessage(message);
    ack.acknowledge();  // 브로커에 오프셋 커밋
}
```

### Redis Streams 방식 (kafka-redis-compare 모듈)

```java
// 발행: XADD
redisTemplate.opsForStream().add(streamKey, Map.of("message", payload));

// 소비: 스케줄러로 XREADGROUP 폴링 (Pull 방식)
@Scheduled(fixedDelay = 1000)  // 1초마다 직접 폴링
void pollStream() {
    var records = redisTemplate.opsForStream().read(
        Consumer.from(groupName, consumerName),
        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
        StreamOffset.create(streamKey, ReadOffset.lastConsumed())
    );
    records.forEach(record -> {
        listener.onMessage(record);
        redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());  // XACK
    });
}
```

---

## 2) 핵심 개념 비교

### Consumer Group 방식

| 항목 | Kafka | Redis Streams |
|---|---|---|
| 그룹 등록 | 브로커가 자동 관리 | `XGROUP CREATE` 명령 필요 |
| 파티션 배정 | 브로커가 자동 리밸런싱 | 없음 (모든 컨슈머가 그룹에서 독립 pull) |
| 컨슈머 추가 | 자동 파티션 재배정 | 그냥 추가됨 (경쟁적으로 pull) |

### ACK 방식

| 항목 | Kafka | Redis Streams |
|---|---|---|
| ACK 대상 | 브로커의 오프셋 커밋 | Redis의 `XACK` (stream별 PEL 제거) |
| ACK 단위 | 오프셋 (파티션별 순차적) | 메시지 ID (개별 메시지) |
| ACK 미완료 시 | 커밋 안 된 오프셋부터 재처리 | PEL(Pending Entry List)에 남아 재처리 가능 |

### 오프셋/ID 관리

| 항목 | Kafka | Redis Streams |
|---|---|---|
| 식별자 | 오프셋 (0부터 단조증가 정수) | ID = `타임스탬프-시퀀스` (예: `1738000000000-0`) |
| 저장 위치 | 브로커 내부 토픽 (`__consumer_offsets`) | Redis 자체 |
| 커밋 영속성 | Kafka 클러스터에 영속 | Redis 재시작 시 영속성 설정 따름 |

---

## 3) 실습: 두 방식 직접 비교

### Redis Streams 앱 실행

```bash
# Redis와 Kafka 모두 기동 (둘 다 docker-compose에 있어야 함)
docker compose up -d

# Redis Streams 비교 앱 실행 (포트 8081)
./gradlew :kafka-redis-compare:bootRun
```

### 메시지 발행 (Redis Streams 방식)

```bash
curl -X POST http://localhost:8081/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"redis-stream-test"}'
```

로그 확인:
```
Redis Stream 수신: stream=redis-stream-key, id=1738000000000-0, body={message=redis-stream-test}
```

### Kafka 방식과 나란히 비교

```bash
# 터미널 1: Kafka 앱
./gradlew :kafka-study:bootRun

# 터미널 2: Redis Streams 앱
./gradlew :kafka-redis-compare:bootRun

# 각각에 메시지 발행
curl -X POST http://localhost:8080/api/messages -H "Content-Type: application/json" -d '{"message":"kafka-test"}'
curl -X POST http://localhost:8081/api/messages -H "Content-Type: application/json" -d '{"message":"redis-test"}'
```

**관찰 포인트:**
1. Kafka는 `@KafkaListener`가 브로커 push를 받아 즉시 처리
2. Redis Streams는 1초마다 `@Scheduled`로 polling → 최대 1초 지연 가능
3. Kafka는 파티션별 오프셋으로 재처리가 정확, Redis는 PEL로 미처리 메시지 추적

---

## 4) 성능/운영 특성 비교

| 항목 | Kafka | Redis Streams |
|---|---|---|
| 처리량 | 수백만 msg/s (디스크 기반) | 수십만 msg/s (메모리 기반, 더 빠를 수 있음) |
| 메시지 보존 | 설정한 retention 기간 | 메모리/`MAXLEN` 제한까지 |
| 영속성 | 디스크 (항상 영속) | AOF/RDB 설정에 따라 다름 |
| 클러스터링 | 기본 설계 (브로커 클러스터) | Redis Cluster (별도 설정) |
| 리밸런싱 | 자동 (Cooperative 가능) | 없음 (수동 설계) |
| 메시지 재처리 | 오프셋 리셋으로 전체 재처리 | PEL XCLAIM으로 개별 재처리 |
| 운영 복잡도 | 높음 (Kafka 전용 인프라) | 낮음 (이미 Redis 있다면) |

---

## 5) 의사결정 기준

### Kafka를 선택할 때

- **장기 메시지 보존이 필요**: 수 일~수 주 retention, 오디트 로그
- **대용량 처리량**: 초당 수십만~수백만 건
- **여러 컨슈머 그룹이 독립적으로 동일 메시지를 소비**: 토픽 1개를 여러 그룹이 각자 처리
- **파티션 기반 순서 보장**: 키별 메시지 순서가 중요한 경우
- **Kafka Streams, Kafka Connect** 생태계를 활용하려는 경우

### Redis Streams를 선택할 때

- **이미 Redis를 사용 중**: 별도 Kafka 인프라 없이 간단한 큐 기능이 필요
- **낮은 지연 + 소규모**: 처리량이 크지 않고 빠른 응답이 중요
- **간단한 작업 큐**: 복잡한 토폴로지 없이 단순 pub/sub
- **Kafka 설치/운영 비용을 피하고 싶을 때**

### 이 프로젝트에서의 교훈

`kafka-redis-compare` 모듈의 `RedisStreamConfig`를 보면 `@Scheduled(fixedDelay = 1000)` 폴링이 필요하다.
Kafka의 `@KafkaListener`는 브로커가 push해주므로 별도 스케줄링이 없다.

Redis Streams는 **폴링 주기만큼의 지연**이 발생하는 반면, Kafka는 거의 즉시 처리된다.

---

## 요약

```
소규모 / 이미 Redis 있음 / 빠른 구현 → Redis Streams
대용량 / 장기 보존 / 생태계 활용 / 멀티 컨슈머 그룹 → Kafka
```

> 이 프로젝트에서 `app.mq.type: kafka` 또는 `app.mq.type: redis`로 전환하면서 동일한 인터페이스(`MessagePublisher`)로 두 방식을 비교할 수 있다. 자세한 Strategy 패턴 구조는 [06. 이 프로젝트의 Spring Kafka 코드 읽기](06-spring-kafka-in-this-project.md)를 참조한다.
