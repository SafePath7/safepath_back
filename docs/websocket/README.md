# WebSocket 사용자·보호자 채널

[한국어](README.md) | [日本語](README.ja.md)

이 문서는 SafePath의 실시간 사용자·보호자 모니터링 채널 구현과 테스트 방법을 설명한다.

## 목적과 범위

WebSocket 모듈은 안전귀가 또는 모니터링 세션에 참여한 사용자와 보호자 사이의 실시간 메시지 중계를 담당한다.

- 세션 서비스가 준비한 사용자 ID와 보호자 ID 목록으로 채널을 연다.
- 허용된 참여자만 해당 채널을 구독할 수 있다.
- 실제로 채널에 입장한 WebSocket 세션만 메시지를 전송할 수 있다.
- WebSocket 연결이 끊겨도 안전귀가 세션이 끝날 때까지 채널은 유지한다.
- 세션 종료 서비스가 채널을 닫는다.

이 모듈은 사용자·보호자 관계 조회, 로그인/JWT, 링크·비밀번호 인증, FCM 발송, 안전귀가 세션 DB 저장을 구현하지 않는다. 해당 기능은 세션/사용자/보호자 담당 모듈이 처리한다.

## 구성 파일

| 파일 | 역할 |
| --- | --- |
| `config/WebSocketConfig.java` | STOMP endpoint(`/ws`), 전송 접두사(`/app`), 구독 접두사(`/topic`) 설정 |
| `config/SecurityConfig.java` | `/ws`, `/ws/**` WebSocket 핸드셰이크 허용 |
| `websocket/InMemoryChannelRegistry.java` | 채널, 허용 참여자, 접속 WebSocket 세션을 서버 메모리에서 관리 |
| `websocket/WebSocketSessionEventListener.java` | STOMP 구독/연결 종료 이벤트를 받아 채널 입장·퇴장을 처리 |
| `websocket/ConnectionMessageController.java` | 채널에 입장한 세션의 메시지를 구독자에게 중계 |
| `websocket/dto/ConnectionMessageRequest.java` | 클라이언트에서 서버로 보내는 메시지 형식 |
| `websocket/dto/ConnectionMessageResponse.java` | 서버가 채널 구독자에게 보내는 메시지 형식 |
| `test/.../InMemoryChannelRegistryTest.java` | 채널·참여자·세션·종료 단위 테스트 |
| `test/.../WebSocketSessionEventListenerTest.java` | STOMP 구독/연결 종료 이벤트 단위 테스트 |

## 메서드와 책임

### `InMemoryChannelRegistry`

| 메서드 | 하는 일 |
| --- | --- |
| `open(Long userId, Set<Long> guardianIds)` | 사용자와 허용 보호자 목록으로 UUID `channelId`를 생성하고, 참여자 정보를 서버 메모리에 저장한다. 세션 시작 서비스가 호출한다. |
| `exists(String channelId)` | 해당 채널이 아직 활성 상태인지 확인한다. |
| `isParticipant(String channelId, Long participantId)` | 사용자 또는 보호자 ID가 이 채널의 허용 참여자인지 확인한다. |
| `join(String channelId, Long participantId, String sessionId)` | 허용 참여자의 WebSocket 세션을 채널에 등록한다. 채널이 없거나 참여자가 아니면 예외를 발생시킨다. |
| `isSessionJoined(String channelId, String sessionId)` | 메시지를 보내는 WebSocket 세션이 실제로 이 채널에 입장했는지 확인한다. |
| `leaveAll(String sessionId)` | WebSocket 연결이 끊긴 세션 ID만 모든 채널에서 제거한다. 채널과 사용자·보호자 참여 권한은 유지한다. |
| `close(String channelId)` | 채널과 채널에 속한 모든 접속 세션 정보를 메모리에서 제거한다. 세션 종료 서비스가 호출한다. |

### WebSocket 이벤트·메시지 처리

| 메서드 | 하는 일 |
| --- | --- |
| `WebSocketSessionEventListener.handleSubscribe(...)` | `/topic/connections/{channelId}` 구독을 감지한다. STOMP `participant-id` 헤더를 읽어 참여 권한을 확인하고 WebSocket 세션을 채널에 등록한다. |
| `WebSocketSessionEventListener.handleDisconnect(...)` | WebSocket 연결 종료를 감지하고 `leaveAll(sessionId)`를 호출한다. |
| `ConnectionMessageController.relay(...)` | `/app/connections/{channelId}/messages`로 들어온 메시지를 처리한다. 채널 존재 여부와 입장한 세션 여부를 확인한 뒤 같은 채널 구독자에게 중계한다. |

## 채널 수명

```text
세션 시작 서비스
  └─ channelRegistry.open(userId, guardianIds)
       └─ channelId 생성, 사용자와 허용 보호자 목록을 메모리에 저장

사용자/보호자 WebSocket 구독
  └─ /topic/connections/{channelId}
       └─ participant-id가 채널 참여자인지 확인 후 WebSocket 세션 등록

메시지 전송
  └─ /app/connections/{channelId}/messages
       └─ 해당 WebSocket 세션이 채널에 입장했는지 확인 후 중계

WebSocket 연결 종료
  └─ leaveAll(sessionId)
       └─ 접속 세션만 제거, 채널과 참여 권한은 유지

세션 종료 서비스
  └─ channelRegistry.close(channelId)
       └─ 채널과 그 채널의 접속 세션 정보를 메모리에서 제거
```

