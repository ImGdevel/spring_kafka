# notification-domain

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 목적

`notification-domain`은 kafka-notification 실습의 가장 안쪽 모듈이다.  
알림 비즈니스 개념만 담고, Kafka나 Spring 같은 기술 프레임워크에는 의존하지 않는다.

## 현재 포함 요소

- `NotificationChannel`
  - v1 채널 범위: `EMAIL`, `SLACK`
- `NotificationTarget`
  - 수신 대상 식별자
- `NotificationContent`
  - 제목과 본문
- `NotificationTemplate`
  - 템플릿 코드
- `NotificationSendResult`
  - 전송 결과 표현
- `NotificationSender`
  - 채널 구현이 따라야 하는 인터페이스

## 책임

- 알림 전송에 필요한 순수 도메인 타입 정의
- Provider 구현이 따를 최소 인터페이스 정의
- 다른 모듈이 공유할 수 있는 비즈니스 언어 통일

## 의존 규칙

- Spring Boot 의존 금지
- Kafka 의존 금지
- Web/HTTP 의존 금지
- `notification-contract`를 참조하지 않는다

## 패키지 구조

```text
com.study.notification.domain
```

## 사용 방식

- `notification-producer-app`
  - 요청 DTO를 도메인 개념으로 변환할 때 사용
- `notification-worker-app`
  - 소비한 이벤트를 실제 전송 모델로 해석할 때 사용
- `notification-provider-sandbox`
  - `NotificationSender` 구현 시 사용

## 확장 규칙

- 새 채널을 추가할 때는 먼저 `NotificationChannel`을 확장한다
- 도메인 타입은 전송 기술 세부사항 없이 유지한다
- 템플릿 렌더링 결과와 Kafka 헤더 정보는 이 모듈에 넣지 않는다

## 리뷰 체크리스트

- 도메인 타입이 Kafka payload와 섞이지 않았는가
- 구현 세부사항이 인터페이스에 새어 나오지 않는가
- v1 범위 밖 채널이 추가되지 않았는가
