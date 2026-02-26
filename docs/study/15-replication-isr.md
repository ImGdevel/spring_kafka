# 15. 복제와 ISR

목표: 브로커 장애가 나도 메시지가 사라지지 않는 원리(복제/ISR)를 이해한다. 로컬 단일 브로커 환경의 한계를 인지하고 개념 중심으로 학습한다.

---

## 1) 복제(Replication) 기본 개념

Kafka는 파티션 단위로 복제한다.

```
토픽: orders  (replication.factor=3, 파티션=1)

Broker-1 (Leader):   partition-0  [msg1, msg2, msg3] ← 쓰기/읽기
Broker-2 (Follower): partition-0  [msg1, msg2, msg3] ← 복제만
Broker-3 (Follower): partition-0  [msg1, msg2, msg3] ← 복제만
```

| 역할 | 설명 |
|---|---|
| **Leader** | 해당 파티션의 모든 읽기/쓰기를 처리 |
| **Follower** | Leader에서 복제. 직접 서비스하지 않음 |
| **ISR (In-Sync Replicas)** | Leader와 동기화 상태인 복제본 목록 |

---

## 2) ISR 동작 원리

Follower는 Leader에서 메시지를 주기적으로 fetch한다.
일정 시간(`replica.lag.time.max.ms`, 기본 30초) 이상 뒤처지면 ISR에서 제외된다.

```
ISR = {Leader(1), Follower(2), Follower(3)}

Follower-3이 네트워크 문제로 뒤처짐
→ replica.lag.time.max.ms 초과
→ ISR = {Leader(1), Follower(2)}  ← Follower-3 제외됨

Follower-3 회복 후 따라잡기 완료
→ ISR = {Leader(1), Follower(2), Follower(3)}  ← 재합류
```

---

## 3) `acks=all` + `min.insync.replicas` 조합

```
# 브로커/토픽 설정
min.insync.replicas=2

# 프로듀서 설정
acks=all
```

`acks=all`: ISR의 **모든** 복제본이 메시지를 저장해야 ACK 응답
`min.insync.replicas=2`: ISR이 최소 2개 이상이어야 쓰기 허용

```
정상 상태: ISR = {1, 2, 3}, min.insync.replicas=2
→ 최소 2개 ISR 충족 → 쓰기 가능

Follower-2, 3 장애: ISR = {1}
→ min.insync.replicas=2 불충족
→ NotEnoughReplicasException → 쓰기 거부
```

**트레이드오프:**
- `min.insync.replicas=1`: 가용성 ↑, 내구성 ↓ (Leader 장애 시 유실 가능)
- `min.insync.replicas=2`: 가용성 ↓, 내구성 ↑ (더 많은 복제 필요)

---

## 4) Leader 장애 시 Failover

```
정상: Leader=Broker-1, ISR={1, 2, 3}

Broker-1 장애 발생
→ 컨트롤러가 새 Leader 선출 (ISR 중에서)
→ Broker-2가 새 Leader로 선출
→ 프로듀서/컨슈머는 새 Leader(Broker-2)로 자동 리다이렉트

소요 시간: 수 초 (컨트롤러 감지 + Metadata 전파)
```

---

## 5) Unclean Leader Election 위험성

ISR에 속한 복제본이 하나도 없을 때, OSR(Out-of-Sync Replica)을 Leader로 선출하는 것이다.

```yaml
# 브로커 설정 (기본값: false)
unclean.leader.election.enable: false  # 권장
```

| 설정 | 가용성 | 데이터 손실 위험 |
|---|---|---|
| `false` (기본) | ISR 없으면 파티션 오프라인 (쓰기 거부) | 없음 |
| `true` | 뒤처진 복제본이라도 선출 | 있음 (뒤처진 만큼 유실) |

**언제 `true`를 고려하나:** 가용성이 내구성보다 훨씬 중요한 경우 (로그 수집 등 유실 허용 가능한 시스템)

---

## 6) 로컬 환경의 한계와 개념 이해

이 프로젝트는 **단일 브로커** 환경이다.

```yaml
# docker-compose.yml
# KRaft 모드, 브로커 1개
```

```java
// KafkaConfig.java (infra-message-queue)
new NewTopic(topic, 3, (short) 1)  // replication.factor=1
```

- `replication.factor=1` → 복제본이 없음 → ISR={Leader만}
- `acks=all`이어도 ISR=1이므로 Leader 응답만으로 성공
- 브로커 장애 시 그냥 데이터 손실

**클러스터에서 제대로 테스트하려면:**
- docker-compose에 브로커 3개 추가
- `replication.factor=3`, `min.insync.replicas=2` 설정
- 브로커 하나 `docker stop`으로 장애 시뮬레이션

---

## 7) 복제 지표 (개념)

| 지표 | 설명 |
|---|---|
| `UnderReplicatedPartitions` | ISR 수가 replication.factor보다 적은 파티션 수. 0이어야 정상 |
| `OfflinePartitionsCount` | 리더가 없는 파티션 수. 0이어야 정상 |
| `ActiveControllerCount` | 클러스터의 활성 컨트롤러 수. 1이어야 정상 |

> 이 지표들은 [18. Consumer Lag 모니터링](18-monitoring-consumer-lag.md)에서 모니터링 설정과 함께 다룬다.

---

## 요약

| 설정 조합 | 내구성 | 가용성 |
|---|---|---|
| `acks=1`, `min.insync.replicas=1` | 낮음 | 높음 |
| `acks=all`, `min.insync.replicas=1` | 중간 | 높음 |
| `acks=all`, `min.insync.replicas=2` (rf=3) | 높음 | 중간 |
| `acks=all`, `min.insync.replicas=3` (rf=3) | 매우 높음 | 낮음 |
