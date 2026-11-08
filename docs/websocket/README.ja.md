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
| `close(String channelId)` | チャンネルと、そのチャンネルに属する接続セッション情報をメモリから削除する。セッション終了サービスが呼び出す。既存の STOMP 購読を自動解除したり、WebSocket 接続を強制終了したりはしない。 |

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

`close(channelId)` は `InMemoryChannelRegistry` のメモリ情報だけを削除します。すでに作成された STOMP 購読を自動で `UNSUBSCRIBE` したり、WebSocket 接続を終了したりはしません。セッション終了時にクライアントへ終了イベントを送信し、クライアント側で購読解除/接続終了を処理する方式は、セッション連携段階で別途実装します。

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

## 現在のテスト方法

### 現在の状態

セッション開始/終了 API はまだ WebSocket モジュールと連携していません。そのため現在の正式コードには `channelRegistry.open(...)` を呼び出すビジネスロジックがなく、有効なチャンネルも生成されません。

- チャンネル生成用の WebSocket REST API は意図的に存在しません。
- 臨時の Postman テスト API はコミット対象から削除済みです。
- したがって、**現在は Postman でエンドツーエンドのチャンネル生成・購読・メッセージ送信をテストできません。**
- 現在できる検証は、チャンネルレジストリと STOMP イベント処理の JUnit テストです。

### 実施した Postman 統合テストの記録と再現方法

#### テスト概要

実際のセッション開始で使う主要な呼び出しを一時 API で再現し、WebSocket チャンネルを検証しました。

```text
実際の利用フロー
セッション開始 API
→ channelRegistry.open(userId, guardianIds)
→ channelId を返す
→ WebSocket 接続

テストフロー
一時テスト API
→ channelRegistry.open(1L, Set.of(2L, 3L))
→ channelId を返す
→ Postman WebSocket 接続
```

実際に Postman で確認した結果:

- ユーザー `1`、保護者 `2`、`3` を参加者とするチャンネル生成
- ユーザー `1` と保護者 `2` の同一チャンネル購読とメッセージ送受信
- 保護者 `3` は許可参加者一覧に含めたチャンネル生成だけを確認し、実際のメッセージ送受信は別途検証していない。
- 非許可ユーザー `99` の購読、メッセージ受信、メッセージ送信試行を遮断した結果
- WebSocket 接続終了時はチャンネルを維持し、接続セッションだけを削除

セッション API 連携前には `open(userId, guardianIds)` を呼び出す正式 API がありませんでした。そのため、以下の**ローカル専用の一時コード**を追加して実際の Postman 統合テストを実施しました。このコードはテスト終了後に削除しており、コミットしません。

#### 1. 一時ファイルの追加

次の 4 ファイルを追加します。すべて `local` プロファイルだけで動作させます。

`src/main/java/com/safepath/backend/websocket/dto/LocalChannelOpenRequest.java`

```java
public record LocalChannelOpenRequest(
        @NotNull Long userId,
        @NotEmpty Set<@NotNull Long> guardianIds
) {
}
```

`src/main/java/com/safepath/backend/websocket/dto/LocalChannelOpenResponse.java`

```java
public record LocalChannelOpenResponse(String channelId) {
}
```

`src/main/java/com/safepath/backend/websocket/LocalWebSocketTestController.java`

```java
@Profile("local")
@RestController
@RequestMapping("/api/test/websocket/channels")
public class LocalWebSocketTestController {

    private final InMemoryChannelRegistry channelRegistry;

    public LocalWebSocketTestController(InMemoryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocalChannelOpenResponse open(@Valid @RequestBody LocalChannelOpenRequest request) {
        return new LocalChannelOpenResponse(
                channelRegistry.open(request.userId(), request.guardianIds())
        );
    }
}
```

`src/main/java/com/safepath/backend/config/LocalWebSocketTestSecurityConfig.java`

```java
@Profile("local")
@Configuration
public class LocalWebSocketTestSecurityConfig {

    @Bean
    WebSecurityCustomizer localWebSocketTestWebSecurityCustomizer() {
        return web -> web.ignoring()
                .requestMatchers("/api/test/websocket/**");
    }
}
```

必要な import はそれぞれ `jakarta.validation`、`java.util.Set`、Spring Web annotation、Spring Security の `WebSecurityCustomizer` を追加します。既存の `SecurityConfig` にある `/ws`、`/ws/**` の設定は変更しません。

#### 2. Postman でのチャンネル生成

通常の HTTP リクエストで次を呼び出します。

```text
POST http://localhost:8080/api/test/websocket/channels
Content-Type: application/json
```

```json
{
  "userId": 1,
  "guardianIds": [2, 3]
}
```

期待結果は `201 Created` と新しい UUID チャンネル ID です。

```json
{
  "channelId": "{UUID}"
}
```

この値を、以降の STOMP 宛先にある `{channelId}` の位置で使います。

#### 3. Postman STOMP の検証

2 つ以上の Postman WebSocket 接続を開き、次の参加者を検証します。

| 参加者 | `participant-id` | 期待結果 |
| --- | ---: | --- |
| ユーザー | `1` | 購読/メッセージ送信を許可 |
| 保護者 1 | `2` | 購読/メッセージ送信を許可 |
| 保護者 2 | `3` | 購読/メッセージ送信を許可 |
| 非許可ユーザー | `99` | 購読とメッセージ送信を拒否 |

各 WebSocket 接続先は `ws://localhost:8080/ws` です。続いて Hexadecimal の STOMP `CONNECT` → `SUBSCRIBE` → `SEND` フレームを送信します。フレーム値と変換方法は下の [Postman の Hexadecimal フレーム](#postman-の-hexadecimal-フレーム) を参照してください。

検証結果とソースコード上の例外条件を区別する。

- 実際に確認完了: `1` と `2` は同じチャンネルで互いの `MESSAGE` フレームを受信する。
- 実際に確認完了: `99` の購読、メッセージ受信、メッセージ送信試行が遮断される。
- 保護者 `3` の実際のメッセージ送受信は今回の Postman テストで確認していない。

| コード位置 | 条件 | サーバーコードで定義した例外メッセージ |
| --- | --- | --- |
| `InMemoryChannelRegistry.join(...)` | チャンネルがない、または `participant-id` が許可参加者ではない | `存在しない WebSocket チャンネルです。`、`チャンネル参加権限がありません。` |
| `ConnectionMessageController.relay(...)` | チャンネルがない、または WebSocket セッションがチャンネルへ入室していない | `存在しない WebSocket チャンネルです。`、`チャンネルに入室していない WebSocket セッションです。` |

これらはサーバーコードが投げる例外メッセージです。Postman で STOMP `ERROR` フレームまたは接続状態としてどのように現れるかは、Spring のイベント/エラー処理経路により別途確認する必要があり、今回のテスト結果と同一視しません。

#### 4. テストコードの整理

テスト終了後に次のファイルを削除します。

```text
LocalWebSocketTestController.java
LocalChannelOpenRequest.java
LocalChannelOpenResponse.java
LocalWebSocketTestSecurityConfig.java
```

これにより `/api/test/websocket/channels` エンドポイントとテスト専用のセキュリティ例外もなくなります。正式なチャンネル生成はセッション開始サービスが `channelRegistry.open(...)` を呼び出して処理します。

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

### セッション API 連携後の Postman/STOMP 統合テスト（参考）

以下の手順は現在実行するテストではありません。セッション開始 API が `channelRegistry.open(1L, Set.of(2L, 3L))` を呼び、レスポンスに `channelId` を渡すよう統合された後に使います。

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
