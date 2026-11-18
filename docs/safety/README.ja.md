# SafePath 安全施設モジュール

[한국어](README.ko.md)

## 1. 対象と目的

本書は `com.safepath.backend.safety`、直接関係する Flyway マイグレーション、およびテストだけを対象とする。安全施設モジュールは、地図表示、管理者 CRUD、公共データ API からの同期を通じて `safety_facilities` を管理する。

```text
公開地図リクエスト -> SafetyFacilityController -> SafetyFacilityService -> Repository
管理者リクエスト -> SafetyFacilityAdminController -> AdminService -> Repository
スケジューラ -> 供給元別 SyncService -> SafetyFacilitySyncService -> Repository
```

## 2. パッケージの責務

| 場所 | 責務 |
| --- | --- |
| `controller` | 公開地図検索 HTTP API |
| `admin/controller`, `admin/service`, `admin/dto` | 管理者向け検索・作成・更新・削除 |
| `service` | 地図検索ポリシーと共通 upsert |
| `entity`, `repository`, `dto` | 永続モデル、検索、API/同期データモデル |
| `facility/<source>` | 5つの公共データ API クライアント、変換、同期、定期実行 |
| `facility/exception` | 外部 API リクエストと再試行枯渇の例外 |

## 3. データモデルとマイグレーション

`SafetyFacility` は `safety_facilities` に対応する。

| カラム | 型/制約 | 内容 |
| --- | --- | --- |
| `id` | BIGINT PK | 内部 ID |
| `latitude`, `longitude` | DOUBLE PRECISION, NOT NULL | WGS84 緯度・経度 |
| `address` | VARCHAR(255), NOT NULL | 表示住所 |
| `facility_type` | VARCHAR(50), NOT NULL | `CCTV`、`POLICE` などの分類 |
| `source` | VARCHAR(30), nullable | データ供給元。例: `CCTV`、`ADMIN` |
| `external_id` | VARCHAR(255), nullable | 供給元における外部 ID |
| `created_at`, `updated_at` | TIMESTAMP, NOT NULL | 作成・最終更新時刻 |

`V1__create_tables.sql` は基本テーブルを作成し、`V2__add_safety_facility_source_and_external_id.sql` は同期用の `source` と `external_id` を追加する。V2 は `IF NOT EXISTS` を使うため、履歴が V1 のままでもカラムが先に存在する環境ではカラム追加をスキップして履歴を V2 にそろえられる。適用済み V1 は変更しない。`ddl-auto=validate`、かつ現在は `spring.flyway.enabled=false` のため、実環境で V2 を反映するには Flyway を有効化したデプロイ手順または承認済み DB 手順が必要である。

`SafetyFacility.create(...)` は施設と時刻を作成する。`update(...)` は座標・住所・分類・`updatedAt` を更新し、`source` と `externalId` は変更しない。

## 4. 公開地図 API

`GET /api/safety-facilities` は認証不要である。必須クエリは `zoom` (1〜14)、`southWestLat`/`northEastLat` (-90〜90)、`southWestLng`/`northEastLng` (-180〜180) である。南西座標が北東座標を超えると 400 を返す。

```http
GET /api/safety-facilities?zoom=3&southWestLat=37.50&southWestLng=126.90&northEastLat=37.60&northEastLng=127.10
```

戻り値は `SafetyFacilityMapResponse(mode, totalCount, clusters, facilities)`。

- `zoom <= 3`: `MARKER`。ID 昇順の全施設を `facilities` に返し、`clusters` は空。
- `zoom >= 4`: `CLUSTER`。`facilities` は空で、格子ごとの平均座標と件数を `clusters` に返す。
- 格子サイズは `10.24 / 2^(14 - zoom)` 度。SQL は緯度・経度の `FLOOR(... / gridSize)` でグループ化する。

公開マーカーは `id`, `latitude`, `longitude`, `address`, `facilityType`、クラスタは `latitude`, `longitude`, `count` である。`source` と `externalId` は公開しない。

## 5. 管理者 API

基底パスは `/api/admin/safety-facilities`。認証が必須であり、サービス層でも `Actor.requireAdmin()` を実行する。

| HTTP | メソッド | 内容 | 成功 |
| --- | --- | --- | --- |
| `GET /?page=0&size=50` | `list` | ID 降順のページ検索 | 200 |
| `GET /{facilityId}` | `get` | 1件取得 | 200 |
| `POST /` | `create` | 手動施設の作成 | 201 |
| `PATCH /{facilityId}` | `update` | 座標・住所・分類の更新 | 200 |
| `DELETE /{facilityId}` | `delete` | 1件削除 | 204 |

