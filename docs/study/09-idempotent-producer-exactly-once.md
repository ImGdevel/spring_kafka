# 09. 멱등 프로듀서 & EOS 트랜잭션

목표: "정확히 한 번(Exactly-Once Semantics, EOS)" 전달이 어떻게 동작하는지 원리부터 Spring Kafka 구현까지 이해한다.

---

## 1) 문제: 재시도하면 중복이 생긴다

프로듀서가 메시지를 보내고 ACK를 못 받으면 재시도한다.
브로커는 이미 저장했는데 ACK 응답 중에 네트워크가 끊기면 **동일 메시지가 두 번 저장**된다.

```
Producer → send → Broker [저장 완료]
              ← ACK (네트워크 끊김)
Producer → 재시도 → Broker [또 저장] ← 중복!
```

---

## 2) 멱등 프로듀서(Idempotent Producer)

### 활성화
```yaml
spring:
  kafka:
    producer:
      enable-idempotence: true   # Kafka 3.0+ 기본값 true
```

### 동작 원리: PID + Sequence Number

| 개념 | 설명 |
|---|---|
| **PID** (Producer ID) | 브로커가 프로듀서 세션에 부여하는 고유 ID |
| **Sequence Number** | 프로듀서가 메시지마다 증가시키는 단조증가 번호 |

브로커는 `(PID, Partition, SequenceNumber)` 조합을 기억한다.
재시도로 같은 메시지가 오면 중복임을 알고 **저장을 건너뛰되 ACK는 보낸다**.

```
Producer (PID=42) → send(seq=5) → Broker [seq=5 저장]
                ← ACK 유실
Producer (PID=42) → retry(seq=5) → Broker [seq=5 이미 있음, 스킵 후 ACK]
```

### 제약 조건
- 단일 파티션, 단일 프로듀서 세션 내에서만 보장된다.
- 프로세스가 재시작되면 PID가 새로 발급되어 이전 보장이 사라진다.
- 여러 파티션에 걸친 원자적(atomic) 쓰기는 **트랜잭션**이 필요하다.

---

## 3) Kafka 트랜잭션

여러 파티션/토픽에 메시지를 **원자적으로** 쓸 때 사용한다.

### 핵심 설정
```yaml
spring:
  kafka:
    producer:
      transaction-id-prefix: tx-   # 고유 prefix 설정 시 트랜잭션 활성화
```

### 트랜잭션 API (저수준)
```java
kafkaTemplate.executeInTransaction(ops -> {
    ops.send("topic-a", "message1");
    ops.send("topic-b", "message2");
    return true; // commit
    // 예외 throw 시 rollback
});
```

### `transactional.id` 역할
- 브로커가 프로듀서를 식별하는 영속적 ID
- 이전 미완료 트랜잭션을 정리(fence)한다 → **zombie producer 방지**

---

## 4) EOS(Exactly-Once Semantics)

EOS = 프로듀서 멱등 + 트랜잭션 + 컨슈머 `isolation.level=read_committed`

### 컨슈머 isolation.level
```yaml
spring:
  kafka:
    consumer:
      isolation-level: read_committed   # 커밋된 메시지만 소비
```

| isolation.level | 동작 |
|---|---|
| `read_uncommitted` (기본) | 진행 중인 트랜잭션의 메시지도 읽음 |
| `read_committed` | 커밋 완료된 메시지만 읽음 → abort된 메시지 무시 |

### EOS 전체 흐름
```
Producer                    Broker                   Consumer
────────────────────────────────────────────────────────────
beginTransaction()
send(msg1) [seq=1]    →  partition-A 임시 저장
send(msg2) [seq=1]    →  partition-B 임시 저장
commitTransaction()   →  2개 파티션 원자적 커밋
                                              ← read_committed
                                                 msg1, msg2 소비
```

---

## 5) Spring Kafka에서 `@Transactional` + `KafkaTransactionManager`

### 설정 (별도 빈 등록)
```java
@Configuration
public class KafkaTransactionConfig {

    @Bean
    public KafkaTransactionManager<String, String> kafkaTransactionManager(
            ProducerFactory<String, String> pf) {
        return new KafkaTransactionManager<>(pf);
    }
}
```

### 서비스에서 사용
```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Transactional("kafkaTransactionManager")
    public void processOrder(String orderId) {
        kafkaTemplate.send("order-events", orderId, "ORDER_CREATED");
        kafkaTemplate.send("audit-log", orderId, "AUDIT_ENTRY");
        // 예외 발생 시 두 send 모두 롤백
    }
}
```

