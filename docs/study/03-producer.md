# 03. Producer 기초

Producer는 Kafka로 메시지(레코드)를 보내는 쪽이다.

## 레코드 기본 구조
- topic
- key (선택)
- value (보통 payload)
- headers (선택)

## 중요한 설정/개념

### 1) acks: “브로커가 저장했다고 언제 인정할까?”
- `acks=0`: 보내고 끝(가장 빠르지만 손실 가능)
- `acks=1`: leader에 기록되면 성공
- `acks=all`(또는 `-1`): ISR(in-sync replicas)까지 반영되면 성공(가장 안전, 느릴 수 있음)

로컬 단일 브로커 환경(replica=1)에서는 `acks=all`이어도 체감 차이가 작다.

### 2) retries / idempotence: “재시도하면 중복이 생기지 않나?”
네트워크/브로커 문제로 실패가 나면 재시도하게 되는데, 이때 **중복 전송**이 발생할 수 있다.

- 기본 재시도: “최소 1번(at-least-once)”에 가까워진다(중복 가능).
- **idempotent producer**(멱등 프로듀서)를 켜면, 브로커가 중복을 제거해 “중복 없는 재시도”에 가까워진다.

> 정확히 한 번(Exactly-once)은 “프로듀서 멱등 + 트랜잭션 + 컨슈머 쪽 처리/커밋”까지 함께 설계해야 한다.

### 3) 배치/압축 설정

Producer는 성능을 위해 여러 레코드를 모아(batch) 보낸다. 핵심 설정 4가지:

| 설정 | 기본값 | 역할 |
|---|---|---|
| `batch.size` | 16384 (16 KB) | 배치 최대 크기. 이 크기가 차면 즉시 전송 |
| `linger.ms` | 0 | 배치를 채우기 위해 기다리는 최대 시간(ms). 0이면 즉시 전송 |
| `buffer.memory` | 33554432 (32 MB) | Producer가 브로커로 보내기 전 버퍼 총 크기 |
| `compression.type` | none | `gzip` / `snappy` / `lz4` / `zstd` 중 선택 |

**`batch.size` + `linger.ms` 조합이 핵심:**
- `linger.ms=0`(기본): 레코드가 들어오면 바로 전송 → 낮은 지연, 낮은 처리량
- `linger.ms=10`: 10ms 기다렸다가 모아서 전송 → 배치 효율 증가, 지연 소폭 증가

**압축(`compression.type`):**
- 배치 단위로 압축된다. 배치가 클수록 압축률이 높아진다.
- CPU 사용량 증가 ↔ 네트워크·브로커 저장 비용 감소 트레이드오프
- `lz4` / `snappy`: CPU 부담 적음, 적당한 압축률 (실무에서 자주 쓰임)
- `zstd`: 높은 압축률, CPU 부담 있음

> 자세한 성능 튜닝은 [14. 성능 튜닝](14-performance-tuning.md)에서 다룬다.

## 이 프로젝트에서 Producer는 어디인가?
- HTTP 요청을 받아서 Kafka로 보내는 부분: `kafka-study/src/main/java/com/study/kafka/web/MessageController.java`
- Controller는 애플리케이션 인터페이스인 `MessagePublisher`만 호출한다. (인터페이스: `infra-message-queue/src/main/java/com/study/messaging/MessagePublisher.java`)

- Kafka로 실제 전송(Infra): `infra-message-queue/src/main/java/com/study/kafka/infra/kafka/KafkaMessagePublisher.java`
  - 내부에서 `KafkaTemplate<String, String>`로 전송한다.
  - key가 없으면: `kafkaTemplate.send(topic, message)`
  - key가 있으면: `kafkaTemplate.send(topic, key, message)`

## 연습 아이디어
- key를 추가해서 “특정 사용자 ID는 같은 파티션으로” 보내보기
- 실패/재시도 상황을 가정하고 “중복이 생겨도 안전한 처리”를 고민해보기(컨슈머 쪽)
