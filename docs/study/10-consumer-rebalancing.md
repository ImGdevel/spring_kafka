# 10. 컨슈머 리밸런싱

목표: 리밸런싱이 언제 어떻게 발생하는지 이해하고, Cooperative 방식으로 Stop-the-world를 최소화한다.

---

## 1) 리밸런싱이란

Consumer Group 내 파티션 배정이 다시 이뤄지는 과정이다.

**트리거 조건:**
- 컨슈머 추가 (스케일 아웃)
- 컨슈머 제거 (정상 종료 또는 장애)
- `session.timeout.ms` 초과 — heartbeat가 끊긴 컨슈머를 브로커가 죽은 것으로 판단
- `max.poll.interval.ms` 초과 — 처리 시간이 너무 길어 poll 주기가 늦어진 경우
- 토픽 파티션 수 변경

**관련 설정:**

| 설정 | 기본값 | 역할 |
|---|---|---|
| `session.timeout.ms` | 45000ms | heartbeat 없이 이 시간이 지나면 컨슈머 제거 |
| `heartbeat.interval.ms` | 3000ms | heartbeat 전송 주기 (session.timeout의 1/3 권장) |
| `max.poll.interval.ms` | 300000ms | poll() 간격 초과 시 그룹에서 제외 |

---

## 2) Eager vs Cooperative 리밸런싱

### Eager 리밸런싱 (기본 — `RangeAssignor`, `RoundRobinAssignor`)

```
컨슈머A: partition-0, partition-1
컨슈머B: partition-2

컨슈머C 추가 → 리밸런싱 시작
       ↓
[Stop-the-world] 모든 컨슈머가 파티션을 반납
       ↓
전체 재배정
컨슈머A: partition-0
컨슈머B: partition-1
컨슈머C: partition-2
```

**문제**: 리밸런싱 동안 그룹 전체가 메시지 처리를 멈춘다.

### Cooperative 리밸런싱 (`CooperativeStickyAssignor`)

```
컨슈머A: partition-0, partition-1
컨슈머B: partition-2

컨슈머C 추가 → 리밸런싱 시작
       ↓
필요한 파티션만 이동 (나머지는 계속 처리)
컨슈머A: partition-0, partition-1  [계속 처리 중]
컨슈머B: partition-2 → partition-2 반납
컨슈머C: partition-2 배정
```

**장점**: 이동이 필요 없는 파티션은 처리를 멈추지 않는다.

---

## 3) CooperativeStickyAssignor 설정

```yaml
spring:
  kafka:
    consumer:
      group-id: study-group
      partition-assignment-strategy:
        - org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

### 마이그레이션 시 주의사항
기존 Eager → Cooperative로 전환 시 **롤링 업그레이드**가 필요하다.
모든 컨슈머를 한 번에 바꾸면 Eager와 Cooperative 인스턴스가 섞여 문제가 생길 수 있다.

권장 절차:
1. `CooperativeStickyAssignor`를 **두 번째** assignor로 추가
2. 모든 인스턴스 재시작
3. 기존 assignor 제거 후 재시작

---

## 4) 리밸런싱 로그 관찰

### 실습: 컨슈머 2개 띄우고 하나 강제 종료

```bash
# 터미널 1: 첫 번째 컨슈머 (kafka-study 앱 실행)
./gradlew :kafka-study:bootRun

# 터미널 2: 두 번째 컨슈머 (같은 group-id로 새 포트에 실행)
SERVER_PORT=8081 ./gradlew :kafka-study:bootRun
```

파티션 배정 로그 확인:
```
INFO  Assigned partitions: [study-topic-0, study-topic-1]
INFO  Assigned partitions: [study-topic-2]
```

터미널 1 강제 종료(`Ctrl+C`) 후:
```
INFO  Partitions revoked: [study-topic-0, study-topic-1]  ← Eager의 경우
INFO  Assigned partitions: [study-topic-0, study-topic-1, study-topic-2]  ← 재배정
```

### 리밸런싱 중 메시지 발행
리밸런싱 중에도 메시지를 발행하면 지연(Lag)이 쌓이는 것을 확인할 수 있다.

```bash
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/messages \
    -H "Content-Type: application/json" \
    -d "{\"message\":\"msg-$i\"}" &
done
wait
```

> Consumer Lag 모니터링 방법은 [18. Consumer Lag 모니터링](18-monitoring-consumer-lag.md)에서 다룬다.

---

## 5) 리밸런싱 중 커밋 타이밍 주의

리밸런싱 직전에 처리 중인 메시지가 있으면 `ConsumerRebalanceListener`로 처리할 수 있다.

```java
@KafkaListener(topics = "${app.kafka.topic}")
public void listen(String message, Acknowledgment ack,
                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
    try {
        processMessage(message);
        ack.acknowledge();
    } catch (Exception e) {
        // 리밸런싱 중 커밋 안 된 메시지는 새 컨슈머에서 재처리됨
        log.error("처리 실패 partition={}: {}", partition, message);
    }
}
```

---

## 요약

| 항목 | Eager (기본) | Cooperative (CooperativeStickyAssignor) |
|---|---|---|
| 리밸런싱 방식 | 전체 파티션 반납 후 재배정 | 이동 필요한 파티션만 이동 |
| Stop-the-world | 있음 | 거의 없음 |
| 설정 복잡도 | 단순 | assignor 명시 필요 |
| 권장 사용 환경 | 소규모, 단순 | 대규모, 고가용성 |
