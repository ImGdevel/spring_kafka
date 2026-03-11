# notification-provider-sandbox

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 목적

`notification-provider-sandbox`는 Worker가 사용할 실습용 채널 구현 모듈이다.  
실제 Email/Slack 외부 연동 대신, 로컬에서 성공과 실패를 재현할 수 있는 가짜 Provider를 둔다.

## 현재 포함 요소

- `SandboxEmailSender`
- `SandboxSlackSender`

현재 구현 상태:

- 스텁 수준
- 실제 외부 API 호출 없음
- 성공 반환 형태의 최소 골격만 존재

## 책임

- 채널별 전송기 클래스 제공
- Worker가 채널 선택 로직을 붙일 수 있는 진입점 제공
- 이후 실패율, 지연, 부분 실패 규칙을 붙일 수 있는 실험 공간 제공

## 예정 동작

`SandboxEmailSender`

- 성공 응답
- 지정 비율 실패
- 지정 시간 지연

`SandboxSlackSender`

- 성공 응답
- 지정 비율 실패
- 짧은 지연

## 설정 계약 초안

```yaml
app:
  notification:
    sandbox:
      email:
        failure-rate: 0.1
        delay-millis: 150
      slack:
        failure-rate: 0.2
        delay-millis: 50
```

## 의존 규칙

- `notification-domain`에만 의존한다
- Kafka 토픽/헤더 개념은 직접 알지 않는다
- Producer 앱에서는 사용하지 않는다

## 리뷰 체크리스트

- 외부 API 키나 실 연동 코드가 들어가 있지 않은가
- Worker 전용 모듈이라는 경계가 유지되는가
- 실패 주입 규칙이 도메인 모듈에 새지 않는가
