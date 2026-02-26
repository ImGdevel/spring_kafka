# 12. 로그 컴팩션

목표: `cleanup.policy=delete`와 `compact`의 차이를 이해하고, 최신 상태만 유지하는 패턴을 실습한다.

---

## 1) Kafka 로그 보존 정책

Kafka 토픽에 메시지가 쌓이면 두 가지 방식으로 오래된 데이터를 정리할 수 있다.

| 정책 | 설정 | 동작 |
|---|---|---|
| **Delete** | `cleanup.policy=delete` | 시간(`retention.ms`) 또는 크기(`retention.bytes`) 기준으로 오래된 세그먼트 삭제 |
| **Compact** | `cleanup.policy=compact` | 같은 key의 이전 값을 제거하고 **최신 값만** 유지 |
| **둘 다** | `cleanup.policy=delete,compact` | 컴팩션 후 오래된 세그먼트도 삭제 |

---

## 2) 로그 컴팩션 동작 원리

### Dirty / Clean 세그먼트

```
[로그 세그먼트 구조]

Clean 영역 (이미 컴팩션됨)     Dirty 영역 (아직 컴팩션 안 됨)
─────────────────────────────  ────────────────────────────
key=A → value=v1              key=B → value=v2
key=B → value=v1              key=A → value=v3
                               key=C → value=v1
                               key=B → value=v3  ← 최신
```

컴팩션 후:
```
key=A → value=v3  (최신)
key=B → value=v3  (최신)
key=C → value=v1  (최신)
```

### 컴팩션 타이밍 설정

| 설정 | 기본값 | 역할 |
|---|---|---|
| `min.cleanable.dirty.ratio` | 0.5 | dirty / (dirty + clean) 비율이 이 이상이면 컴팩션 시작 |
| `segment.ms` | 604800000 (7일) | 세그먼트를 강제로 닫는 주기 |
| `min.compaction.lag.ms` | 0 | 레코드가 최소 이 시간 이후에 컴팩션 대상이 됨 |

---

## 3) Tombstone 레코드 (키 삭제)

**value=null** 인 레코드를 보내면 해당 키를 "삭제됨"으로 표시한다.

```java
// 키 삭제 (tombstone)
kafkaTemplate.send("compacted-topic", "user-123", null);
```

컴팩션 시 tombstone 레코드는 일정 시간(`delete.retention.ms`) 후 완전히 제거된다.
그 전까지는 컨슈머가 key=삭제됨을 감지할 수 있다.

---

## 4) 활용 사례: Event Sourcing Lite 패턴

로그 컴팩션은 "각 키의 최신 상태"를 Kafka 자체에 저장하는 패턴에 적합하다.

**예시: 사용자 프로필 업데이트**
```
key=user-123, value={"name":"김철수", "age":25}   ← 초기
key=user-123, value={"name":"김철수", "age":26}   ← 나이 변경
key=user-123, value={"name":"김철수", "age":26, "email":"..."}  ← 이메일 추가
```

컴팩션 후 최신 값만 남는다 → **새 컨슈머가 전체 히스토리 없이 현재 상태를 복원** 가능

**사용 사례:**
- Kafka Streams의 KTable 내부 상태 저장소
- 서비스 구성 정보(config topic)
- 캐시 웜업(새 인스턴스가 최신 상태를 Kafka에서 읽어 메모리에 적재)

---

## 5) 실습: 컴팩션 토픽 생성 후 동작 확인

### 컴팩션 토픽 생성

```bash
# Kafka 컨테이너 안에서 실행
docker exec -it kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic compacted-topic \
  --partitions 1 \
  --replication-factor 1 \
  --config cleanup.policy=compact \
  --config min.cleanable.dirty.ratio=0.01 \
  --config segment.ms=100
```

> `min.cleanable.dirty.ratio=0.01`과 `segment.ms=100`은 테스트용으로 컴팩션을 빠르게 일어나게 한다. 프로덕션에서는 기본값을 사용한다.

### 동일 key로 반복 발행

```bash
# 같은 key로 여러 번 발행
for i in {1..5}; do
  curl -X POST http://localhost:8080/api/messages \
    -H "Content-Type: application/json" \
    -d "{\"message\":\"value-$i\", \"key\":\"my-key\"}"
done
```

*(이 프로젝트의 `/api/messages` 엔드포인트는 key 파라미터를 지원하면 key-based 발행이 가능하다.)*

### 컴팩션 확인

```bash
# 토픽의 현재 오프셋 범위 확인
docker exec -it kafka kafka-run-class.sh kafka.tools.GetOffsetShell \
  --bootstrap-server localhost:9092 \
  --topic compacted-topic

# 컨슈머로 직접 확인 (컴팩션 후 최신 값만 남아야 함)
docker exec -it kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic compacted-topic \
  --from-beginning \
  --property print.key=true
```

컴팩션 완료 후 `my-key`에 대해 마지막 value만 출력되어야 한다.

---

## 요약

| 항목 | Delete 정책 | Compact 정책 |
|---|---|---|
| 보존 기준 | 시간/크기 | 키별 최신 값 |
| 적합한 용도 | 이벤트 로그, 분석 | 상태 저장, KTable |
| 전체 히스토리 보존 | O (retention 내) | X (최신만 보존) |
| Tombstone 지원 | N/A | O (null value로 삭제) |
