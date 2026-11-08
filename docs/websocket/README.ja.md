# WebSocket ユーザー・保護者チャンネル

[한국어](README.md) | [日本語](README.ja.md)

この文書では、SafePath のリアルタイムなユーザー・保護者モニタリングチャンネルの実装とテスト方法を説明します。

## 目的と範囲

WebSocket モジュールは、安全帰宅またはモニタリングセッションの参加者であるユーザーと保護者の間で、リアルタイムメッセージを中継します。

- セッションサービスが用意したユーザー ID と保護者 ID の一覧でチャンネルを開きます。
- 許可された参加者だけがそのチャンネルを購読できます。
- 実際にチャンネルへ入室した WebSocket セッションだけがメッセージを送信できます。
- WebSocket 接続が切れても、安全帰宅セッションが終了するまではチャンネルを保持します。
- セッション終了サービスがチャンネルを閉じます。

このモジュールは、ユーザー・保護者関係の検索、ログイン/JWT、リンク・パスワード認証、FCM 送信、安全帰宅セッションの DB 保存を実装しません。これらはセッション、ユーザー、保護者の各モジュールが担当します。

## 構成ファイル

| ファイル | 役割 |
| --- | --- |
| `config/WebSocketConfig.java` | STOMP endpoint(`/ws`)、送信接頭辞(`/app`)、購読接頭辞(`/topic`)の設定 |
| `config/SecurityConfig.java` | `/ws`、`/ws/**` の WebSocket ハンドシェイクを許可 |
| `websocket/InMemoryChannelRegistry.java` | チャンネル、許可参加者、接続中 WebSocket セッションをサーバーメモリで管理 |
| `websocket/WebSocketSessionEventListener.java` | STOMP 購読/接続終了イベントを受け、チャンネル入退室を処理 |
| `websocket/ConnectionMessageController.java` | 入室済みセッションのメッセージを購読者へ中継 |
| `websocket/dto/ConnectionMessageRequest.java` | クライアントからサーバーへ送るメッセージ形式 |
| `websocket/dto/ConnectionMessageResponse.java` | サーバーがチャンネル購読者へ送るメッセージ形式 |
| `test/.../InMemoryChannelRegistryTest.java` | チャンネル・参加者・セッション・終了の単体テスト |
| `test/.../WebSocketSessionEventListenerTest.java` | STOMP 購読/接続終了イベントの単体テスト |

## メソッドと責務

### `InMemoryChannelRegistry`

| メソッド | 役割 |
| --- | --- |
| `open(Long userId, Set<Long> guardianIds)` | ユーザーと許可された保護者一覧から UUID の `channelId` を生成し、参加者情報をサーバーメモリに保存する。セッション開始サービスが呼び出す。 |
| `exists(String channelId)` | チャンネルがまだ有効か確認する。 |
| `isParticipant(String channelId, Long participantId)` | ユーザーまたは保護者 ID がチャンネルの許可参加者か確認する。 |
| `join(String channelId, Long participantId, String sessionId)` | 許可された参加者の WebSocket セッションをチャンネルに登録する。チャンネルがない、または参加者でない場合は例外を発生させる。 |
| `isSessionJoined(String channelId, String sessionId)` | 送信元 WebSocket セッションが実際にチャンネルへ入室済みか確認する。 |
| `leaveAll(String sessionId)` | 接続が切れた WebSocket セッション ID だけを全チャンネルから削除する。チャンネルとユーザー・保護者の参加権限は維持する。 |
| `close(String channelId)` | チャンネルと、そのチャンネルに属する接続セッション情報をメモリから削除する。セッション終了サービスが呼び出す。 |

### WebSocket イベント・メッセージ処理

| メソッド | 役割 |
| --- | --- |
| `WebSocketSessionEventListener.handleSubscribe(...)` | `/topic/connections/{channelId}` の購読を検知する。STOMP の `participant-id` ヘッダーで参加権限を確認し、WebSocket セッションをチャンネルに登録する。 |
| `WebSocketSessionEventListener.handleDisconnect(...)` | WebSocket 接続終了を検知し、`leaveAll(sessionId)` を呼び出す。 |
| `ConnectionMessageController.relay(...)` | `/app/connections/{channelId}/messages` へ届いたメッセージを処理する。チャンネルの存在と入室済みセッションを確認後、同じチャンネルの購読者へ中継する。 |

## チャンネルのライフサイクル

