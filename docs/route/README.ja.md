# 徒歩経路推薦 API

[한국어](README.ko.md)

## 機能と範囲

`POST /api/routes` は出発地と目的地の緯度・経度を受け取り、Mapbox の徒歩経路と安全施設データから、おすすめ・安全優先・最速の経路を返します。計算結果は DB やセッションに保存しません。選択経路のセッション保存、保護者への共有、WebSocket 連携は未実装です。

スコアは**施設へのアクセスに関する暫定指標**であり、犯罪・事故確率や実際の安全性を検証・保証するものではありません。データのない区間を危険と断定することもできません。

## 準備と実行

1. 既存のローカル PostgreSQL とバックエンドを起動し、安全施設データを用意します。データがない場合、スコアは `null` になり得ます。設定方法は[プロジェクト README](../../README.md)を参照してください。
2. サーバー環境に `MAPBOX_ACCESS_TOKEN` を設定し、バックエンドを再起動します。`.env.example` は設定例です。実際のトークンを Git に保存しないでください。起動プロセスが環境変数を読み取れる必要があります。
3. Firebase ログインで取得した **Firebase ID トークン**を用意します。Mapbox トークンとは別です。未認証リクエストにはセキュリティ設定が 401 を返します。

Mapbox Directions API は徒歩候補の生成に使用し、地図の表示はフロントエンドの地図 SDK が担当します。外部 API の利用回数と応答時間が発生します。

## リクエスト

```http
POST /api/routes
Authorization: Bearer <Firebase ID token>
Content-Type: application/json
```

```json
{
  "origin": { "latitude": 35.8564, "longitude": 128.4938 },
  "destination": { "latitude": 35.865, "longitude": 128.505 }
}
```

ローカルの既定 URL は `http://localhost:8080/api/routes` です。各座標は必須の数値で、緯度は -90〜90、経度は -180〜180 です。出発地と目的地の直線距離が 1m 未満の場合、または評価対象に 20km を超える経路がある場合は 400 です。極地付近や日付変更線をまたぐ経路は現在サポートしていません。

## レスポンス

次は形式を示す例で、座標・距離・スコアは実測値ではありません。

```json
{
  "candidateCount": 2,
  "routes": [
    {
      "type": "RECOMMENDED",
      "coordinates": [
        { "latitude": 35.8564, "longitude": 128.4938 },
        { "latitude": 35.865, "longitude": 128.505 }
      ],
      "distanceMeters": 1800.0,
      "durationSeconds": 1200.0,
      "safetyScore": 65.0,
      "observedFraction": 0.8,
      "safetyFactors": { "CCTV": 4, "SECURITY_LIGHT": 5 },
      "sameAsTypes": ["FASTEST"]
    },
    {
      "type": "SAFEST",
      "coordinates": [
        { "latitude": 35.8564, "longitude": 128.4938 },
        { "latitude": 35.86, "longitude": 128.5 },
        { "latitude": 35.865, "longitude": 128.505 }
      ],
      "distanceMeters": 1950.0,
      "durationSeconds": 1320.0,
      "safetyScore": 70.0,
      "observedFraction": 0.9,
      "safetyFactors": { "CCTV": 5, "EMERGENCY_CALL_BOX": 1 },
      "sameAsTypes": []
    },
    {
      "type": "FASTEST",
      "coordinates": [
        { "latitude": 35.8564, "longitude": 128.4938 },
        { "latitude": 35.865, "longitude": 128.505 }
      ],
      "distanceMeters": 1800.0,
      "durationSeconds": 1200.0,
      "safetyScore": 65.0,
      "observedFraction": 0.8,
      "safetyFactors": { "CCTV": 4, "SECURITY_LIGHT": 5 },
      "sameAsTypes": ["RECOMMENDED"]
    }
  ]
}
```

| フィールド | 意味 |
| --- | --- |
| `candidateCount` | Mapbox の結果から座標配列が完全一致する経路をまとめ、距離順で最大 6 件に制限した候補数。評価するのはさらに短い最大 3 件です。 |
| `type` | `RECOMMENDED`、`SAFEST`、`FASTEST` の順に 3 件を返します。 |
| `coordinates` | 地図上で順に結ぶ緯度・経度オブジェクトの配列。道路網に合わせた開始・終了座標は入力座標と異なる場合があります。 |
| `distanceMeters`, `durationSeconds` | Mapbox が返した距離（m）と予想時間（秒）。 |
| `safetyScore` | 観測された区間の施設アクセス点数（0〜100）。観測区間がなければ `null`。 |
| `observedFraction` | 施設の寄与が 0 より大きい区間数 / 全区間数。データの網羅率や実際の安全率ではありません。 |
| `safetyFactors` | 半径内にあり、最も近い 1 区間に割り当てられた施設の種類別件数。 |
| `sameAsTypes` | 座標配列が完全に一致する他の選択タイプ。なければ空配列。 |

候補が 1〜2 件の場合や同じ候補が複数の基準で選ばれた場合、3 項目の経路が重複します。異なる 3 経路は保証しません。基本経路の取得後に追加リクエストが失敗しても、取得済みの候補で応答します。

## 経路選択とスコア

