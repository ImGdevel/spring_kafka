# notification-contract

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 목적

`notification-contract`는 Producer와 Worker가 Kafka로 주고받는 메시지 계약을 정의한다.  
이 모듈은 통신 포맷과 토픽/헤더 이름을 고정하는 역할을 맡는다.

## 현재 포함 요소

- 이벤트 DTO
  - `NotificationRequestedEvent`
  - `NotificationSentEvent`
  - `NotificationFailedEvent`
- Kafka 계약 상수
  - `NotificationTopics`
  - `NotificationHeaderNames`

## 토픽 계약

- `notification.requested`
  - Producer가 발행하는 요청 이벤트
- `notification.sent`
  - Worker가 성공 시 발행하는 결과 이벤트
- `notification.failed`
  - Worker가 실패 시 발행하는 결과 이벤트
- `notification.requested.dlt`
  - broker 수준 DLT 또는 수동 재처리 보관용 토픽

## 헤더 계약

- `trace-id`
  - 요청 단위 추적 식별자
- `notification-id`
  - 알림 자체 식별자
- `channel`
  - `EMAIL`, `SLACK`

## 이벤트 스키마 방향

`NotificationRequestedEvent`

- `notificationId`
- `traceId`
- `channel`
- `recipient`
- `subject`
- `body`
- `templateCode`

`NotificationSentEvent`

- `notificationId`
- `traceId`
- `channel`
- `provider`

`NotificationFailedEvent`

- `notificationId`
- `traceId`
- `channel`
- `reason`

## 의존 규칙

- `notification-domain`에는 의존할 수 있다
- `notification-provider-sandbox`에는 의존하지 않는다
- Spring Kafka 설정이나 직렬화 설정은 넣지 않는다

## 버전 관리 원칙

- 이벤트 필드 변경은 호환성 검토 후 진행한다
- 토픽 이름은 운영 흐름이 바뀌지 않는 한 변경하지 않는다
- 헤더 이름은 Producer와 Worker가 동시에 이해할 수 있게 유지한다

## 리뷰 체크리스트

- 이벤트 DTO에 비즈니스 정책이 들어가 있지 않은가
- 토픽/헤더 이름이 앱 문서와 일치하는가
- 전송 결과와 실패 결과의 책임이 분리되어 있는가
