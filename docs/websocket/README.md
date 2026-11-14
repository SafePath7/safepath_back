# WebSocket 위치 공유

[한국어](README.md) | [日本語](README.ja.md)

안전귀가 중 사용자의 위치를 허용된 보호자에게 실시간으로 중계한다. Spring WebSocket/STOMP와 Firebase ID 토큰을 사용한다.

## 권한 모델

- `open(userId, guardianIds)`의 `userId`는 Firebase UID가 아닌 **내부 DB 사용자 ID (`users.id`)** 다.
- `guardianIds`는 해당 사용자와 연결되고 수락된 보호자의 내부 DB ID 집합이다.
- 채널 사용자만 위치를 `SEND`할 수 있다. 보호자는 같은 토픽을 구독해 수신만 한다.
- 클라이언트의 `participant-id` 및 위치 요청의 `senderId`는 사용하지 않는다. 서버가 토큰에서 확인한 사용자 ID를 사용한다.
- 연결이 끊겨도 허용 참가자 목록은 유지되므로 같은 사용자는 토큰으로 재연결할 수 있다. 세션 종료 시에만 채널을 닫는다.

`InMemoryChannelRegistry`는 단일 서버 메모리 저장소다. 서버 재시작 시 채널이 사라지며, 다중 서버에서는 Redis 등의 공유 저장소/브로커로 교체해야 한다.

## 세션 시작·종료 연동

현재 `SafeReturnSessionService`는 채널을 직접 열지 않는다. 안전귀가 세션을 생성하는 서비스 계층에서 보호자 목록을 조회한 뒤 아래 순서로 연동한다.

```java
// userId: 인증된 요청자의 users.id
// guardianIds: 수락된 보호자들의 users.id 집합
Set<Long> guardianIds = guardianService.findAcceptedGuardianIds(userId);
ActiveSession session = new ActiveSession(UUID.randomUUID(), userId, Instant.now());
activeSessionStore.saveSession(session);
String channelId = channelRegistry.open(userId, guardianIds);
```

`channelId`는 프런트가 WebSocket 주소에 사용할 값이므로 세션 응답에 포함하거나, `sessionId → channelId` 매핑으로 보관해 조회 가능해야 한다. `channelId`만으로는 사용자를 인증하지 않으므로 공개해도 되지만, 토큰 없이 입장할 수는 없다.

세션 종료 시에는 소유자 검증과 세션 제거가 성공한 뒤 같은 세션의 채널을 닫는다.

```java
String channelId = sessionChannelStore.remove(sessionId);
if (channelId != null) {
    channelRegistry.close(channelId);
}
```

`sessionChannelStore`는 현재 프로젝트에 아직 없으므로, 활성 세션 저장소/DB에 `channelId`를 함께 저장하거나 별도 매핑으로 구현한다. 서버 재시작 뒤에도 복구해야 하면 DB에 저장한다.

## 프런트 연동

### 1. 안전귀가 세션 시작

사용자 앱은 기존 세션 시작 API를 Firebase ID 토큰과 함께 호출한다.

```text
POST /api/safe-return/sessions
Authorization: Bearer <Firebase ID token>
```

응답에서 `channelId`를 받도록 세션 응답을 확장한 뒤 다음 단계에 사용한다.

### 2. WebSocket/STOMP 연결 및 인증

`ws://<host>/ws`로 연결한 뒤, 각 연결에서 Firebase **ID 토큰**을 STOMP `CONNECT` 헤더로 보낸다. FCM 토큰이나 URL 쿼리 파라미터를 사용하지 않는다.

```text
CONNECT
accept-version:1.2
host:<host>
Authorization:Bearer <Firebase ID token>

\0
```

앱은 최초 연결과 모든 재연결 직전에 Firebase SDK에서 최신 ID 토큰을 가져와야 한다. 서버는 토큰을 검증하고 Firebase UID를 내부 사용자 ID로 변환해 STOMP 세션에 저장한다.

### 3. 위치 구독

사용자와 보호자 모두 같은 주소를 구독한다. `participant-id` 헤더는 넣지 않는다.

```text
SUBSCRIBE
id:location-{channelId}
destination:/topic/connections/{channelId}/locations

\0
```

구독 프레임은 브로커 등록 전에 서버에서 토큰 사용자와 채널 참가 권한을 확인한다. 권한이 없으면 구독되지 않는다.

### 4. 사용자 위치 전송

사용자 앱만 아래 주소로 전송한다.

```text
SEND
destination:/app/connections/{channelId}/locations
content-type:application/json

{"latitude":37.5665,"longitude":126.9780,"accuracyMeters":8.5,"capturedAt":"2026-11-14T12:00:00Z"}
\0
```

요청 DTO:

```json
{
  "latitude": 37.5665,
  "longitude": 126.9780,
  "accuracyMeters": 8.5,
  "capturedAt": "2026-11-14T12:00:00Z"
}
```