1. `MapboxRouteClient.find` は `mapbox/walking`、`alternatives=true`、全体の GeoJSON 経路を要求します。候補が 6 件未満なら歩道優先度 1、-1、0.5、-0.5 の順で最大 4 回追加要求し、座標配列の完全一致のみを重複排除して距離順で最大 6 件を残します。
2. `RouteService.recommend` は短い最大 3 件を評価します。以下の選択は**評価対象の候補内**の結果であり、道路網全体の最適経路ではありません。
3. 既存の `SafetyFacilityService.findFacilitiesInBounds` を使い、候補を囲む矩形内の施設を一度取得します。現在 PostGIS は使用していません。
4. `RouteScorer` が経路を既定の 100m ごとに分割し、`FacilitySafetyFactor` が各施設を半径 80m 内で最も近い 1 区間の代表点に割り当てます。同一施設 ID は一度だけ反映します。
5. 施設の寄与は `重み × (1 - 代表点までの距離 / 半径)`。区間スコアは `100 × (1 - exp(-寄与の合計))` です。寄与が 0 より大きい区間のみ平均と最小値に含め、全体は既定で `平均 × 0.8 + 最小値 × 0.2` です。最後の短い区間も同じ 1 区間として数えます。
6. `FASTEST` は評価対象で予想時間が最短の経路です。`SAFEST` は観測された施設スコアが最大の経路で、時間・距離による減点はありません。すべて `null` の場合は最短距離候補を代わりに返します。`RECOMMENDED` は `安全スコア - 0.5 × (当該時間 - 最短時間) / 60` が最大の経路です。スコア `null` は**順位比較時のみ** 50 とし、レスポンスは `null` のままです。

区間代表点を用いるため、実際の経路線までの最短距離とは異なる場合があります。複数タイプが重なっても、異なる経路を捏造しません。

### 設定と拡張

施設の種類と既定の重みは [FacilityScore](../../src/main/java/com/safepath/backend/route/score/FacilityScore.java) で管理します。既定値は CCTV 1、SECURITY_LIGHT 1、POLICE 2、EMERGENCY_CALL_BOX 1.5、SAFETY_GUARDIAN_STORE 1 です。DB の `facility_type` は enum 名と一致する必要があり、未知の種類は採点から除外します。

`safepath.route.score` 配下の設定で既定値を上書きできます。例: `safepath.route.score.cctv=2`、`safepath.route.score.duration-weight=1.0`。施設の設定キーは `cctv`、`light`、`police`、`emergency-call-box`、`safety-guardian-store` です。`segment-meters` の既定値は 100、`radius-meters` は 80、`weakest-weight` は 0.2、`duration-weight` は 0.5 です。値は暫定方針で、変更後は再起動が必要です。

`SafetyFactor` は区間ごとの寄与を計算する拡張ポイントです。現在サービスに接続されているのは施設戦略のみで、危険イベントや SOS の追加には実データへのアクセス、戦略実装、サービスへの接続が必要です。`ScorePolicy` の `distance-weight`、`safest-distance-weight`、`safest-duration-weight` は現在の経路選択では使用されていません。

## フロントエンド連携

1. 出発地・目的地を送り、`routes` の `type` で 3 カードを区別します。
2. 選択したカードの `coordinates` を順に地図 SDK のポリラインへ渡します。API は名前付きの緯度・経度オブジェクトを返しますが、GeoJSON 配列は `[longitude, latitude]` の順です。
3. `durationSeconds / 60` で分を表示し、`safetyScore == null` は「情報不足」として扱います。`observedFraction` も併せて確認してください。
4. `sameAsTypes` があれば「おすすめ・最速が同じ」などと表示します。経路をセッションに渡して保存する契約は未実装です。

## テスト

Postman で上記のリクエストを使い、**Authorization → Bearer Token** に Firebase ID トークンを設定します。Body は **raw → JSON** を選択します。成功時は 3 タイプ、座標、距離・時間、スコア、観測比率、同一経路表示を確認します。座標例は候補数や所要時間を保証しません。

緯度の範囲外、必須フィールド欠落、同一の出発地・目的地をそれぞれ送り 400 を確認できます。認証なしでは 401 を確認できます。実データとトークンを用いた手動テストは Mapbox の利用回数を消費します。

単体テストとビルド:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.route.*"
.\gradlew.bat build
```

経路の単体テストは実際の Mapbox・DB との統合検証の代わりにはなりません。数十万件の DB に対するインデックス利用と性能、実際のスコアの妥当性は別途検証が必要です。

## エラーと運用

| 状態 | コードまたは状況 |
| --- | --- |
| 400 | 必須値の欠落、座標範囲外、同一地点、20km 制限など |
| 401 / 403 | 認証失敗 / 権限拒否。Spring Security が処理します。 |
| 422 | `ROUTE_NOT_FOUND`: 基本要求で利用可能な経路がない |
| 502 | `ROUTING_PROVIDER_ERROR`: Mapbox の HTTP・応答・ネットワークエラー |
| 503 | `ROUTING_NOT_CONFIGURED`、`SAFETY_DATA_UNAVAILABLE`、`ROUTING_INTERRUPTED` |
| 504 | `ROUTING_TIMEOUT`: 基本 Mapbox 要求のタイムアウト |

追加候補要求が失敗した場合は基本候補を保持し、WARN ログを出します。INFO ログの `returned`、`added`、`unique` は返却・新規・累計候補数です。リクエストごとのタイムアウトは 10 秒、接続タイムアウトは 5 秒です。追加要求は順次実行されるため、全体の応答時間は長くなることがあります。ログにはトークンや座標を記録しません。
