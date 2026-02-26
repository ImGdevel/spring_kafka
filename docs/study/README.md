# Kafka 공부 노트 (이 프로젝트 기준)

이 폴더는 `Spring Boot + Kafka`를 처음 공부할 때 “무엇을, 왜, 어떻게”를 순서대로 이해할 수 있게 정리한 문서 모음이다.
코드는 현재 레포를 기준으로 설명한다.

전체 학습 경로: **[ROADMAP.md](ROADMAP.md)**

## Phase 1. 기초 (01~08)
1. [01. Kafka 한 장 요약](01-overview.md)
2. [02. 핵심 개념: 토픽/파티션/복제/오프셋](02-core-concepts.md)
3. [03. Producer 기초](03-producer.md)
4. [04. Consumer 기초](04-consumer.md)
5. [05. 로컬 실행: docker-compose(KRaft)](05-local-dev-docker-kraft.md)
6. [06. 이 프로젝트의 Spring Kafka 코드 읽기](06-spring-kafka-in-this-project.md)
7. [07. 키 기반 파티셔닝과 순서 보장](07-key-partition-ordering.md)
8. [08. 오프셋 커밋/재처리 + 에러 핸들링(DLT)](08-error-handling-ack-dlt.md)

## Phase 2. 중급 (09~13)
9. [09. 멱등 프로듀서 & EOS 트랜잭션](09-idempotent-producer-exactly-once.md)
10. [10. 컨슈머 리밸런싱](10-consumer-rebalancing.md)
11. [11. 오프셋 커밋 전략](11-offset-commit-strategies.md)
12. [12. 로그 컴팩션](12-log-compaction.md)
13. [13. 직렬화/역직렬화](13-serialization.md)

## Phase 3. 심화 (14~17)
14. [14. 성능 튜닝](14-performance-tuning.md)
15. [15. 복제와 ISR](15-replication-isr.md)
16. [16. Kafka Streams 입문](16-kafka-streams-intro.md)
17. [17. Kafka Connect 입문](17-kafka-connect-intro.md)

## Phase 4. 실습 시나리오 (18~19)
18. [18. Consumer Lag 모니터링](18-monitoring-consumer-lag.md)
19. [19. Redis Streams vs Kafka 비교](19-redis-streams-vs-kafka.md)

## 이 프로젝트에서 바로 해볼 것
- Kafka 기동: `docker compose up -d`
- 앱 실행: `./gradlew bootRun`
- 메시지 발행:
  - `curl -X POST http://localhost:8080/api/messages -H “Content-Type: application/json” -d '{“message”:”hello kafka”}'`
- 로그 확인: `Consumed message: hello kafka (key=null, partition=..., offset=...)`