```text
セッション開始サービス
  └─ channelRegistry.open(userId, guardianIds)
       └─ channelId を生成し、ユーザーと許可された保護者一覧をメモリへ保存

ユーザー/保護者の WebSocket 購読
  └─ /topic/connections/{channelId}
       └─ participant-id がチャンネル参加者か確認して WebSocket セッションを登録

メッセージ送信
  └─ /app/connections/{channelId}/messages
       └─ その WebSocket セッションがチャンネルに入室済みか確認して中継

WebSocket 接続終了
  └─ leaveAll(sessionId)
       └─ 接続セッションだけを削除し、チャンネルと参加権限は維持

セッション終了サービス
  └─ channelRegistry.close(channelId)
       └─ チャンネルと接続セッション情報をメモリから削除
```

`InMemoryChannelRegistry` はサーバーメモリを使います。そのためサーバー再起動時にはすべての有効なチャンネルが失われます。現在はローカル開発/単一サーバー向けの実装です。複数サーバー環境では Redis などの共有ストアまたはメッセージブローカーが必要です。

## セッション API との連携契約

チャンネル生成は別 REST API ではなく、セッション開始サービス内部から呼び出します。

```java
String channelId = channelRegistry.open(userId, guardianIds);
```

引数は次のとおりです。

| 引数 | 型 | 説明 |
| --- | --- | --- |
| `userId` | `Long` | 保護されるユーザー ID |
| `guardianIds` | `Set<Long>` | このセッションでモニタリングを許可された保護者 ID の一覧 |

セッションサービスは戻り値の `channelId` をセッションレスポンスまたはセッションデータに含め、ユーザーと許可された保護者に渡す必要があります。セッション終了時には次を呼び出します。

```java
channelRegistry.close(channelId);
```

セッション開始/終了 API はまだ統合されていないため、正式コードにはチャンネル生成用 REST API はありません。臨時の Postman テスト API もコミット対象から削除済みです。

## STOMP アドレスとメッセージ形式

| 区分 | 値 |
| --- | --- |
| WebSocket endpoint | `ws://localhost:8080/ws` |
| 購読アドレス | `/topic/connections/{channelId}` |
| 送信アドレス | `/app/connections/{channelId}/messages` |
| 購読ヘッダー | `participant-id: {Long のユーザーまたは保護者 ID}` |

送信 payload:

```json
{
  "senderId": "1",
  "content": "現在位置を共有します。"
}
```

中継に成功すると、すべての購読者は次の形式のメッセージを受信します。

```json
{
  "connectionId": "{channelId}",
  "senderId": "1",
  "content": "現在位置を共有します。",
  "sentAt": "2026-11-09T00:00:00Z"
}
```

現在、メッセージ DTO の `senderId` は文字列ですが、参加権限の検証に使う `participant-id` は `Long` です。ユーザー ID が `1` の場合、payload の `senderId` は `"1"`、STOMP ヘッダーは `participant-id: 1` です。

> 現在の `participant-id` ヘッダーは開発用の検証手段です。本番では JWT または Web リンクのアクセストークンなど、サーバーが検証した認証情報から参加者 ID を決定してください。クライアントが送った ID ヘッダーをそのまま信頼してはいけません。

## ローカル実行の準備

### 1. Docker PostgreSQL の起動

プロジェクトルートで `.env.example` を `.env` へコピーし、Docker 用パスワードを設定します。`.env` は Git に追加しません。

```powershell
Copy-Item .env.example .env
docker compose up -d
docker compose ps
```

`postgres` サービスが `healthy` であることを確認します。

### 2. Spring Boot の実行

IntelliJ の実行設定、またはターミナルで次の環境変数を設定します。

```text
SPRING_PROFILES_ACTIVE=local
POSTGRES_USER=<.env の POSTGRES_USER>
POSTGRES_PASSWORD=<.env の POSTGRES_PASSWORD>
```

PowerShell の例:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:POSTGRES_USER = "<.env の POSTGRES_USER>"
$env:POSTGRES_PASSWORD = "<.env の POSTGRES_PASSWORD>"
.\gradlew.bat bootRun
```

サーバーログに次が表示されれば準備完了です。

```text
The following 1 profile is active: "local"
Tomcat started on port 8080
```

## 現在のテスト方法

### JUnit 全体テスト

正式コードだけではセッション API がチャンネルを開かないため、チャンネル管理の検証は JUnit で行います。

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:POSTGRES_USER = "<.env の POSTGRES_USER>"
$env:POSTGRES_PASSWORD = "<.env の POSTGRES_PASSWORD>"
.\gradlew.bat test
```

成功時:

```text
BUILD SUCCESSFUL
```

次の項目を検証します。