### DB + Kafka 동시 트랜잭션 (ChainedTransactionManager)
DB와 Kafka를 하나의 `@Transactional`로 묶을 수 없다 (2PC가 아님).
실무에서는 **Outbox 패턴**이 권장된다:
1. DB 트랜잭션 내에서 outbox 테이블에 이벤트를 먼저 저장
2. 별도 프로세스(Debezium CDC 등)가 outbox를 읽어 Kafka에 발행

---

## 6) 실습: 트랜잭션 커밋/롤백 시나리오

### 사전 조건 — application.yaml

```yaml
# kafka-study/src/main/resources/application.yaml
spring:
  kafka:
    producer:
      transaction-id-prefix: study-tx-
```

> `docker-compose.yml`에 `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1` 설정이 있어야 단일 브로커 환경에서 트랜잭션이 동작한다.

### 주의: transaction-id-prefix 설정 시 모든 send가 트랜잭션 필요

`transaction-id-prefix`를 설정하면 `KafkaTemplate`이 transactional 모드로 전환된다.
이 상태에서 `kafkaTemplate.send()`를 트랜잭션 밖에서 호출하면 아래 예외가 발생한다.

```
java.lang.IllegalStateException: No transaction is in process; possible solutions:
  run the template operation within the scope of a template.executeInTransaction() operation,
  start a transaction with @Transactional before invoking the template method,
  run in a transaction started by a listener container when consuming a record
```

**따라서 일반 발행도 `executeInTransaction`으로 래핑해야 한다.**

```java
// KafkaMessagePublisher.publish() — 일반 단건 발행도 트랜잭션 래핑
kafkaTemplate.executeInTransaction(ops -> {
    ops.send(topic, new MessagePayload(message));
    return null;
});
```

### 트랜잭션 엔드포인트 구현

```java
// POST /api/messages/transaction
@PostMapping("/transaction")
public ResponseEntity<String> sendTransaction(@RequestBody TransactionRequest request) {
    kafkaTemplate.executeInTransaction(ops -> {
        for (String msg : request.messages()) {
            ops.send(topic, new MessagePayload(msg));
        }
        if (request.rollback()) {
            throw new RuntimeException("트랜잭션 롤백 시뮬레이션");
        }
        return null;
    });
    return ResponseEntity.accepted().body("transaction committed");
}
```

```java
public record TransactionRequest(List<String> messages, boolean rollback) {}
```

### 커밋 시나리오 실행

```bash
curl -X POST http://localhost:8082/api/messages/transaction \
  -H "Content-Type: application/json" \
  -d '{"messages":["tx-msg1","tx-msg2"],"rollback":false}'
# HTTP 202: "transaction committed"
```

소비자 로그 확인:
```
value=tx-msg1, key=null, partition=0, offset=0
value=tx-msg2, key=null, partition=0, offset=1
```
두 메시지가 모두 소비된다.

### 롤백 시나리오 실행

```bash
curl -X POST http://localhost:8082/api/messages/transaction \
  -H "Content-Type: application/json" \
  -d '{"messages":["will-rollback"],"rollback":true}'
# HTTP 500: RuntimeException이 throw됨
```

소비자 로그에 `will-rollback`이 **나타나지 않는다** → 롤백 성공.

`afka-producer-N` 스레드에서 아래 에러 로그가 남는 것은 **정상**이다:
```
ERROR LoggingProducerListener: Exception thrown when sending ... ProducerFencedException
```
비동기 send 콜백이 abort 완료를 감지한 것이며, 메시지는 커밋되지 않는다.

### 주의: 두 인스턴스 동시 실행 금지

동일한 `transaction-id-prefix`를 가진 앱 인스턴스 2개가 동시에 실행되면 `ProducerFencedException`이 발생한다.
브로커는 `transactional.id`에 대해 가장 최신 epoch 하나만 허용한다(zombie producer 방지).
앱 재시작 전 이전 프로세스를 완전히 종료해야 한다.

---

## 요약

| 기법 | 범위 | 재시작 후 보장 |
|---|---|---|
| 기본 프로듀서 | at-least-once | - |
| 멱등 프로듀서 | 단일 파티션, 단일 세션 중복 제거 | 재시작 시 새 PID → 보장 없음 |
| 트랜잭션 | 여러 파티션 원자적 쓰기 | `transactional.id` 유지 시 보장 |
| EOS (트랜잭션 + read_committed) | 프로듀서~컨슈머 종단 EOS | 완전한 exactly-once |
