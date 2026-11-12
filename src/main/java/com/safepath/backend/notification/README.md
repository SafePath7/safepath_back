# 알림 모듈

Firebase Cloud Messaging(FCM)을 통해 사용자와 보호자에게 푸시 알림을 전송한다.

## 구성

- `NotificationController`: 외부 알림 API 제공
- `NotificationService`: FCM 토큰 관리 및 내부 알림 발송
- `FirebaseAuthenticationFilter`: Firebase ID 토큰을 검증해 요청자의 Firebase UID를 Spring Security 인증 정보에 저장

FCM 초기화는 `config/FirebaseConfig`가 담당한다. `GOOGLE_APPLICATION_CREDENTIALS`에는 Firebase 서비스 계정 JSON 파일의 경로가 설정되어야 한다.

## 외부 API

모든 API는 Firebase ID 토큰이 필요하다.

```http
Authorization: Bearer <Firebase ID token>
```

### FCM 토큰 갱신

기기 변경, 앱 재설치, 앱 데이터 초기화, `onNewToken()` 호출 또는 로그인 시 현재 기기의 FCM 토큰을 갱신한다.

```http
PUT /api/notifications/fcm-token
Content-Type: application/json

{
  "fcmToken": "현재_기기의_FCM_토큰"
}
```

- 성공: `204 No Content`
- 로컬 DB 사용자가 없음: `404 Not Found`

### SOS 알림

로그인 사용자의 `ACCEPTED` 보호자에게 긴급 SOS 알림을 전송한다.

```http
POST /api/notifications/sos
```

성공 시 `202 Accepted`와 전송 결과를 반환한다.

```json
{
  "successCount": 2,
  "failureCount": 0
}
```

## 내부 함수

다른 도메인 서비스는 아래 메서드를 호출해 알림을 보낸다. 알림 전송용 공개 API를 추가하지 않는다.

| 메서드 | 호출 시점 | 대상 |
| --- | --- | --- |
| `registerFcmToken(userId, fcmToken)` | 회원가입·내 정보 저장 직후 | 사용자 본인 |
| `updateFcmToken(firebaseUid, fcmToken)` | FCM 토큰 갱신 API | 사용자 본인 |
| `sendGuardianRequestNotification(requesterId, guardianId)` | 보호자 연결 요청 DB 저장 직후 | 요청받은 보호자 |
| `sendDeletionReminderNotification(userId)` | 미디어 자동 삭제 3일 전 스케줄러 | 사용자 본인 |
| `notifyGuardianOfRouteDeviation(userId)` | 경로 이탈 위험 이벤트 상태 저장 직후 | 수락된 보호자 |

`sendGuardianRequestNotification`, `sendDeletionReminderNotification`, `notifyGuardianOfRouteDeviation`는 전송 실패를 결과값으로 반환한다. 따라서 FCM 장애가 이미 저장된 요청·이벤트·삭제 작업을 롤백하지 않는다.

## 앱 연동

Android 앱은 로그인 후 및 `FirebaseMessagingService.onNewToken()`에서 FCM 토큰을 받아 `PUT /api/notifications/fcm-token`으로 전송해야 한다.

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    // Firebase ID 토큰을 Authorization 헤더에 넣어 서버로 전송
}

override fun onNewToken(token: String) {
    // 서버에 새 FCM 토큰 전송
}
```

## 테스트

단위 테스트:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.notification.NotificationControllerTest"
```

실제 푸시 수신 테스트에는 보호자 기기에서 발급된 실제 FCM 토큰, `ACCEPTED` 보호자 관계, 동일 Firebase 프로젝트의 서비스 계정 키가 필요하다.