| 필드 | 규칙 |
| --- | --- |
| `latitude` | -90 이상 90 이하 |
| `longitude` | -180 이상 180 이하 |
| `accuracyMeters` | 선택값, 0 이상(미터) |
| `capturedAt` | 필수, 앱이 측정한 UTC ISO-8601 시각 |

`senderId`는 요청에 넣지 않는다. 서버는 인증된 세션의 내부 사용자 ID를 응답에 넣어 구독자에게 중계한다.

```json
{
  "connectionId": "{channelId}",
  "senderId": "1",
  "latitude": 37.5665,
  "longitude": 126.978,
  "accuracyMeters": 8.5,
  "capturedAt": "2026-11-14T12:00:00Z",
  "receivedAt": "2026-11-14T12:00:01Z"
}
```

### 5. 재연결과 종료

네트워크가 끊기면 새 WebSocket 연결에서 `CONNECT → SUBSCRIBE`를 다시 수행한다. 같은 Firebase 계정은 같은 내부 사용자 ID로 변환되어 기존 채널에 다시 입장할 수 있다. 세션이 종료되면 프런트는 구독을 해제하고 연결을 닫는다.

## 로컬 Postman 수동 테스트

운영 코드에는 임시 API를 포함하지 않는다. 세션 시작 연동 전 로컬에서만 확인해야 한다면 아래 파일을 **임시로** 추가하고 검증 후 삭제한다.

`src/main/java/com/safepath/backend/websocket/dto/LocalChannelOpenRequest.java`

```java
public record LocalChannelOpenRequest(@NotEmpty Set<@NotNull Long> guardianIds) {}
```

`src/main/java/com/safepath/backend/websocket/dto/LocalChannelOpenResponse.java`

```java
public record LocalChannelOpenResponse(String channelId) {}
```

`src/main/java/com/safepath/backend/websocket/LocalWebSocketTestController.java`

```java
@Profile("local")
@RestController
@RequestMapping("/api/test/websocket/channels")
class LocalWebSocketTestController {
    private final InMemoryChannelRegistry channels;
    private final UserService users;

    LocalWebSocketTestController(InMemoryChannelRegistry channels, UserService users) {
        this.channels = channels;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LocalChannelOpenResponse open(@AuthenticationPrincipal String firebaseUid,
            @Valid @RequestBody LocalChannelOpenRequest request) {
        Long userId = users.findUserIdByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new AccessDeniedException("USER_NOT_REGISTERED"));
        return new LocalChannelOpenResponse(channels.open(userId, request.guardianIds()));
    }
}
```

필요한 import는 `jakarta.validation.*`, `java.util.Set`, Spring Web/Security annotation이다. 이 API도 Firebase ID 토큰을 요구하며, 요청자의 내부 ID를 사용자로 사용한다. `userId`를 요청 body로 받거나 Security 예외를 추가하지 않는다.

1. `local` 프로필로 실행한다.
2. 사용자 Firebase ID 토큰으로 아래 API를 호출한다.

```text
POST http://localhost:8080/api/test/websocket/channels
Authorization: Bearer <사용자 Firebase ID token>
Content-Type: application/json

{"guardianIds":[2]}
```

3. 응답의 `channelId`로 사용자·보호자 각각 WebSocket 연결을 열고 위 `CONNECT`, `SUBSCRIBE` 프레임을 보낸다.
4. 사용자 연결에서 위치 `SEND` 후 보호자 연결의 `MESSAGE`를 확인한다.
5. 검증이 끝나면 위 세 임시 파일을 삭제한다.

Postman에서 NUL 문자 입력이 어렵다면 메시지 형식을 Hexadecimal로 선택한다.

```powershell
function ConvertTo-StompHex([string]$frame) {
    ([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($frame))).Replace('-', ' ')
}
```

## 주요 구현 파일과 테스트

| 파일 | 역할 |
| --- | --- |
| `WebSocketConfig.java` | `/ws`, `/app`, `/topic`, 인바운드 인터셉터 설정 |
| `StompAuthenticationChannelInterceptor.java` | CONNECT 토큰 인증, 세션 ID 저장, 구독 전 채널 인가·입장 |
| `InMemoryChannelRegistry.java` | 채널 참가자, 사용자 위치 송신 권한, WebSocket 세션 관리 |
| `WebSocketSessionEventListener.java` | 연결 종료 시 세션 제거 |
| `LocationUpdateController.java` | 위치 SEND 권한 확인과 토픽 중계 |
| `WebSocketLocationIntegrationTest.java` | 실제 사용자·보호자 WebSocket 연결과 MESSAGE 수신 통합 테스트 |

```powershell
.\gradlew.bat test --tests "com.safepath.backend.websocket.*"
```
