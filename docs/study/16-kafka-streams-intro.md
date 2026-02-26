# 16. Kafka Streams 입문

목표: 별도 스트림 처리 프레임워크(Spark, Flink) 없이 Kafka 내에서 실시간 데이터 처리를 구현하는 방법을 이해한다. 이 프로젝트에 `kafka-streams-study` 모듈을 추가해 Word Count 예제를 직접 실행한다.

---

## 1) Kafka Streams vs 일반 Consumer

| 항목 | 일반 KafkaListener | Kafka Streams |
|---|---|---|
| 추상화 수준 | 레코드 단위 처리 | 데이터 흐름(topology) 정의 |
| 상태 저장 | 직접 DB/Redis에 저장 | 내장 State Store (RocksDB) |
| 윈도우 연산 | 직접 구현 | 내장 API |
| 조인 | 직접 구현 | KStream-KTable 조인 API |
| 배포 | Spring Boot 앱 | 동일 (Java 라이브러리) |
| 오프셋 관리 | Spring Kafka가 처리 | Streams가 내부 관리 |

**언제 Kafka Streams를 쓰나:**
- 토픽 A → 집계/필터/변환 → 토픽 B 파이프라인
- 윈도우 기반 카운팅/집계
- 여러 토픽을 조인해서 결과 생성

---

## 2) 핵심 API

### KStream
레코드 단위 스트림. 각 레코드가 독립적으로 처리된다.

```java
KStream<String, String> stream = builder.stream("input-topic");
stream.filter((key, value) -> value.contains("error"))
      .to("error-topic");
```

### KTable
키별 최신 값을 저장하는 테이블. 업데이트 스트림이다.

```java
KTable<String, Long> table = builder.table("user-events");
// 각 키의 최신 값만 유지 (로그 컴팩션과 유사)
```

### GlobalKTable
모든 파티션의 데이터를 각 인스턴스에 완전히 복제한다. 조인에 주로 사용한다.

```java
GlobalKTable<String, String> globalTable = builder.globalTable("reference-data");
```

### 윈도우 연산
```java
stream.groupByKey()
      .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
      .count()
      .toStream()
      .to("windowed-counts");
```

---

## 3) 실습: kafka-streams-study 모듈 추가 + Word Count

### 3-1. settings.gradle에 모듈 추가

```groovy
// settings.gradle
include 'kafka-streams-study'
```

### 3-2. kafka-streams-study/build.gradle 생성

```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
    id 'java'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.apache.kafka:kafka-streams'
}
```

### 3-3. application.yaml

```yaml
server:
  port: 8082

spring:
  kafka:
    bootstrap-servers: localhost:9092
    streams:
      application-id: word-count-app
      properties:
        default.key.serde: org.apache.kafka.common.serialization.Serdes$StringSerde
        default.value.serde: org.apache.kafka.common.serialization.Serdes$StringSerde
```

### 3-4. Word Count Topology 구현

```java
// kafka-streams-study/src/main/java/com/study/streams/WordCountTopology.java
@Configuration
@EnableKafkaStreams
public class WordCountTopology {

    @Bean
    public KStream<String, String> wordCountStream(StreamsBuilder builder) {
        KStream<String, String> inputStream = builder.stream("word-input");

        inputStream
            .flatMapValues(value -> Arrays.asList(value.toLowerCase().split("\\s+")))
            .groupBy((key, word) -> word)
            .count(Materialized.as("word-count-store"))
            .toStream()
            .mapValues(Object::toString)
            .to("word-count-output");

        return inputStream;
    }
}
```

### 3-5. 토픽 생성 및 실행

```bash
# 입력/출력 토픽 생성
docker exec -it kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic word-input --partitions 1 --replication-factor 1

docker exec -it kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --topic word-count-output --partitions 1 --replication-factor 1

# 앱 실행
./gradlew :kafka-streams-study:bootRun
```

### 3-6. 동작 확인

```bash
# 출력 토픽 소비 (터미널 1)
docker exec -it kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic word-count-output \
  --from-beginning \
  --property print.key=true

# 메시지 발행 (터미널 2)
docker exec -it kafka kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic word-input

> hello kafka hello world
> kafka streams kafka
```

출력 예시:
```
hello	2
kafka	2
world	1
kafka	3    ← 두 번째 메시지 처리 후 업데이트
streams	1
```

---

## 4) 상태 저장소(State Store)

Word Count 예제에서 `Materialized.as("word-count-store")`로 RocksDB 기반 상태 저장소를 생성한다.

```java
// Interactive Query로 상태 저장소에서 직접 읽기
@RestController
@RequiredArgsConstructor
public class WordCountQueryController {

    private final KafkaStreams kafkaStreams;

    @GetMapping("/count/{word}")
    public Long getCount(@PathVariable String word) {
        ReadOnlyKeyValueStore<String, Long> store =
            kafkaStreams.store(StoreQueryParameters.fromNameAndType(
                "word-count-store",
                QueryableStoreTypes.keyValueStore()
            ));
        Long count = store.get(word);
        return count != null ? count : 0L;
    }
}
```

```bash
curl http://localhost:8082/count/kafka
# → 3
```

---

## 5) 이 프로젝트에서 Kafka Streams 실습 범위

이 프로젝트에 실제로 `kafka-streams-study` 모듈을 추가하려면 위 3번 절차를 따른다.
간단히 개념만 확인하고 싶으면 `kafka-redis-compare` 모듈 코드를 참고해 구조 파악 후,
`kafka-streams-study`는 별도 실습 모듈로 추가한다.

> Kafka Connect(외부 시스템 연동)는 [17. Kafka Connect 입문](17-kafka-connect-intro.md)에서 다룬다.
