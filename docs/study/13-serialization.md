# 13. 직렬화/역직렬화

목표: Kafka가 메시지를 bytes로 저장하는 이유를 이해하고, 이 프로젝트에서 String → JSON 객체로 직렬화를 변경하는 실습을 한다.

---

## 1) Kafka에서 직렬화가 중요한 이유

Kafka는 메시지를 **바이트 배열(byte[])**로 저장한다.
직렬화(Serialization)는 객체 → bytes, 역직렬화(Deserialization)는 bytes → 객체 변환이다.

**잘못된 직렬화의 문제:**
- 프로듀서와 컨슈머가 다른 포맷을 쓰면 역직렬화 실패
- 스키마 변경 시 하위 호환성 깨짐
- 타입 정보가 없으면 수신 측에서 해석 불가

---

## 2) 직렬화 방식 비교

### StringSerializer / StringDeserializer

가장 단순하다. 이 프로젝트의 현재 방식이다.

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

- 장점: 단순, 디버깅 쉬움 (콘솔에서 바로 읽힘)
- 단점: 타입 정보 없음, 복잡한 객체를 수동으로 JSON 변환해야 함

### JsonSerializer / JsonDeserializer (Spring Kafka)

객체를 Jackson으로 직렬화한다.

```yaml
spring:
  kafka:
    producer:
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.study.messaging.dto"
        spring.json.value.default.type: com.study.messaging.dto.MessageRequest
```

- 장점: 객체 직접 발행/소비, 타입 안전
- 단점: 타입 정보가 헤더에 포함되어 크기 증가, 수신 측에서 같은 클래스 필요

### ByteArraySerializer / ByteArrayDeserializer

원시 바이트를 직접 다룰 때 사용한다. Avro, Protobuf 등과 조합한다.

---

## 3) Avro + Schema Registry (개념 소개)

Avro는 스키마를 별도 레지스트리(Confluent Schema Registry)에 등록하고,
메시지에는 스키마 ID만 포함시켜 크기를 줄이는 방식이다.

```
[Avro 직렬화 흐름]
프로듀서 → 스키마 등록(최초 1회) → 메시지 = [magic byte][schema-id][avro bytes]
컨슈머  → schema-id로 레지스트리 조회 → 역직렬화
```

**장점**: 스키마 진화(필드 추가/삭제) 관리, 하위 호환성 강제
**단점**: Schema Registry 인프라 필요, 설정 복잡도 증가

이 프로젝트에서는 Schema Registry를 설치하지 않으므로 개념 이해에 그친다.

---

## 4) 실습: String → JSON 객체 직렬화로 변경

이 프로젝트에서 `MessagePayload` DTO를 JSON으로 직렬화해 발행/소비한다.

### 4-1. MessagePayload DTO

```java
// infra-message-queue/src/main/java/com/study/messaging/dto/MessagePayload.java
package com.study.messaging.dto;

public record MessagePayload(String message) {}
```

### 4-2. application.yaml 수정 (kafka-study)

```yaml
spring:
  kafka:
    producer:
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.study.messaging.dto"
        spring.json.value.default.type: com.study.messaging.dto.MessagePayload
```

### 4-3. KafkaMessagePublisher 수정

트랜잭션 프로듀서(`transaction-id-prefix` 설정)와 함께 사용하는 경우 `KafkaTemplate<Object, Object>` 타입을 사용해야 한다. `KafkaTemplate<String, MessagePayload>` 타입으로 선언하면 스프링이 의존성을 주입하지 못하는 경우가 있다.

```java
// infra-message-queue/.../kafka/KafkaMessagePublisher.java
@Service
@ConditionalOnProperty(name = "app.mq.type", havingValue = "kafka", matchIfMissing = true)
public class KafkaMessagePublisher implements MessagePublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    @Override
    public void publish(String message, String key) {
        MessagePayload payload = new MessagePayload(message);
        kafkaTemplate.executeInTransaction(ops -> {
            if (key == null || key.isBlank()) {
                ops.send(topic, payload);
            } else {
                ops.send(topic, key, payload);
            }
            return null;
        });
    }
}
```

> `transaction-id-prefix` 설정 시 모든 `send()`가 트랜잭션 컨텍스트 안에 있어야 한다.
> 자세한 내용은 [09. 멱등 프로듀서 & EOS](09-idempotent-producer-exactly-once.md) 참고.

### 4-4. MessageListener / DltMessageListener 수정

```java
// MessageListener.java
@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
public void listen(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
    String message = record.value().message();
    if (message != null && message.contains("fail")) {
        throw new IllegalStateException("실패를 시뮬레이션한 메시지: " + message);
    }
    log.info("메시지 소비 완료: value={}, partition={}, offset={}",
        message, record.partition(), record.offset());
    ack.acknowledge();
}
```

### 4-5. KafkaErrorHandlerConfig 수정

DLT 전송에 사용하는 `KafkaTemplate`도 타입을 맞춰야 한다.

```java
@Bean
CommonErrorHandler commonErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate, ...) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, ...);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 1));
}
```

### 4-6. 동작 확인

```bash
docker compose up -d
java -jar kafka-study/build/libs/kafka-study-*.jar --server.port=8082

# 정상 메시지
curl -X POST http://localhost:8082/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"hello-json","key":null}'
```

소비자 로그:
```
메시지 소비 완료: value=hello-json, key=null, partition=1, offset=35
```

Kafka 브로커에 저장된 실제 바이트를 보면 JSON 형식으로 저장된 것을 확인할 수 있다:
```json
{"message":"hello-json"}
```

---

## 5) 직렬화 선택 기준

| 상황 | 권장 직렬화 |
|---|---|
| 빠른 프로토타입, 간단한 텍스트 | `StringSerializer` |
| 자바 객체를 주고받는 서비스 | `JsonSerializer` |
| 다른 언어 서비스와 연동 | `JsonSerializer` 또는 Avro |
| 스키마 진화 관리가 중요한 대규모 시스템 | Avro + Schema Registry |
