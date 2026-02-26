# 14. 성능 튜닝

목표: 처리량(Throughput) vs 지연(Latency) 트레이드오프를 이해하고, 상황에 맞는 설정값을 선택한다.

---

## 1) Producer 튜닝

### 핵심 설정

| 설정 | 기본값 | 역할 |
|---|---|---|
| `batch.size` | 16384 (16KB) | 배치 최대 크기. 가득 차면 즉시 전송 |
| `linger.ms` | 0 | 배치를 채우기 위해 기다리는 시간. 0이면 즉시 전송 |
| `compression.type` | none | `snappy` / `lz4` / `zstd` / `gzip` |
| `buffer.memory` | 33554432 (32MB) | 전체 버퍼 크기. 초과 시 `max.block.ms` 동안 블로킹 |
| `max.block.ms` | 60000 | 버퍼 가득 찼을 때 블로킹 최대 시간 |

### `linger.ms` 튜닝 전략

```
linger.ms=0 (기본)
메시지 → 즉시 전송 → 1개짜리 배치 → 낮은 처리량, 낮은 지연

linger.ms=5~20
메시지 → 5ms 대기 → 여러 레코드 묶어 전송 → 높은 처리량, 약간의 지연

linger.ms=100+
메시지 → 100ms 대기 → 큰 배치 → 매우 높은 처리량, 눈에 띄는 지연
```

**실무 권장:** 실시간 API 응답과 무관한 백그라운드 파이프라인은 `linger.ms=10~50`을 많이 쓴다.

### 압축 비교

| 압축 방식 | CPU 부담 | 압축률 | 권장 용도 |
|---|---|---|---|
| none | 없음 | 0% | 개발/테스트 |
| snappy | 낮음 | 중간 | 실시간 처리 |
| lz4 | 낮음 | 중간 | 실시간 처리 (snappy보다 빠름) |
| zstd | 중간 | 높음 | 배치 파이프라인, 저장 비용 절감 |
| gzip | 높음 | 높음 | 구형 시스템 호환 |

---

## 2) Consumer 튜닝

| 설정 | 기본값 | 역할 |
|---|---|---|
| `fetch.min.bytes` | 1 | 브로커가 최소 이 크기의 데이터를 모아 응답 |
| `fetch.max.wait.ms` | 500 | `fetch.min.bytes` 충족 전 최대 대기 시간 |
| `max.poll.records` | 500 | 한 번의 `poll()`에서 가져올 최대 레코드 수 |
| `fetch.max.bytes` | 52428800 (50MB) | 한 번의 fetch 응답 최대 크기 |

### fetch.min.bytes + fetch.max.wait.ms 조합

```
fetch.min.bytes=1 (기본)
브로커: 데이터 있으면 바로 응답 → 낮은 지연

fetch.min.bytes=1024, fetch.max.wait.ms=500
브로커: 1KB가 쌓이거나 500ms가 지나면 응답
→ 처리량 증가, 지연 최대 500ms
```

### max.poll.records 튜닝

처리 시간이 긴 경우 `max.poll.records`를 줄여 `max.poll.interval.ms` 초과를 방지한다.

```
max.poll.records=500, 처리에 건당 2ms → 배치당 1초 → 300초 제한에 여유
max.poll.records=500, 처리에 건당 1초 → 배치당 500초 → max.poll.interval.ms 초과!

해결: max.poll.records를 50으로 줄이거나, max.poll.interval.ms를 늘린다
```

---

## 3) 파티션 수 설계 기준

파티션은 Kafka 병렬 처리의 단위다.

**처리량 기준:**
```
목표 처리량: 500MB/s
프로듀서 단일 파티션 처리량: ~50MB/s
컨슈머 단일 파티션 처리량: ~25MB/s

→ 프로듀서 기준: 500/50 = 10개
→ 컨슈머 기준: 500/25 = 20개
→ 20개 이상 권장
```

**컨슈머 수 기준:**
- 파티션 수 ≥ 컨슈머 수 (초과 컨슈머는 유휴 상태)
- 컨슈머 스케일 아웃을 고려해 여유 있게 설정

**주의사항:**
- 파티션은 늘릴 수는 있지만 줄일 수 없다 (재생성 필요)
- 너무 많은 파티션은 브로커 메모리/파일 핸들 부담 증가
- 실무 권장: 소규모 시스템 1~6개, 중간 6~30개, 대규모 30개+

---

## 4) 내구성 vs 지연 트레이드오프

### `acks` + `min.insync.replicas` 조합

```yaml
# 최고 내구성 (느림)
spring:
  kafka:
    producer:
      acks: all

# 브로커 설정 (server.properties 또는 토픽 설정)
# min.insync.replicas=2  (replication.factor=3일 때)
```

| 설정 조합 | 내구성 | 지연 |
|---|---|---|
| `acks=0` | 매우 낮음 | 매우 낮음 |
| `acks=1` | 중간 (leader 저장) | 낮음 |
| `acks=all` + `min.insync.replicas=1` | 중간 | 중간 |
| `acks=all` + `min.insync.replicas=2` | 높음 | 높음 |

**로컬 단일 브로커 환경:** `replication.factor=1`이므로 `acks=all`이어도 ISR=1이라 빠르게 응답한다. 클러스터 환경에서 체감 차이가 난다.

---

## 5) 실습: linger.ms 변경 후 배치 크기 비교

### linger.ms=0 (기본)
```yaml
spring:
  kafka:
    producer:
      properties:
        linger.ms: 0
```

```bash
./gradlew :kafka-study:bootRun

# 빠르게 여러 메시지 발행
for i in {1..20}; do
  curl -s -X POST http://localhost:8080/api/messages \
    -H "Content-Type: application/json" \
    -d "{\"message\":\"msg-$i\"}" &
done
wait
```

### linger.ms=100
```yaml
spring:
  kafka:
    producer:
      properties:
        linger.ms: 100
```

동일한 스크립트로 발행 후 로그에서 `RecordAccumulator` 관련 메시지를 확인한다.

**로그 레벨 설정 (DEBUG로 배치 크기 확인):**
```yaml
logging:
  level:
    org.apache.kafka.clients.producer: DEBUG
```

로그에서 `batch.size`, `record-send-total`, `record-size-avg` 메트릭 변화를 관찰한다.

---

## 6) Spring Boot Actuator로 Kafka 메트릭 확인

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics
  metrics:
    export:
      enabled: true
```

```bash
# 주요 프로듀서 메트릭
curl http://localhost:8080/actuator/metrics/kafka.producer.record-send-rate
curl http://localhost:8080/actuator/metrics/kafka.producer.batch-size-avg
curl http://localhost:8080/actuator/metrics/kafka.consumer.fetch-rate
```

> Consumer Lag 전용 모니터링은 [18. Consumer Lag 모니터링](18-monitoring-consumer-lag.md)에서 다룬다.