`page >= 0`、`1 <= size <= 100`、`facilityId > 0`。リクエスト本体は `latitude`、`longitude`、`address`、`facilityType` で、緯度経度と文字数を検証する。作成時は `source=ADMIN`、`externalId` は UUID になる。管理者レスポンスには `source`, `createdAt`, `updatedAt` も含む。存在しない ID は 404。

## 6. サービス・Repository のメソッド

| クラス/メソッド | 内容 |
| --- | --- |
| `SafetyFacilityService.findInBounds(...)` | 境界を検証し、地図レベルに応じてマーカーまたはクラスタ検索を選ぶ。 |
| `SafetyFacilitySyncService.hasFacilities(source)` | 初回同期スキップ用に供給元データの存在を確認する。 |
| `SafetyFacilitySyncService.sync(item)` | `(source, externalId)` の単件 upsert。 |
| `SafetyFacilitySyncService.syncAll(items)` | 供給元単位で既存データを一括取得し、新規のみ `saveAll`、既存は変更検知で更新する。 |
| `SafetyFacilityRepository.findClustersInBounds(...)` | native SQL で境界内の格子クラスタを集計する。 |

Repository には `existsBySource`、`findBySourceAndExternalId`、`findBySourceAndExternalIdIn`、境界内マーカー検索、件数検索もある。現在の重複識別は DB 制約ではなく `(source, externalId)` の検索規約であるため、外部 ID は安定させる。

## 7. 外部データ同期・定期実行

各供給元は API 応答を `SafetyFacilityImportItem` に変換して共通 upsert に渡す。無効/空の座標は除外し、ページ・応答状態を検証する。警察署の Web Mercator 座標は WGS84 に変換する。

| モジュール | `source` / 種別 | 公開同期メソッド | 環境変数 |
| --- | --- | --- | --- |
| CCTV | `CCTV` / `CCTV` | `syncAll()`, `syncAll(maxPages)`, `syncAll(startPage,maxPages)` | `SAFEPATH_CCTV_API_KEY` |
| 非常ベル | `EMERGENCY_CALL_BOX` / 変換値 | 同上 | `SAFEPATH_EMERGENCY_CALL_BOX_API_KEY` |
| 警察署 | `POLICE_STATION` / `POLICE` | `syncAll(maxPages)` | `SAFEPATH_POLICE_STATION_API_KEY` |
| 女性安心拠点 | `SAFETY_GUARDIAN_STORE` / 同名 | `syncAll(maxPages)` | `SAFEPATH_SAFETY_GUARDIAN_STORE_API_KEY` |
| 防犯灯 | `SECURITY_LIGHT` / 変換値 | `syncAll()` | `SAFEPATH_SECURITY_LIGHT_API_KEY` |

`fetch(pageNo, numOfRows)` は API キー未設定時に失敗する。`SafetyFacilityApiRetryExecutor.execute(...)` は再試行可能な HTTP/ネットワーク失敗を指数バックオフで再試行し、非再試行 HTTP 失敗は即時送出する。試行枯渇時は `SafetyFacilityApiRetryExhaustedException`。CCTV は専用 `CctvApiRetryExecutor` を持つ。

各 `*SyncScheduler.sync()` は `@Scheduled`。既定の初期遅延は 0ms、周期は 7,776,000,000ms（約90日）。初回に同じ `source` のデータがあれば全件同期をスキップする。`max-pages=0` は最終ページまでを意味する。設定は `safepath.<source>.sync.*`（開始ページ、最大ページ、初期遅延、周期、CCTV の retry/request interval）で上書きできる。秘密鍵の値は文書やリポジトリに保存しない。

## 8. テスト方法

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "com.safepath.backend.safety.*"
.\gradlew.bat test --tests "com.safepath.backend.safety.service.SafetyFacilityServiceTest"
.\gradlew.bat test --tests "com.safepath.backend.safety.admin.SafetyFacilityAdminApiTests"
```

テスト対象は、公開 API の認証不要・マーカー/クラスタ・400、管理者 CRUD と権限、エンティティ時刻と upsert、供給元別ページ処理・無効座標除外・再試行・スケジューラである。Flyway を有効にして空 DB を検証する場合は V1 と V2 が順に適用されることを確認し、適用済みファイルは変更しない。

## 9. 変更時チェックリスト

1. 新しい供給元には一意の `source`、安定した `externalId`、クライアント・変換・スケジューラ・テストを追加する。
2. エンティティのカラム追加には新しい Flyway バージョンを追加する。
3. 公開レスポンスや URL の変更では地図クライアントと SecurityConfig の公開パスを確認する。
4. 管理 API の `requireAdmin()` と API キーの環境変数注入を維持する。
5. 大量データでは低ズームの全マーカー返却とクラスタ SQL の性能を確認する。
