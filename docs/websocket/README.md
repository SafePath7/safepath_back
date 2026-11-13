# WebSocket 위치 공유

[한국어](README.md) | [日本語](README.ja.md)

안전귀가 채널에서 사용자의 위치를 보호자에게 실시간으로 전달한다. Spring WebSocket/STOMP를 사용하며, 인증된 사용자만 채널에 참여할 수 있다.

## 동작과 권한

- 세션 시작 서비스가 `channelRegistry.open(userId, guardianIds)`를 호출해 채널을 연다.
- 채널에는 사용자 한 명과 허용된 보호자만 참여할 수 있다.
- 사용자만 위치를 전송할 수 있고, 보호자는 위치를 구독해 수신한다.
- 연결이 끊기면 WebSocket 세션만 제거된다. 채널과 허용 참가자는 안전귀가 세션이 끝날 때까지 유지되므로, 같은 사용자는 재연결할 수 있다.
- 세션 종료 서비스는 `channelRegistry.close(channelId)`를 호출해 채널을 제거해야 한다.

`InMemoryChannelRegistry`는 서버 메모리를 사용한다. 따라서 서버 재시작 시 열려 있던 채널은 사라진다. 다중 서버 환경에서는 Redis 등 공유 저장소/브로커가 필요하다.

## 인증

WebSocket 핸드셰이크 endpoint는 `/ws`이고, 인증은 이어지는 STOMP `CONNECT` 프레임에서 수행한다.

```text
CONNECT
accept-version:1.2
host:localhost
Authorization:Bearer <Firebase ID token>

\0
```

서버는 Firebase ID 토큰을 검증하고 Firebase UID를 내부 사용자 ID로 변환해 WebSocket 세션에 바인딩한다. 토큰이 없거나 유효하지 않거나 가입되지 않은 사용자는 연결할 수 없다.

`participant-id` 헤더는 사용하지 않는다. `SUBSCRIBE`와 `SEND`의 권한은 클라이언트가 보낸 ID가 아니라 인증된 WebSocket 세션의 사용자 ID로 판단한다.

## STOMP 주소

| 구분 | 주소 |
| --- | --- |
| WebSocket endpoint | `ws://localhost:8080/ws` |
| 위치 전송 | `/app/connections/{channelId}/locations` |
| 위치 구독 | `/topic/connections/{channelId}/locations` |

## 위치 전송 형식

사용자 앱은 `senderId` 없이 위치 데이터만 전송한다.

```json
{
  "latitude": 37.5665,
  "longitude": 126.9780,
  "accuracyMeters": 8.5,
  "capturedAt": "2026-11-14T12:00:00Z"
}
```

| 필드 | 설명 |
| --- | --- |
| `latitude` | 위도. -90 이상 90 이하 |
| `longitude` | 경도. -180 이상 180 이하 |
| `accuracyMeters` | 선택값. 위치 정확도(미터), 0 이상 |
| `capturedAt` | 앱이 위치를 측정한 UTC 시각 |

서버는 인증된 세션의 사용자 ID를 `senderId`로 넣어 같은 채널의 구독자에게 중계한다. 따라서 클라이언트가 다른 사용자인 척 발신자를 위조할 수 없다.

```json
{
  "connectionId": "{channelId}",
  "senderId": "1",
  "latitude": 37.5665,
  "longitude": 126.9780,
  "accuracyMeters": 8.5,
  "capturedAt": "2026-11-14T12:00:00Z",
  "receivedAt": "2026-11-14T12:00:01Z"
}
```

## 주요 파일

| 파일 | 역할 |
| --- | --- |
| `config/WebSocketConfig.java` | STOMP endpoint, `/app`, `/topic` 및 인증 인터셉터 설정 |
| `websocket/StompAuthenticationChannelInterceptor.java` | `CONNECT`의 Firebase ID 토큰 검증 및 세션 사용자 바인딩 |
| `websocket/InMemoryChannelRegistry.java` | 채널, 허용 참가자, 위치 송신자 및 접속 세션 관리 |
| `websocket/WebSocketSessionEventListener.java` | 구독/연결 종료 이벤트에서 입장·퇴장 처리 |
| `websocket/LocationUpdateController.java` | 위치 송신 권한 확인 및 위치 중계 |
| `websocket/dto/LocationUpdateRequest.java` | 앱 → 서버 위치 요청 DTO |
| `websocket/dto/LocationUpdateResponse.java` | 서버 → 구독자 위치 응답 DTO |

## 테스트

WebSocket 단위 테스트는 다음 명령으로 실행한다.

```powershell
.\gradlew.bat test --tests "com.safepath.backend.websocket.*"
```

Postman으로 실제 송수신을 확인하려면 먼저 세션 시작 로직에서 채널을 열어 유효한 `channelId`를 확보해야 한다. 이후 각 연결은 Firebase ID 토큰으로 `CONNECT`하고, 사용자와 보호자 모두 위치 토픽을 구독한다. 위치 `SEND`는 채널을 연 사용자만 성공한다.
