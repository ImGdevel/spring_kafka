# 08. 오프셋 커밋/재처리 + 에러 핸들링(DLT)

목표: “중복 vs 유실”을 이해하고, Spring Kafka의 `AckMode`와 **Dead Letter Topic(DLT)** 기본 패턴을 체험한다.

## 1) 오프셋 커밋과 전달 보장
- **At-most-once**: 커밋을 먼저 → 처리 전에 죽으면 **유실** 가능
- **At-least-once(기본)**: 처리 후 커밋 → 커밋 전에 죽으면 **중복** 가능
- **Exactly-once**: 프로듀서 멱등 + 트랜잭션 + 컨슈머 처리/커밋까지 포함한 설계 필요(아래 “멱등/트랜잭션” 문서에서 다룸 예정)

### Spring Kafka `AckMode`(리스너 컨테이너)
- 기본은 `BATCH`(poll한 레코드 배치 단위 커밋).  
  환경 설정으로 바꿀 수 있다: `spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE` 등.
- `MANUAL` 계열을 쓰면 리스너에서 `Acknowledgment.acknowledge()` 호출로 커밋 시점을 직접 제어한다.

## 2) 에러 처리와 재시도
- 리스너에서 예외가 던져지면 `CommonErrorHandler`가 재시도/스킵/DLT 여부를 결정한다.
- 기본 `DefaultErrorHandler` + `FixedBackOff`(예: 2회 재시도) + `DeadLetterPublishingRecoverer`를 많이 쓴다.

## 3) DLT(Dead Letter Topic) 패턴
- “여러 번 재시도해도 실패”한 레코드를 다른 토픽(DLT)에 보내서 손실을 막고, 나중에 재처리/분석한다.
- key/partition/offset 정보가 함께 전달되므로 원본 레코드를 추적할 수 있다.

## 4) 이 프로젝트에 추가한 것들
- 설정 파일: `kafka-study/src/main/resources/application.yaml`
  - `app.kafka.dlt-topic: study-topic-dlt`
- 토픽 생성: `kafka-infra/src/main/java/com/study/kafka/config/KafkaConfig.java` (메인 + DLT 토픽 생성, 파티션 3개)
- 에러 핸들러: `kafka-infra/src/main/java/com/study/kafka/config/KafkaErrorHandlerConfig.java`
  - `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (재시도 1회, 그 후 DLT 전송)
- 실패 시뮬레이션: `kafka-study/src/main/java/com/study/kafka/messaging/MessageListener.java`
  - payload에 `"fail"`이 포함되면 예외를 던져서 DLT로 보내지도록 함
- DLT 소비 로그: `kafka-study/src/main/java/com/study/kafka/messaging/DltMessageListener.java`
  - DLT에 쌓인 레코드를 별도 로그로 확인

## 5) 로컬에서 시나리오 실행
1) Kafka + 앱 실행
```bash
docker compose up -d
./gradlew bootRun
```
2) 정상 메시지(성공 → 커밋)
```bash
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"ok"}'
```
  - 로그: `Consumed message: ok (key=null, partition=..., offset=...)`

3) 실패 메시지(DLT로 이동)
```bash
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"please fail"}'
```
  - 로그 흐름:
    - 리스너에서 예외 → `DefaultErrorHandler`가 재시도 1회
    - 재시도 후에도 실패 → DLT로 전송
    - DLT 리스너 로그: `Consumed DLT message: ... (origPartition=..., origOffset=...)`

> 참고: 재시도 횟수/백오프/스킵 조건은 `KafkaErrorHandlerConfig`에서 조정 가능하다.

## 6) 왜 이런 구조가 중요한가?
- “실패를 무시하고 커밋”하면 **유실**이지만 중복은 없다.
- “처리 후 커밋”하면 **중복**은 생길 수 있지만 유실을 막는다.
- 재시도 + DLT를 쓰면 “최소 1번 + 실패 기록 보존” 패턴을 구현할 수 있다.
- 운영에서는 DLT 모니터링/재처리(역컨슘 or ETL) 체계를 함께 두어야 한다.

## 7) MANUAL_IMMEDIATE AckMode 구현 예시

`spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE`로 설정하면 리스너가 커밋 시점을 직접 제어한다.

### application.yaml 설정
```yaml
spring:
  kafka:
    listener:
      ack-mode: MANUAL_IMMEDIATE
