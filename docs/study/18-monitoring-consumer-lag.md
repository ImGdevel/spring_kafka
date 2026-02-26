# 18. Consumer Lag 모니터링

목표: Consumer Lag이 무엇인지 이해하고, CLI와 JMX 메트릭으로 Lag을 관찰하는 방법을 실습한다.

---

## 1) Consumer Lag이란

```
토픽 파티션 오프셋 상태:
  Log End Offset (LEO) : 마지막으로 쓰여진 메시지 오프셋 + 1
  Committed Offset     : 컨슈머 그룹이 커밋한 오프셋

Consumer Lag = Log End Offset - Committed Offset
```

**예시:**
```
partition-0: LEO=100, Committed=90 → Lag=10
partition-1: LEO=200, Committed=200 → Lag=0
partition-2: LEO=150, Committed=100 → Lag=50

그룹 총 Lag = 10 + 0 + 50 = 60
```

**Lag이 증가하는 원인:**
- 프로듀서 처리량 > 컨슈머 처리량
- 컨슈머 처리 시간이 갑자기 느려짐 (외부 DB 응답 지연 등)
- 컨슈머 장애 (리밸런싱 중 처리 멈춤)
- `max.poll.interval.ms` 초과로 그룹 이탈

---

## 2) CLI로 Lag 확인

### 기본 명령

```bash
# 컨슈머 그룹 목록
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# 그룹 상세 정보 및 Lag 확인
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group study-group
```

출력 예시:
```
GROUP           TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
study-group     study-topic    0          45              45              0    consumer-1
study-group     study-topic    1          38              40              2    consumer-1
study-group     study-topic    2          52              52              0    consumer-2
```

### 실습: Lag 발생 시나리오

```bash
# 1. 앱 실행
./gradlew :kafka-study:bootRun

# 2. 많은 메시지 빠르게 발행
for i in {1..50}; do
  curl -s -X POST http://localhost:8080/api/messages \
    -H "Content-Type: application/json" \
    -d "{\"message\":\"msg-$i\"}" &
done
wait

# 3. 즉시 Lag 확인 (처리 중이라면 Lag > 0)
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group study-group

# 4. 잠시 후 재확인 (처리 완료 후 Lag = 0)
sleep 5
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group study-group
```

### 오프셋 리셋 (주의: 데이터 재처리 또는 스킵)

```bash
# 가장 오래된 오프셋으로 리셋 (재처리)
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group study-group \
  --topic study-topic \
  --reset-offsets \
  --to-earliest \
  --execute

# 최신 오프셋으로 리셋 (처리 스킵)
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group study-group \
  --topic study-topic \
  --reset-offsets \
  --to-latest \
  --execute
```

> **주의**: 리셋 전 앱을 중지해야 한다. 실행 중 리셋은 예상치 못한 동작을 일으킬 수 있다.

---

## 3) JMX 메트릭

### 주요 Consumer 메트릭

| 메트릭 | 의미 |
|---|---|
| `records-lag-max` | 파티션별 최대 Lag |
| `records-lag` | 특정 파티션의 현재 Lag |
| `commit-rate` | 초당 커밋 횟수 |
| `fetch-rate` | 초당 fetch 요청 수 |
| `records-consumed-rate` | 초당 소비 레코드 수 |

### Spring Boot Actuator로 확인

```yaml
management:
  endpoints:
    web:
      exposure:
        include: metrics
```

```bash
# Consumer Lag 메트릭
curl http://localhost:8080/actuator/metrics/kafka.consumer.records-lag-max

# 커밋 레이트
curl http://localhost:8080/actuator/metrics/kafka.consumer.commit-rate
```

응답 예시:
```json
{
  "name": "kafka.consumer.records-lag-max",
  "measurements": [{"statistic": "VALUE", "value": 0.0}],
  "availableTags": [{"tag": "topic", "values": ["study-topic"]}]
}
```

---

## 4) 브로커 상태 메트릭

| 메트릭 | 정상값 |
|---|---|
| `UnderReplicatedPartitions` | 0 |
| `ActiveControllerCount` | 1 |
| `OfflinePartitionsCount` | 0 |

```bash
# JMX로 브로커 메트릭 확인
docker exec -it kafka kafka-run-class.sh kafka.tools.JmxTool \
  --jmx-url "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi" \
  --object-name "kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions"
```

---

## 5) Lag 알람 기준 (실무 참고)

| 상황 | 조치 |
|---|---|
| Lag 지속 증가 | 컨슈머 인스턴스 수평 확장 (파티션 수 이하로) |
| Lag이 갑자기 급증 | 처리 병목 원인 파악 (DB 쿼리, 외부 API 응답 지연) |
| Lag이 0에서 오르지 않음 | 컨슈머 정상 동작 중 |
| Lag이 0으로 떨어지지 않음 | 처리량이 발행량을 따라가지 못함 |

**Lag 모니터링 도구 (외부):**
- **Kafka UI** (opensource): 웹 UI로 토픽/컨슈머 그룹/Lag 시각화
- **Burrow** (LinkedIn): Consumer Lag 전용 모니터링 서비스
- **Grafana + JMX Exporter + Prometheus**: 대시보드 구성

> Kafka UI를 docker-compose에 추가하면 시각적으로 편리하다.

```yaml
# docker-compose.yml에 추가
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "8090:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
    depends_on:
      - kafka
```

```
http://localhost:8090 → 토픽/컨슈머/Lag 웹 UI
```
