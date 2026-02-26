# Kafka 학습 로드맵

이 로드맵은 Kafka를 기초부터 심화까지 단계적으로 학습하기 위한 인덱스다.
각 문서는 현재 프로젝트(`kafka/`) 코드를 기준으로 설명한다.

---

## Phase 1. 기초 (완료 — 01~08)

> 목표: Kafka가 무엇인지, 로컬에서 띄우고, 메시지를 보내고 받는 기본 흐름을 이해한다.

| 파일 | 핵심 질문 |
|---|---|
| [01. Kafka 한 장 요약](01-overview.md) | Kafka는 왜 필요한가? |
| [02. 핵심 개념: 토픽/파티션/복제/오프셋](02-core-concepts.md) | 토픽·파티션·오프셋은 무슨 관계인가? |
| [03. Producer 기초](03-producer.md) | acks/retries/배치가 왜 중요한가? |
| [04. Consumer 기초](04-consumer.md) | 오프셋 커밋과 전달 보장의 관계는? |
| [05. 로컬 실행: docker-compose(KRaft)](05-local-dev-docker-kraft.md) | Zookeeper 없이 어떻게 뜨는가? |
| [06. 이 프로젝트의 Spring Kafka 코드 읽기](06-spring-kafka-in-this-project.md) | Strategy 패턴이 왜 여기 어울리나? |
| [07. 키 기반 파티셔닝과 순서 보장](07-key-partition-ordering.md) | 키가 없으면 순서는 어떻게 되나? |
| [08. 오프셋 커밋/재처리 + 에러 핸들링(DLT)](08-error-handling-ack-dlt.md) | 실패 메시지를 어떻게 보존하나? |

---

## Phase 2. 중급 (09~13) — 실습 완료

> 목표: 중복 없는 전달, 리밸런싱, 오프셋 제어, 로그 저장 원리, 직렬화를 이해한다.

| 파일 | 핵심 질문 | 실습 |
|---|---|---|
| [09. 멱등 프로듀서 & EOS 트랜잭션](09-idempotent-producer-exactly-once.md) | "정확히 한 번"은 어떻게 구현하나? | ✅ |
| [10. 컨슈머 리밸런싱](10-consumer-rebalancing.md) | 컨슈머가 죽으면 파티션은 누가 처리하나? | ✅ (CooperativeStickyAssignor) |
| [11. 오프셋 커밋 전략](11-offset-commit-strategies.md) | AckMode 종류와 선택 기준은? | ✅ (MANUAL_IMMEDIATE) |
| [12. 로그 컴팩션](12-log-compaction.md) | 무한 저장 대신 최신 상태만 유지하려면? | - |
| [13. 직렬화/역직렬화](13-serialization.md) | 객체를 Kafka로 어떻게 주고받나? | ✅ (JsonSerializer + MessagePayload) |

학습 순서 권장: **09 → 11 → 10** (전달 보장·커밋 흐름 먼저) → **12 → 13** (저장 원리)

### Phase 2 실습 적용 내용 요약

4가지 설정을 누적 적용해 동작 검증했다.

| 설정 | 위치 | 결과 |
|---|---|---|
| `CooperativeStickyAssignor` | consumer.partition-assignment-strategy | 리밸런싱 시 Stop-the-world 최소화 |
| `transaction-id-prefix: study-tx-` | producer | `POST /api/messages/transaction` 커밋/롤백 확인 |
| `ack-mode: MANUAL_IMMEDIATE` | listener | `ack.acknowledge()` 명시 후 즉시 오프셋 커밋 |
| `JsonSerializer` / `JsonDeserializer` | producer / consumer | `MessagePayload` 객체 직접 발행·소비 |

**발견한 주의사항**: `transaction-id-prefix` 설정 시 일반 `send()`도 반드시 `executeInTransaction`으로 래핑해야 한다. 동일 prefix의 인스턴스 2개를 동시 실행하면 `ProducerFencedException` 발생.

---

## Phase 3. 심화 (14~17)

> 목표: 성능 튜닝, 고가용성 원리, Kafka 생태계(Streams·Connect)를 이해한다.

| 파일 | 핵심 질문 |
|---|---|
| [14. 성능 튜닝](14-performance-tuning.md) | 처리량 vs 지연 트레이드오프를 어떻게 조정하나? |
| [15. 복제와 ISR](15-replication-isr.md) | 브로커가 죽어도 메시지가 안 사라지는 이유는? |
| [16. Kafka Streams 입문](16-kafka-streams-intro.md) | 스트림 처리를 별도 앱 없이 할 수 있나? |
| [17. Kafka Connect 입문](17-kafka-connect-intro.md) | DB→Kafka→S3 파이프라인을 코드 없이 연결하려면? |

---

## Phase 4. 실습 시나리오 (18~19)

> 목표: 운영·비교 관점에서 Kafka를 더 깊이 이해한다.

| 파일 | 핵심 질문 |
|---|---|
| [18. Consumer Lag 모니터링](18-monitoring-consumer-lag.md) | 컨슈머가 느릴 때 어떻게 감지하나? |
| [19. Redis Streams vs Kafka 비교](19-redis-streams-vs-kafka.md) | 이 프로젝트에서 어느 쪽이 더 나은가? |

---

## 빠른 실습 명령

```bash
# Kafka 기동
docker compose up -d

# 앱 빌드 후 실행 (포트 8080이 다른 앱에 점유된 경우 --server.port=8082 등 변경)
./gradlew :kafka-study:build -x test
java -jar kafka-study/build/libs/kafka-study-*.jar --server.port=8082

# 정상 메시지 (JSON 직렬화)
curl -X POST http://localhost:8082/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"hello kafka","key":null}'

# 실패 시나리오 (DLT로 이동)
curl -X POST http://localhost:8082/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"please fail","key":null}'

# 트랜잭션 커밋
curl -X POST http://localhost:8082/api/messages/transaction \
  -H "Content-Type: application/json" \
  -d '{"messages":["tx-msg1","tx-msg2"],"rollback":false}'

# 트랜잭션 롤백 (소비자 로그에 메시지 없어야 함)
curl -X POST http://localhost:8082/api/messages/transaction \
  -H "Content-Type: application/json" \
  -d '{"messages":["will-rollback"],"rollback":true}'
```
