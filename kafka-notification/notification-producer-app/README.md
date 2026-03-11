# notification-producer-app

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 목적

`notification-producer-app`은 외부 요청을 받아 Kafka에 알림 요청 이벤트를 적재하는 진입 앱이다.

## 책임

- HTTP 요청 수신
- 요청 유효성 검증
- `NotificationRequestedEvent` 생성
- `notification.requested` 토픽 발행
- `trace-id`, `notification-id` 생성과 헤더 설정

## 하지 않을 일

- 실제 Email/Slack 전송
- 재시도 수행
- 실패 이벤트 발행
- DLT 처리

## API 계약 초안

엔드포인트:

- `POST /api/notifications`

요청 예시:

```json
{
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "subject": "welcome",
  "body": "hello",
  "templateCode": "WELCOME"
}
```

검증 규칙 초안:

- `channel`은 `EMAIL`, `SLACK`만 허용
- `recipient`는 비어 있으면 안 됨
- `body`는 비어 있으면 안 됨
- `subject`는 EMAIL에서만 필수로 확장 가능

응답 방향:

- 기본 응답은 `202 Accepted`
- 실제 전송 성공 여부는 즉시 반환하지 않음

## 사용 모듈

- `notification-domain`
- `notification-contract`

## 런타임 흐름

1. 클라이언트 요청 수신
2. 요청 검증
3. `NotificationRequestedEvent` 생성
4. 헤더 구성
5. Kafka 발행
6. `202 Accepted` 반환

## 설정 계약 초안

```yaml
app:
  notification:
    producer:
      allowed-channels:
        - EMAIL
        - SLACK
```

## 리뷰 체크리스트

- Producer가 실제 전송 책임을 들고 있지 않은가
- 응답이 동기 전송 결과를 의미하지 않는가
- contract 모듈의 토픽/헤더 계약을 그대로 따르는가