- ユーザー ID `1` と保護者 ID `2`、`3` でチャンネル作成
- ユーザー/許可された保護者のチャンネル参加を許可
- 許可されていないユーザー ID `99` の参加を拒否
- WebSocket セッション終了時にチャンネルは維持し、セッションだけを削除
- セッション終了時に `close(channelId)` でチャンネルを削除
- STOMP 購読・接続終了イベント処理

### Postman/STOMP 統合テスト

正式な統合テストには、先にセッション開始 API が必要です。セッション開始 API が `channelRegistry.open(1L, Set.of(2L, 3L))` を呼び、レスポンスに `channelId` を渡すよう統合された後、次の手順でテストします。

テスト参加者データ:

| 名前 | ID | 期待結果 |
| --- | ---: | --- |
| `user-1` | `1` | チャンネル作成者、購読/送信を許可 |
| `guardian-1` | `2` | 購読/送信を許可 |
| `guardian-2` | `3` | 購読/送信を許可 |
| `unknown-1` | `99` | 購読/送信を拒否 |

1. セッション開始 REST API を呼び、`channelId` を取得します。
2. Postman で **New → WebSocket** を選び、`ws://localhost:8080/ws` に接続します。
3. ユーザー用と保護者用にそれぞれ WebSocket 接続を開きます。
4. 各接続で STOMP `CONNECT` フレームを送ります。
5. ユーザー接続は `participant-id: 1`、保護者接続は `participant-id: 2` または `3` で同じチャンネルを購読します。
6. 入室済み接続の一方がメッセージを送ると、同じチャンネルの全購読者が `MESSAGE` フレームを受け取ります。
7. `participant-id: 99` で同じチャンネルを購読する、または購読していないセッションが送信すると、サーバーが拒否します。

### Postman の Hexadecimal フレーム

Postman の WebSocket メッセージ形式で **Hexadecimal** を選択します。STOMP フレームは UTF-8 テキストを 16 進バイトで表したもので、末尾の `00` はフレーム終了を表す NUL 文字です。

| Hex 値 | 意味 |
| --- | --- |
| `0A` | 改行(`\n`) |
| `0A 0A` | ヘッダーと body の境界 |
| `00` | STOMP フレーム終了(NUL) |

`CONNECT` フレームの意味:

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

Postman Hexadecimal 入力値:

```text
43 4F 4E 4E 45 43 54 0A 61 63 63 65 70 74 2D 76 65 72 73 69 6F 6E 3A 31 2E 32 0A 68 6F 73 74 3A 6C 6F 63 61 6C 68 6F 73 74 0A 0A 00
```

ユーザー `1` の購読フレームは、論理的には次の JSON と同じです。

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

メッセージ送信フレームは次の JSON と同じです。

```json
{
  "command": "SEND",
  "headers": {
    "destination": "/app/connections/{channelId}/messages",
    "content-type": "application/json"
  },
  "body": {
    "senderId": "1",
    "content": "テストメッセージ"
  },
  "terminator": "00"
}
```

`channelId` は毎回変わるため、`SUBSCRIBE` と `SEND` の Hex 値は作成する必要があります。PowerShell では次の関数で STOMP テキストフレームを Hexadecimal 入力値に変換できます。

```powershell
function ConvertTo-StompHex([string]$frame) {
    ([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($frame))).Replace('-', ' ')
}

$channelId = '<セッション開始 API が返した channelId>'

$subscribe = "SUBSCRIBE`nid:sub-user-1`ndestination:/topic/connections/$channelId`nparticipant-id:1`n`n" + [char]0
ConvertTo-StompHex $subscribe

$send = "SEND`ndestination:/app/connections/$channelId/messages`ncontent-type:application/json`n`n{`"senderId`":`"1`",`"content`":`"テストメッセージ`"}" + [char]0
ConvertTo-StompHex $send
```

変換結果全体を Postman の Hexadecimal 入力欄へ貼り付けます。成功時に受信する `MESSAGE` フレームの body は次の JSON 形式です。

```json
{
  "connectionId": "{channelId}",
  "senderId": "1",
  "content": "テストメッセージ",
  "sentAt": "..."
}
```

## 今後の連携作業

1. セッション開始サービスが承認済み保護者一覧を検索して `open(userId, guardianIds)` を呼び出す
2. セッションレスポンス/通知に `channelId` またはサーバー検証用の接続 ticket を渡す
3. セッション終了サービスで `close(channelId)` を呼び出す
4. JWT または Web リンク ticket を STOMP `CONNECT` 時に検証し、開発用 `participant-id` ヘッダーをサーバー認証情報で置き換える
5. 複数サーバー配備時にはメモリレジストリを共有ストアへ置き換える
