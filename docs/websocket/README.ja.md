# WebSocket 位置共有

[한국어](README.md) | [日本語](README.ja.md)

安全帰宅中のユーザー位置を許可済み保護者へリアルタイム配信する。Spring WebSocket/STOMP と Firebase ID トークンを使用する。

## 権限とセッション連携

- `open(userId, guardianIds)` の値は Firebase UID ではなく、`users.id` の内部 DB ID である。
- `userId` は位置を送信する安全帰宅ユーザー、`guardianIds` は受諾済み保護者の内部 ID 集合である。
- ユーザーだけが位置を送信でき、保護者は購読して受信する。
- セッション開始サービスで保護者一覧を取得した後、`channelRegistry.open(userId, guardianIds)` を呼び出す。
- 返された `channelId` をセッション応答または `sessionId → channelId` の保存先に保持する。セッション終了時にはその ID で `channelRegistry.close(channelId)` を呼び出す。

`InMemoryChannelRegistry` は単一サーバーのメモリーを使用する。サーバー再起動時にチャネルは失われ、複数サーバーでは Redis などの共有ストア/ブローカーが必要になる。

## フロントエンドの手順

1. `POST /api/safe-return/sessions` に Firebase ID トークンを付けてセッションを開始し、応答の `channelId` を取得する。
2. `ws://<host>/ws` へ接続し、各 WebSocket で次の STOMP `CONNECT` を送る。FCM トークンや URL クエリーは使用しない。

```text
CONNECT
accept-version:1.2
host:<host>
Authorization:Bearer <Firebase ID token>

\0
```

再接続時も Firebase SDK から最新 ID トークンを取得し、`CONNECT → SUBSCRIBE` を再実行する。

3. ユーザーと保護者は同じ位置トピックを購読する。`participant-id` は送らない。

```text
SUBSCRIBE
id:location-{channelId}
destination:/topic/connections/{channelId}/locations

\0
```

4. ユーザーだけが位置を送信する。

```text
SEND
destination:/app/connections/{channelId}/locations
content-type:application/json

{"latitude":37.5665,"longitude":126.9780,"accuracyMeters":8.5,"capturedAt":"2026-11-14T12:00:00Z"}
\0
```

リクエストに `senderId` は含めない。サーバーが認証済みセッションの内部ユーザー ID を `senderId` として付加する。

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

緯度は -90〜90、経度は -180〜180、`accuracyMeters` は任意で 0 以上、`capturedAt` は必須の UTC ISO-8601 時刻である。

## ローカル Postman テスト

セッション開始連携前の手動テストだけは、韓国語版 README の「ローカル Postman 手動テスト」にある 3 ファイルを `@Profile("local")` で一時追加できる。認証済みリクエストのユーザー ID を使用し、body に `userId` を受け取らない。API で `channelId` を取得後、ユーザー・保護者それぞれで `CONNECT`、`SUBSCRIBE`、ユーザーだけで位置 `SEND` を実行する。検証後、その一時ファイルは必ず削除する。

Postman で NUL を入力できない場合は Hexadecimal を選択する。

```powershell
function ConvertTo-StompHex([string]$frame) {
    ([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($frame))).Replace('-', ' ')
}
```

## 実装とテスト

| ファイル | 役割 |
| --- | --- |
| `WebSocketConfig.java` | `/ws`、`/app`、`/topic` とインバウンドインターセプター設定 |
| `StompAuthenticationChannelInterceptor.java` | CONNECT トークン認証、セッションへの ID 保存、購読前の認可・入室 |
| `InMemoryChannelRegistry.java` | チャネル参加者、位置送信権限、WebSocket セッション管理 |
| `LocationUpdateController.java` | 位置送信の権限確認とトピック中継 |
| `WebSocketLocationIntegrationTest.java` | 実際のユーザー・保護者接続で MESSAGE 受信を検証 |

```powershell
.\gradlew.bat test --tests "com.safepath.backend.websocket.*"
```
