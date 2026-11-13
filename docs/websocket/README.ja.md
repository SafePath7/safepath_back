# WebSocket 位置共有

[한국어](README.md) | [日本語](README.ja.md)

安全帰宅チャネルで、ユーザーの位置を保護者へリアルタイムに配信する。Spring WebSocket/STOMP を使用し、認証済みの参加者だけがチャネルに入室できる。

## 動作と権限

- セッション開始サービスが `channelRegistry.open(userId, guardianIds)` を呼び出してチャネルを開く。
- チャネルにはユーザー 1 人と許可された保護者だけが参加できる。
- 位置を送信できるのはユーザーだけで、保護者は位置を購読して受信する。
- 接続が切れた場合は WebSocket セッションだけを削除する。安全帰宅セッションが終了するまでチャネルと参加権限は維持されるため、同じユーザーは再接続できる。
- セッション終了サービスは `channelRegistry.close(channelId)` を呼び出してチャネルを削除する必要がある。

`InMemoryChannelRegistry` はサーバーメモリーを使用する。サーバー再起動時には開いていたチャネルが失われるため、複数サーバー環境では Redis などの共有ストア/ブローカーが必要である。

## 認証

WebSocket ハンドシェイク endpoint は `/ws` であり、認証は続く STOMP `CONNECT` フレームで行う。

```text
CONNECT
accept-version:1.2
host:localhost
Authorization:Bearer <Firebase ID token>

\0
```

サーバーは Firebase ID トークンを検証し、Firebase UID を内部ユーザー ID に変換して WebSocket セッションへ紐付ける。トークンがない、無効である、または未登録のユーザーは接続できない。

`participant-id` ヘッダーは使用しない。`SUBSCRIBE` と `SEND` の認可はクライアントが送った ID ではなく、認証済み WebSocket セッションのユーザー ID で判断する。

## STOMP アドレス

| 区分 | アドレス |
| --- | --- |
| WebSocket endpoint | `ws://localhost:8080/ws` |
| 位置送信 | `/app/connections/{channelId}/locations` |
| 位置購読 | `/topic/connections/{channelId}/locations` |

## 位置送信形式

ユーザーアプリは `senderId` を送らず、位置データだけを送信する。

```json
{
  "latitude": 37.5665,
  "longitude": 126.9780,
  "accuracyMeters": 8.5,
  "capturedAt": "2026-11-14T12:00:00Z"
}
```

| フィールド | 説明 |
| --- | --- |
| `latitude` | 緯度。-90 以上 90 以下 |
| `longitude` | 経度。-180 以上 180 以下 |
| `accuracyMeters` | 任意。位置精度（メートル）、0 以上 |
| `capturedAt` | アプリが位置を取得した UTC 時刻 |

サーバーは認証済みセッションのユーザー ID を `senderId` として追加し、同じチャネルの購読者へ中継する。クライアントが別ユーザーとして発信者を偽装することはできない。

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

## 主なファイル

| ファイル | 役割 |
| --- | --- |
| `config/WebSocketConfig.java` | STOMP endpoint、`/app`、`/topic` と認証インターセプターの設定 |
| `websocket/StompAuthenticationChannelInterceptor.java` | `CONNECT` の Firebase ID トークン検証とセッションへのユーザー紐付け |
| `websocket/InMemoryChannelRegistry.java` | チャネル、許可参加者、位置送信者、接続セッションの管理 |
| `websocket/WebSocketSessionEventListener.java` | 購読/接続終了イベントでの入室・退室処理 |
| `websocket/LocationUpdateController.java` | 位置送信権限の確認と位置中継 |
| `websocket/dto/LocationUpdateRequest.java` | アプリ → サーバーの位置リクエスト DTO |
| `websocket/dto/LocationUpdateResponse.java` | サーバー → 購読者の位置レスポンス DTO |

## テスト

WebSocket の単体テストは次のコマンドで実行する。

```powershell
.\gradlew.bat test --tests "com.safepath.backend.websocket.*"
```

Postman で実際の送受信を確認するには、先にセッション開始ロジックでチャネルを開き、有効な `channelId` を用意する必要がある。その後、各接続は Firebase ID トークンで `CONNECT` し、ユーザーと保護者の両方が位置トピックを購読する。位置の `SEND` はチャネルを開いたユーザーだけが成功する。