```

### 리스너 구현 (Acknowledgment 파라미터 추가)
```java
@KafkaListener(topics = “${app.kafka.topic}”, groupId = “${spring.kafka.consumer.group-id}”)
public void listen(String message, Acknowledgment acknowledgment) {
    try {
        // 비즈니스 처리
        process(message);

        // 처리 성공 후 커밋
        acknowledgment.acknowledge();

    } catch (RecoverableException e) {
        // 복구 가능한 예외: 커밋하지 않음 → 재처리
        log.warn(“처리 실패, 재시도 대기: {}”, message);

    } catch (Exception e) {
        // 복구 불가능한 예외: 건너뛰거나 DLT로 보내고 커밋
        log.error(“처리 불가 메시지, 스킵: {}”, message);
        acknowledgment.acknowledge(); // 유실을 감수하고 진행
    }
}
```

### MANUAL vs MANUAL_IMMEDIATE 차이
| AckMode | 커밋 타이밍 |
|---|---|
| `MANUAL` | `acknowledge()` 호출 → 다음 `poll()` 또는 배치 끝에 커밋 |
| `MANUAL_IMMEDIATE` | `acknowledge()` 호출 즉시 커밋 |

**MANUAL_IMMEDIATE**가 더 엄격한 제어가 필요할 때 사용한다.
처리 도중 장애가 나도 커밋된 메시지는 재처리되지 않는다.

> AckMode 전체 비교는 [11. 오프셋 커밋 전략](11-offset-commit-strategies.md)에서 다룬다.

## 8) @RetryableTopic — Non-blocking 재시도 (Spring Kafka 2.7+)

현재 구현한 `DefaultErrorHandler` 방식은 재시도 중 메인 컨슈머 스레드가 **블로킹**된다.
`@RetryableTopic`은 재시도 메시지를 별도 토픽으로 라우팅해서 메인 컨슈머를 막지 않는다.

### 비교

| | `DefaultErrorHandler` | `@RetryableTopic` |
|---|---|---|
| 재시도 중 메인 컨슈머 | **블로킹** (파티션 소비 정지) | **Non-blocking** (메인 파티션 계속 소비) |
| 재시도 토픽 | 없음 | `topic-retry-0`, `topic-retry-1` 자동 생성 |
| DLT 연결 | 수동 설정 (`DeadLetterPublishingRecoverer`) | 자동 연결 |
| 코드 위치 | `@Configuration` 빈 | `@KafkaListener` 메서드에 `@RetryableTopic` 추가 |

### 적용 예시

```java
@RetryableTopic(
    attempts = “3”,
    backoff = @Backoff(delay = 1000, multiplier = 2.0),
    dltStrategy = DltStrategy.FAIL_ON_ERROR
)
@KafkaListener(topics = “${app.kafka.topic}”)
public void listen(MessagePayload payload) {
    // “fail” 포함 시 예외 → retry-0 → retry-1 → DLT 순으로 자동 라우팅
}
```

> 이 프로젝트에서는 `DefaultErrorHandler` 방식으로 DLT 라우팅을 직접 구현하고 있다.
> 처리량이 중요한 운영 환경에서는 `@RetryableTopic` 방식이 컨슈머 지연을 줄이는 데 유리하다.

## 9) 추가로 실험해볼 것
- `FixedBackOff`를 바꿔서 재시도 횟수/지연 체감하기
- DLT에 쌓인 레코드를 별도 컨슈머 앱에서 읽어 “재처리” 플로우 만들어보기
