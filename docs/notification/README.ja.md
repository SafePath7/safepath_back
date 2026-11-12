# 通知モジュール

Firebase Cloud Messaging（FCM）を利用し、ユーザーと保護者へプッシュ通知を送信します。

## 構成

- `NotificationController`: 外部向け通知 API を提供
- `NotificationService`: FCM トークン管理と内部通知の送信
- `FirebaseAuthenticationFilter`: Firebase ID トークンを検証し、リクエスト元の Firebase UID を Spring Security の認証情報に保存

FCM の初期化は `config/FirebaseConfig` が担当します。`GOOGLE_APPLICATION_CREDENTIALS` には Firebase サービスアカウント JSON ファイルのパスを設定する必要があります。

## 外部 API

すべての API には Firebase ID トークンが必要です。

```http
Authorization: Bearer <Firebase ID token>
```

### FCM トークンの更新

機種変更、アプリ再インストール、アプリデータ初期化、`onNewToken()` の呼び出し、またはログイン時に現在の端末の FCM トークンを更新します。

```http
PUT /api/notifications/fcm-token
Content-Type: application/json

{
  "fcmToken": "現在の端末の_FCM_トークン"
}
```

- 成功: `204 No Content`
- ローカル DB にユーザーが存在しない: `404 Not Found`

### SOS 通知

ログイン中のユーザーに紐づく `ACCEPTED` 状態の保護者へ、緊急 SOS 通知を送信します。

```http
POST /api/notifications/sos
```

成功時は `202 Accepted` と送信結果を返します。

```json
{
  "successCount": 2,
  "failureCount": 0
}
```

## 内部メソッド

他ドメインのサービスは、以下のメソッドを呼び出して通知を送信します。通知送信用の公開 API は追加しません。

| メソッド | 呼び出すタイミング | 宛先 |
| --- | --- | --- |
| `registerFcmToken(userId, fcmToken)` | 会員登録・プロフィール保存の直後 | ユーザー本人 |
| `updateFcmToken(firebaseUid, fcmToken)` | FCM トークン更新 API | ユーザー本人 |
| `sendGuardianRequestNotification(requesterId, guardianId)` | 保護者連携リクエストを DB に保存した直後 | リクエストを受けた保護者 |
| `sendDeletionReminderNotification(userId)` | メディア自動削除の 3 日前に実行するスケジューラ | ユーザー本人 |
| `notifyRiskEventUpdate(userId, eventType)` | リスクイベントの保存・更新直後 | 承認済み保護者 |

`sendGuardianRequestNotification`、`sendDeletionReminderNotification`、`notifyRiskEventUpdate` は、送信失敗を結果値として返します。そのため FCM 障害によって、すでに保存済みのリクエスト・イベント・削除処理がロールバックされることはありません。

## アプリ連携

Android アプリはログイン後、および `FirebaseMessagingService.onNewToken()` 実行時に FCM トークンを取得し、`PUT /api/notifications/fcm-token` に送信する必要があります。

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    // Firebase ID トークンを Authorization ヘッダーに設定してサーバーへ送信
}

override fun onNewToken(token: String) {
    // 新しい FCM トークンをサーバーへ送信
}
```

## テスト

単体テスト:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.notification.NotificationControllerTest"
```

実際のプッシュ通知受信テストには、保護者端末で発行された実際の FCM トークン、`ACCEPTED` 状態の保護者関係、および同じ Firebase プロジェクトのサービスアカウントキーが必要です。