`InMemoryChannelRegistry`는 서버 메모리를 사용한다. 따라서 서버 재시작 시 모든 활성 채널이 사라진다. 현재 로컬 개발/단일 서버 단계에 맞춘 구현이며, 다중 서버 환경에서는 Redis 같은 공유 저장소 또는 메시지 브로커가 필요하다.

## 세션 API와의 연동 계약

채널 생성은 별도 REST API가 아니라 세션 시작 서비스 내부에서 호출한다.

```java
String channelId = channelRegistry.open(userId, guardianIds);
```

매개변수는 다음과 같다.

| 매개변수 | 타입 | 설명 |
| --- | --- | --- |
| `userId` | `Long` | 보호받는 사용자 ID |
| `guardianIds` | `Set<Long>` | 이 세션에서 모니터링이 허용된 보호자 ID 목록 |

세션 서비스는 반환받은 `channelId`를 세션 응답 또는 세션 데이터에 포함해 사용자와 허용 보호자에게 전달해야 한다. 세션 종료 처리에서는 다음을 호출한다.

```java
channelRegistry.close(channelId);
```

현재는 세션 시작/종료 API가 아직 합쳐지지 않았으므로, 정식 코드에 채널 생성 REST API는 없다. 임시 Postman 테스트 API도 커밋 대상에서 제거되어 있다.

## STOMP 주소와 메시지 형식

| 구분 | 값 |
| --- | --- |
| WebSocket endpoint | `ws://localhost:8080/ws` |
| 구독 주소 | `/topic/connections/{channelId}` |
| 전송 주소 | `/app/connections/{channelId}/messages` |
| 구독 헤더 | `participant-id: {Long 사용자 또는 보호자 ID}` |

전송 payload는 다음 형식이다.

```json
{
  "senderId": "1",
  "content": "현재 위치를 공유합니다."
}
```

성공적으로 중계되면 모든 구독자가 아래 형식의 메시지를 받는다.

```json
{
  "connectionId": "{channelId}",
  "senderId": "1",
  "content": "현재 위치를 공유합니다.",
  "sentAt": "2026-11-09T00:00:00Z"
}
```

`senderId`는 현재 메시지 DTO에서 문자열이다. 반면 채널 참여 권한 검증에 사용되는 `participant-id`는 `Long`이다. 예를 들어 사용자 ID가 `1`이라면 전송 payload의 `senderId`는 `"1"`, STOMP 헤더는 `participant-id: 1`로 보낸다.

> 현재 `participant-id` 헤더는 개발용 검증 수단이다. 실서비스에서는 JWT, 웹 링크의 접근 ticket 등 서버가 검증한 인증 정보를 사용해 참여자 ID를 결정해야 한다. 클라이언트가 직접 보낸 ID 헤더를 신뢰해서는 안 된다.

## 로컬 실행 준비

### 1. Docker PostgreSQL 실행

프로젝트 루트에서 `.env.example`을 `.env`로 복사하고, Docker용 비밀번호를 설정한다. `.env`는 Git에 올리지 않는다.

```powershell
Copy-Item .env.example .env
docker compose up -d
docker compose ps
```

`postgres` 서비스가 `healthy`인지 확인한다.

### 2. Spring Boot 실행

IntelliJ 실행 설정 또는 터미널에 다음 환경값을 설정한다.

```text
SPRING_PROFILES_ACTIVE=local
POSTGRES_USER=<.env의 POSTGRES_USER>
POSTGRES_PASSWORD=<.env의 POSTGRES_PASSWORD>
```

PowerShell 예시:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:POSTGRES_USER = "<.env의 POSTGRES_USER>"
$env:POSTGRES_PASSWORD = "<.env의 POSTGRES_PASSWORD>"
.\gradlew.bat bootRun
```

서버 로그에 다음 내용이 보이면 준비가 완료된 것이다.

```text
The following 1 profile is active: "local"
Tomcat started on port 8080
```

## 현재 테스트 방법

### JUnit 전체 테스트

현재 정식 코드만으로는 세션 API가 채널을 열지 않으므로, 채널 관리 검증은 JUnit으로 수행한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:POSTGRES_USER = "<.env의 POSTGRES_USER>"
$env:POSTGRES_PASSWORD = "<.env의 POSTGRES_PASSWORD>"
.\gradlew.bat test
```

성공 시:

```text
BUILD SUCCESSFUL
```

다음 항목을 검증한다.

- 사용자 ID `1`과 보호자 ID `2`, `3`으로 채널 생성
- 사용자/허용 보호자의 채널 참여 허용
- 비허용 사용자 ID `99`의 참여 차단
- WebSocket 세션 종료 시 채널은 유지되고 세션만 제거
- 세션 종료 시 `close(channelId)`로 채널 삭제
- STOMP 구독·연결 종료 이벤트 처리

