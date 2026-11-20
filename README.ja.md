# SafePath バックエンド

[한국어](README.md) | [日本語](README.ja.md)

SafePath は安全な帰宅を支援するキャップストーンプロジェクトです。このリポジトリでは Spring Boot ベースのバックエンド API、データベーススキーマ、ローカル開発環境を管理します。

## 技術スタック

- Java 21、Spring Boot 4.1.2、Gradle Wrapper 9.8.2
- Spring Web MVC、Security、Validation、Data JPA、Lombok
- Firebase Admin SDK（Firebase ID トークン検証）
- PostgreSQL 18.3、Docker Compose、Flyway 12.4.0

## 現在の状態

Firebase 認証、ユーザー登録・情報管理、保護者との連携、安全帰宅セッション、経路推薦、危険イベント、通知の機能があります。安全帰宅中の位置は WebSocket/STOMP で共有します。各機能の制約と詳細は下記の文書を参照してください。

アクティブセッションと WebSocket チャネルはサーバーのメモリに保存されるため、再起動時に消え、インスタンス間では共有されません。危険イベント用の DB セッションスナップショットの状態は、通常のセッション終了と自動同期されません。

## ローカル開発

1. JDK 21 と Docker Desktop を用意し、Docker エンジンを起動します。
2. プロジェクトルートで `.env.example` を `.env` にコピーし、`POSTGRES_PASSWORD` をローカル専用の値に変更します。`POSTGRES_DB=safepath` は維持します。Firebase サービスアカウントの JSON を安全な場所に用意し、`.env` に `GOOGLE_APPLICATION_CREDENTIALS=<JSON ファイルの絶対パス>` を追加します。
3. `docker compose up -d` で DB を起動し、`docker compose ps` で healthy を確認します。
4. IntelliJ の実行設定で `local` プロファイルを有効にし、作業ディレクトリをプロジェクトルートに設定します。ローカル設定はルートの `.env` を読み込みます。
5. `BackendApplication` を起動します。ローカル DB は `localhost:5433/safepath` で、Flyway が自動実行され、JPA は `ddl-auto=validate` を使用します。アプリの既定アドレスは `http://localhost:8080` です。

Firebase の認証情報ファイルや `.env` は Git に追加しないでください。ファイルを読み込めない場合、サーバーの起動に失敗することがあります。

macOS/Linux では、信頼できる `.env` に限り次のように実行できます。

```sh
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=local sh gradlew bootRun
```

同じ環境では `SPRING_PROFILES_ACTIVE=local sh gradlew test` でテストを実行できます。Windows では同じ環境変数と `local` プロファイルを設定し、`gradlew.bat bootRun`、`gradlew.bat test` を使います。`gradlew.bat build` または `sh gradlew build` はテスト後にビルドし、成果物を `build/libs` に出力します。既定プロファイルには DB 接続情報がないため、コンテキストテストが失敗する場合があります。Windows の設定例は [Docker 文書](docs/docker/README.md#windows-powershell)を参照してください。

## 認証とユーザー

`POST /api/auth/login` は Bearer の Firebase ID トークンを検証し、`users.firebase_uid` の登録状況を確認します。本文は不要です。登録済みなら `200` と `{"userId":42,"status":"REGISTERED"}`、未登録なら `200` と `{"userId":null,"status":"SIGNUP_REQUIRED"}`、トークンの欠落や検証失敗時は `401` を返します。

登録は `POST /api/users/me/registration`、本人情報の取得は `GET /api/users/me` を使用します。電話番号保護には別々の Base64 エンコード済み 32 バイト鍵 `SAFEPATH_PHONE_ENCRYPTION_KEY` と `SAFEPATH_PHONE_HASH_KEY` が必要です。全インスタンスで同じ鍵を使用し、Git に保存しないでください。鍵の変更には既存データの再暗号化とハッシュ再生成が必要です。FCM トークンは登録後に `PUT /api/notifications/fcm-token` で別途登録します。

`PATCH /api/users/me` は名前を更新し、`DELETE /api/users/me` は SafePath の会員と保護者関係を削除します。電話番号変更 API `PATCH /api/users/me/phone` は現在 `503` を返します。退会後も Firebase アカウントは残ります。危険イベントのメディアや音声原本がある場合はストレージ削除が未実装のため `503` を返し、DB データを保持します。詳細は[ユーザー文書](docs/user/README.md)と[韓国語 README](README.md)を参照してください。

ログイン POST、`GET /api/safety-facilities`、WebSocket 接続 `/ws` と `/ws/**` は HTTP 認証なしで利用できます。その他の要求には認証が必要です。WebSocket は接続後の STOMP `CONNECT` で Firebase ID トークンを検証します。現在 CSRF 検査は無効です。

## 安全帰宅セッションと位置共有

各 HTTP 要求には対象アカウントの Firebase ID トークンを Bearer トークンとして送信します。ユーザー ID と保護者 ID は DB の数値 ID で、セッション ID とチャネル ID は別の値です。

| 機能 | API | 認証と応答 |
| --- | --- | --- |
| セッション開始 | `POST /api/safe-return/sessions` | ユーザートークン、`guardianIds` と任意の `selectedRoute`。成功時 `201` と `id`、`startedAt`、`channelId` |
| ユーザーのセッション状態 | `GET /api/safe-return/sessions/{sessionId}/status` | 所有者トークン。成功時 `200` とセッション ID、開始時刻、選択経路 |
| 保護者によるアクティブセッション検索 | `GET /api/guardian/monitoring/sessions/users/{userId}/active` | 保護者トークン。LOCATION 権限と選択保護者を確認し、該当時は `200` と `id`、`startedAt`、セッションがないか選択されていない場合は `204` |
| 保護者のチャネル取得 | `GET /api/guardian/monitoring/sessions/{sessionId}/channel` | 選択された保護者のトークン。成功時にチャネル ID を返す |
| セッション終了 | `PATCH /api/safe-return/sessions/{sessionId}/end` | 所有者トークン。成功時 `204` |

ユーザーと保護者は `/ws` に接続し、それぞれのトークンを STOMP `CONNECT` ヘッダーに渡して `/topic/connections/{channelId}/locations` を購読します。ユーザーは `/app/connections/{channelId}/locations` に位置を送信します。サーバーは認証とチャネル参加権限を確認して位置を中継します。

終了時、サーバーはチャネルとセッション関連のメモリ情報を削除し、選択された保護者に終了 FCM 通知を送信します。通知の受信は保証されません。WebSocket 接続自体は強制的に閉じないため、クライアントは位置送信と購読を停止し、接続が不要になれば閉じます。

## プロジェクト構成と詳細文書

- `src/main/java/` — Spring Boot アプリケーション
- `src/main/resources/` — 設定と Flyway マイグレーション
- `src/test/` — テストコード
- `compose.yaml`、`.env.example` — ローカル PostgreSQL と環境変数の例
- [アーキテクチャ](docs/architecture/README.md)、[データベースと Flyway](docs/database/README.md)、[Docker](docs/docker/README.md)、[デプロイ計画](docs/deployment/README.md)
- [安全帰宅セッション](docs/safe-return-session/README.md)、[保護者モニタリング](docs/guardian-monitoring/README.md)、[WebSocket 位置共有](docs/websocket/README.ja.md)、[経路推薦](docs/route/README.ko.md)

`main` は安定した統合コードを維持し、`feature/*` ブランチと Pull Request で作業します。適用済みマイグレーションは変更せず、新しいバージョンを追加します。認証情報や DB パスワードを Git にアップロードしないでください。
