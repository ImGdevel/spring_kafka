# 17. Kafka Connect 입문

목표: 코드 없이 외부 시스템과 Kafka를 연결하는 Kafka Connect의 역할을 이해하고, FileStream Source Connector로 로컬 파일을 Kafka 토픽으로 읽어오는 실습을 한다.

---

## 1) Kafka Connect 역할

Kafka Connect는 외부 시스템 ↔ Kafka 간 데이터 이동을 **설정만으로** 구현하는 프레임워크다.

```
[Source Connector]                    [Sink Connector]
MySQL / PostgreSQL → Kafka Topic → S3 / Elasticsearch
File / REST API  →               → BigQuery / HDFS
Debezium CDC     →               → JDBC / Redis
```

**직접 프로듀서/컨슈머 코드를 짜는 것과의 차이:**
- 재시도, 오프셋 관리, 스케일 아웃을 Connect 프레임워크가 처리
- 설정(JSON)만으로 파이프라인 구성 가능
- 운영 중 커넥터 추가/삭제 가능 (REST API)

---

## 2) 실행 모드

| 모드 | 용도 | 설정 방법 |
|---|---|---|
| **Standalone** | 단일 프로세스, 개발/테스트 | properties 파일 |
| **Distributed** | 클러스터, 프로덕션 | REST API로 설정 관리 |

---

## 3) 대표 커넥터

| 커넥터 | 방향 | 용도 |
|---|---|---|
| **Debezium MySQL/PostgreSQL** | Source | CDC(변경 데이터 캡처), binlog/WAL 기반 |
| **JDBC Source** | Source | DB 테이블 폴링 |
| **FileStream Source** | Source | 파일 → Kafka (테스트용) |
| **S3 Sink** | Sink | Kafka → S3 (데이터 레이크) |
| **JDBC Sink** | Sink | Kafka → DB |
| **Elasticsearch Sink** | Sink | Kafka → Elasticsearch |

---

## 4) 실습: docker-compose에 Kafka Connect 추가

### 4-1. docker-compose.yml 수정

```yaml
# docker-compose.yml에 아래 서비스 추가
  kafka-connect:
    image: confluentinc/cp-kafka-connect:7.5.0
    ports:
      - "8083:8083"
    environment:
      CONNECT_BOOTSTRAP_SERVERS: kafka:29092
      CONNECT_REST_ADVERTISED_HOST_NAME: kafka-connect
      CONNECT_GROUP_ID: connect-cluster
      CONNECT_CONFIG_STORAGE_TOPIC: connect-configs
      CONNECT_OFFSET_STORAGE_TOPIC: connect-offsets
      CONNECT_STATUS_STORAGE_TOPIC: connect-status
      CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_STATUS_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_KEY_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      CONNECT_VALUE_CONVERTER: org.apache.kafka.connect.storage.StringConverter
      CONNECT_PLUGIN_PATH: /usr/share/java
    volumes:
      - /tmp/kafka-connect-data:/tmp/kafka-connect-data
    depends_on:
      - kafka
```

> 이 프로젝트의 `docker-compose.yml`은 내부 네트워크에서 `kafka:29092`로 브로커에 접근한다. 기존 설정을 먼저 확인한다.

### 4-2. Kafka Connect 기동 확인

```bash
docker compose up -d kafka-connect

# REST API 응답 확인 (30초~1분 소요)
curl http://localhost:8083/

# 설치된 플러그인 목록
curl http://localhost:8083/connector-plugins | python3 -m json.tool
```

### 4-3. 감시할 파일 생성

```bash
mkdir -p /tmp/kafka-connect-data
echo "첫 번째 줄" > /tmp/kafka-connect-data/test.txt
```

### 4-4. FileStream Source Connector 등록

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "file-source-connector",
    "config": {
      "connector.class": "FileStreamSource",
      "tasks.max": "1",
      "file": "/tmp/kafka-connect-data/test.txt",
      "topic": "file-topic"
    }
  }'
```

### 4-5. 토픽에서 메시지 확인

```bash
docker exec -it kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic file-topic \
  --from-beginning
```

### 4-6. 파일에 줄 추가 → 실시간 확인

```bash
echo "두 번째 줄" >> /tmp/kafka-connect-data/test.txt
echo "세 번째 줄" >> /tmp/kafka-connect-data/test.txt
```

컨슈머 터미널에서 새 줄이 실시간으로 출력되어야 한다.

---

## 5) Connector 관리 REST API

```bash
# 커넥터 목록
curl http://localhost:8083/connectors

# 커넥터 상태 확인
curl http://localhost:8083/connectors/file-source-connector/status

# 커넥터 일시 중지
curl -X PUT http://localhost:8083/connectors/file-source-connector/pause

# 커넥터 재개
curl -X PUT http://localhost:8083/connectors/file-source-connector/resume

# 커넥터 삭제
curl -X DELETE http://localhost:8083/connectors/file-source-connector
```

---

## 6) Debezium CDC 개념 (심화 참고)

Debezium은 DB의 변경 로그(MySQL binlog, PostgreSQL WAL)를 읽어 Kafka에 발행하는 Source Connector다.

```
MySQL binlog → Debezium Source Connector → Kafka Topic (orders.insert, orders.update, orders.delete)
```

INSERT/UPDATE/DELETE 이벤트가 각각 토픽으로 발행된다.
이를 소비해 Elasticsearch를 동기화하거나 이벤트 소싱 패턴을 구현한다.

실습이 필요하면 `docker-compose.yml`에 MySQL + Debezium Connector 이미지를 추가한다.

---

## 요약

| 항목 | Kafka Connect | 직접 프로듀서/컨슈머 |
|---|---|---|
| 개발 필요 | 설정 파일만 | 코드 작성 |
| 오프셋 관리 | 자동 | 직접 |
| 확장성 | Distributed 모드로 수평 확장 | 직접 구현 |
| 커스텀 로직 | 제한적 (SMT 변환만 가능) | 자유롭게 |
| 적합한 용도 | 표준 데이터 파이프라인 | 비즈니스 로직이 있는 소비 |
