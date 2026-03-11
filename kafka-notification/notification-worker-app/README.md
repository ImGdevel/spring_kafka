# notification-worker-app

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 목적

`notification-worker-app`은 Kafka의 알림 요청 이벤트를 소비해 실제 채널 전송을 수행하는 소비 앱이다.

## 책임

- `notification.requested` 토픽 소비
- 이벤트를 도메인 모델로 해석
- 채널별 Provider 선택
- 전송 시도
- 성공 시 `notification.sent` 이벤트 발행
- 실패 시 재시도 후 `notification.failed` 이벤트 발행

## 하지 않을 일

- 외부 사용자용 API 제공
- Producer 역할 수행

## 사용 모듈

- `notification-domain`
- `notification-contract`
- `notification-provider-sandbox`

## 처리 흐름

1. `NotificationRequestedEvent` 소비
2. `channel` 기준으로 Sender 선택
3. Sender 호출
4. 성공 시 `NotificationSentEvent` 발행
5. 실패 시 1회 재시도
6. 재시도 후에도 실패하면 `NotificationFailedEvent` 발행
7. 필요 시 broker DLT 활용

## Provider 선택 규칙

- `EMAIL` -> `SandboxEmailSender`
- `SLACK` -> `SandboxSlackSender`

## 재시도 정책 초안

- 기본 재시도 횟수: 1회
- 백오프: 1000ms
- 재시도 초과 시 실패 이벤트 발행
- 장기 재처리는 후속 단계에서 별도 admin 또는 batch로 분리

## 설정 계약 초안

```yaml
app:
  notification:
    worker:
      concurrency: 3
      retry-on-failure: true
    retry:
      max-attempts: 2
      backoff-millis: 1000
```

## 리뷰 체크리스트

- Worker가 HTTP 엔드포인트를 노출하지 않는가
- 채널 선택 규칙이 domain이나 contract에 섞이지 않는가
- 성공/실패 이벤트 발행 기준이 명확한가
- DLT와 실패 이벤트의 역할이 혼동되지 않는가