### Postman/STOMP 통합 테스트

정식 통합 테스트에는 세션 시작 API가 먼저 필요하다. 세션 시작 API가 `channelRegistry.open(1L, Set.of(2L, 3L))`을 호출하고 응답에 `channelId`를 전달하도록 합쳐진 뒤 아래 절차로 테스트한다.

테스트 참여자 데이터:

| 이름 | ID | 기대 결과 |
| --- | ---: | --- |
| `user-1` | `1` | 채널 생성자, 구독/전송 허용 |
| `guardian-1` | `2` | 구독/전송 허용 |
| `guardian-2` | `3` | 구독/전송 허용 |
| `unknown-1` | `99` | 구독/전송 거부 |

1. 세션 시작 REST API를 호출해 `channelId`를 받는다.
2. Postman에서 **New → WebSocket**을 선택하고 `ws://localhost:8080/ws`에 연결한다.
3. 사용자와 보호자용으로 각각 WebSocket 연결을 연다.
4. 각 연결에서 STOMP `CONNECT` 프레임을 보낸다.
5. 사용자 연결은 `participant-id: 1`, 보호자 연결은 `participant-id: 2` 또는 `3`으로 같은 채널을 구독한다.
6. 입장한 연결 중 하나가 메시지를 보내면 같은 채널의 구독자 모두가 `MESSAGE` 프레임을 받는다.
7. `participant-id: 99`로 같은 채널을 구독하거나, 구독하지 않은 세션이 메시지를 보내면 서버가 거부한다.

### Postman Hexadecimal 프레임

Postman WebSocket 메시지 형식을 **Hexadecimal**로 선택한다. STOMP 프레임은 UTF-8 텍스트를 16진수 바이트로 표현한 것이며, 마지막 `00`은 프레임 종료를 뜻하는 NUL 문자다.

| Hex 값 | 의미 |
| --- | --- |
| `0A` | 줄바꿈(`\n`) |
| `0A 0A` | 헤더와 body의 경계 |
| `00` | STOMP 프레임 종료(NUL) |

`CONNECT` 프레임의 의미는 다음과 같다.

```json
{
  "command": "CONNECT",
  "headers": {
    "accept-version": "1.2",
    "host": "localhost"
  },
  "body": null,
  "terminator": "00"
}
```

Postman Hexadecimal 입력값:

```text
43 4F 4E 4E 45 43 54 0A 61 63 63 65 70 74 2D 76 65 72 73 69 6F 6E 3A 31 2E 32 0A 68 6F 73 74 3A 6C 6F 63 61 6C 68 6F 73 74 0A 0A 00
```

사용자 `1`의 구독 프레임은 논리적으로 다음 JSON과 같다.

```json
{
  "command": "SUBSCRIBE",
  "headers": {
    "id": "sub-user-1",
    "destination": "/topic/connections/{channelId}",
    "participant-id": "1"
  },
  "body": null,
  "terminator": "00"
}
```

메시지 전송 프레임은 다음 JSON과 같다.

```json
{
  "command": "SEND",
  "headers": {
    "destination": "/app/connections/{channelId}/messages",
    "content-type": "application/json"
  },
  "body": {
    "senderId": "1",
    "content": "테스트 메시지"
  },
  "terminator": "00"
}
```

`channelId`는 매번 달라지므로 `SUBSCRIBE`와 `SEND`의 Hex 값은 직접 생성해야 한다. PowerShell에서 아래 함수로 STOMP 텍스트 프레임을 Hexadecimal 입력값으로 변환할 수 있다.

```powershell
function ConvertTo-StompHex([string]$frame) {
    ([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($frame))).Replace('-', ' ')
}

$channelId = '<세션 시작 API가 반환한 channelId>'

$subscribe = "SUBSCRIBE`nid:sub-user-1`ndestination:/topic/connections/$channelId`nparticipant-id:1`n`n" + [char]0
ConvertTo-StompHex $subscribe

$send = "SEND`ndestination:/app/connections/$channelId/messages`ncontent-type:application/json`n`n{`"senderId`":`"1`",`"content`":`"테스트 메시지`"}" + [char]0
ConvertTo-StompHex $send
```

변환 결과 전체를 Postman의 Hexadecimal 입력 창에 붙여 넣는다. 성공 시 수신되는 `MESSAGE` 프레임의 body는 다음 JSON 형태다.

```json
{
  "connectionId": "{channelId}",
  "senderId": "1",
  "content": "테스트 메시지",
  "sentAt": "..."
}
```

## 이후 연동 작업

1. 세션 시작 서비스가 승인된 보호자 목록을 조회한 뒤 `open(userId, guardianIds)` 호출
2. 세션 응답/알림에 `channelId` 또는 서버 검증용 접속 ticket 전달
3. 세션 종료 서비스에서 `close(channelId)` 호출
4. JWT 또는 웹 링크 ticket을 STOMP `CONNECT` 단계에서 검증하고, `participant-id` 개발용 헤더를 서버 인증 정보로 대체
5. 다중 서버 배포 시 메모리 레지스트리를 공유 저장소로 교체
