# 11. 오프셋 커밋 전략

목표: `enable.auto.commit`의 위험성을 이해하고, Spring Kafka의 `AckMode` 전체를 비교해 상황에 맞는 전략을 선택한다.

---

## 1) enable.auto.commit의 동작과 위험성

### 동작 원리
```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: true          # 기본값 (Spring Kafka에서는 false로 관리됨)
      auto-commit-interval-ms: 5000     # 5초마다 자동 커밋
```

`enable.auto.commit=true`이면 컨슈머 라이브러리가 백그라운드에서 주기적으로 커밋한다.

### 위험성
```
poll() → [msg1, msg2, msg3 반환]
              ↓
         처리 중 (msg1 완료, msg2 처리 중)
              ↓
         자동 커밋 타이머 → [msg1, msg2, msg3 오프셋 전부 커밋]
              ↓
         msg2 처리 실패 (예외) → 이미 커밋됨 → 유실!
```

**문제**: "처리 완료 여부와 관계없이" 커밋된다 → **at-most-once** 위험

**Spring Kafka 기본 동작**: `ContainerProperties.AckMode`를 `BATCH`로 설정하고 `enable.auto.commit`을 비활성화한다. 따라서 Spring Kafka 리스너는 기본적으로 수동 커밋 방식으로 동작한다.

---

## 2) Spring Kafka AckMode 전체 정리

| AckMode | 커밋 타이밍 | 특징 |
|---|---|---|
| `BATCH` | 각 `poll()` 배치 처리 완료 후 | **기본값**. 배치 내 하나라도 실패 시 전체 재처리 가능 |
| `RECORD` | 각 레코드 처리 완료 후 | 레코드 단위 커밋. 처리량 낮아짐 |
| `TIME` | 일정 시간마다 | `ackTime` 설정 필요 |
| `COUNT` | 일정 레코드 수마다 | `ackCount` 설정 필요 |
| `COUNT_TIME` | TIME 또는 COUNT 조건 먼저 충족 시 | - |
| `MANUAL` | `acknowledge()` 호출 → 다음 배치 끝 커밋 | 처리 흐름 제어 가능 |
| `MANUAL_IMMEDIATE` | `acknowledge()` 호출 즉시 커밋 | 가장 세밀한 제어 |

---

## 3) MANUAL_IMMEDIATE 구현 예시

### application.yaml
```yaml
spring:
  kafka:
    listener:
      ack-mode: MANUAL_IMMEDIATE
```

### 리스너 (이 프로젝트 실제 구현)

JSON 직렬화(JsonDeserializer)와 함께 사용하는 경우 `ConsumerRecord`의 제네릭 타입을 맞춰야 한다.

```java
// MessageListener.java
@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
public void listen(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
    String message = record.value().message();
    if (message != null && message.contains("fail")) {
        throw new IllegalStateException("실패를 시뮬레이션한 메시지: " + message);
        // 예외 throw → ErrorHandler가 DLT로 라우팅 후 오프셋 커밋 처리
        // ack.acknowledge() 불필요
    }
    log.info("메시지 소비 완료: value={}, partition={}, offset={}",
        message, record.partition(), record.offset());
    ack.acknowledge();  // 성공 시 즉시 커밋
}

// DltMessageListener.java
@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "study-dlt-group")
public void listenDlt(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
    log.warn("DLT 레코드 소비: value={}, dltOffset={}", record.value().message(), record.offset());
    ack.acknowledge();
}
```

**실패 경로 처리**: `MANUAL_IMMEDIATE`에서 예외를 throw하면 `DefaultErrorHandler`가 받아서 재시도 후 DLT로 전송하고 오프셋을 커밋한다. `ack.acknowledge()`를 직접 호출할 필요가 없다.

### 일반적인 패턴 (예외 분기 처리)
```java
@KafkaListener(topics = "${app.kafka.topic}")
public void listen(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
    try {
        processMessage(record.value().message());
        ack.acknowledge();  // 성공 시 즉시 커밋

    } catch (TransientException e) {
        // 재시도 가능: acknowledge 호출 안 함 → 다음 poll에서 재처리
        log.warn("일시 오류, 재처리 예정: {}", record.value().message());

    } catch (Exception e) {
        // 복구 불가: 스킵 후 커밋 (유실 감수)
        log.error("처리 불가 메시지 스킵: {}", record.value().message());
        ack.acknowledge();
    }
}
```

### `Acknowledgment.nack()` 활용 (Spring Kafka 2.9+)
```java
@KafkaListener(topics = "${app.kafka.topic}")
public void listen(String message, Acknowledgment acknowledgment) {
    if (shouldRetry(message)) {
        // 현재 레코드부터 재시도 (sleep 후 재처리)
        acknowledgment.nack(Duration.ofSeconds(5));
        return;
    }
    processMessage(message);
    acknowledgment.acknowledge();
}
```

---

## 4) 실습: BATCH vs MANUAL_IMMEDIATE 중복/유실 비교

### 시나리오 설정
`MessageListener`에 일부러 예외를 발생시키는 코드를 추가한다.

**BATCH (기본):**
```
poll([msg1, msg2, msg3])
msg1 처리 성공
msg2 처리 중 예외 발생
→ 배치 전체 커밋 안 됨
→ 재시작 시 msg1, msg2, msg3 재처리 (msg1 중복!)
```

**MANUAL_IMMEDIATE:**
```
poll([msg1, msg2, msg3])
msg1 처리 성공 → acknowledge() → 커밋
msg2 처리 중 예외 발생 → acknowledge 안 함
→ 재시작 시 msg2, msg3부터 재처리 (msg1은 이미 커밋됨)
```

### 실행 방법
1. `ack-mode: BATCH`로 설정 후 `"fail"`이 포함된 메시지 발행
2. 로그에서 커밋 시점과 재처리 여부 확인
3. `ack-mode: MANUAL_IMMEDIATE`로 변경 후 동일 시나리오 반복

```bash
# 두 번째 메시지가 실패하는 시나리오
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"ok"}'

curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"please fail"}'
```

---

## 5) 전략 선택 기준

| 상황 | 권장 AckMode |
|---|---|
| 단순 로그 수집 (유실 무방) | `BATCH` (기본) |
| 레코드별 독립 처리 (중복 최소화) | `MANUAL_IMMEDIATE` |
| 배치 단위 DB 트랜잭션과 연동 | `MANUAL` (배치 끝에 커밋) |
| 처리량 극대화 (정확도 후순위) | `TIME` 또는 `COUNT` |

> 트랜잭션 기반 EOS는 [09. 멱등 프로듀서 & EOS](09-idempotent-producer-exactly-once.md)에서 다룬다.
