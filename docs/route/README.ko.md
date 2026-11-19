# 도보 경로 추천 API

[日本語](README.ja.md)

## 기능과 범위

`POST /api/routes`는 출발지와 목적지의 위도·경도를 받아 Mapbox 도보 경로와 안전시설 데이터를 바탕으로 추천·안전 우선·가장 빠른 경로를 반환합니다. 추천 API 자체는 결과를 저장하지 않습니다. 선택한 경로는 [세션 시작 요청](../safe-return-session/README.md)에 전달할 수 있으며 세션이 활성화된 동안 메모리에 보관됩니다.

API의 점수는 **시설 접근성에 관한 임시 지표**입니다. 범죄·사고 확률이나 실제 안전을 검증하거나 보장하지 않습니다. 시설이 누락된 구간은 위험하다고 단정할 수 없습니다.

## 준비와 실행

1. 기존 로컬 PostgreSQL과 백엔드를 실행하고 안전시설 데이터를 준비합니다. 빈 DB라면 점수는 `null`일 수 있습니다. 설정 방법은 [프로젝트 README](../../README.md)를 참고하세요.
2. 서버 환경에 `MAPBOX_ACCESS_TOKEN`을 설정하고 백엔드를 재시작합니다. `.env.example`은 예시이며 실제 토큰은 Git에 저장하지 않습니다. 실제 실행 프로세스가 환경변수를 읽어야 합니다.
3. Firebase 로그인으로 발급받은 **Firebase ID 토큰**을 준비합니다. Mapbox 토큰과는 별개입니다. 인증되지 않은 요청은 보안 설정에서 401을 반환합니다.

Mapbox Directions API는 보행 후보 생성에 사용하고, 지도 표시는 프런트의 지도 SDK가 담당합니다. 호출 시 외부 API 사용량과 응답 시간이 발생합니다.

## 요청

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

로컬 기본 주소는 `http://localhost:8080/api/routes`입니다. 각 좌표는 필수 숫자이며 위도는 -90~90, 경도는 -180~180입니다. 출발지와 목적지의 직선거리가 1m 미만이거나 평가 대상 중 20km 초과 경로가 있으면 400을 반환합니다. 극지방에 가까운 경로 또는 날짜변경선에 걸친 경로는 현재 지원하지 않습니다.

## 응답

다음은 형식을 설명하기 위한 예시이며 좌표·거리·점수는 실제 결과가 아닙니다.

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

| 필드 | 의미 |
| --- | --- |
| `candidateCount` | Mapbox에서 확보한 경로 중 좌표 목록이 완전히 같은 경로를 중복 제거한 후보 수입니다. 확보한 후보 전체를 평가합니다. |
| `type` | `RECOMMENDED`, `SAFEST`, `FASTEST` 순서로 세 항목을 반환합니다. |
| `coordinates` | 지도에 순서대로 연결할 위도·경도 객체 배열입니다. Mapbox가 도로망에 맞춘 시작·끝 좌표는 요청 좌표와 다를 수 있습니다. |
| `distanceMeters`, `durationSeconds` | Mapbox가 반환한 이동 거리(m)와 예상 시간(초)입니다. |
| `safetyScore` | 관측 구간의 시설 접근성 점수(0~100)입니다. 관측된 구간이 없으면 `null`입니다. |
| `observedFraction` | 시설 기여가 0보다 큰 구간 수 / 전체 구간 수입니다. 데이터의 완전성이나 실제 안전 비율은 아닙니다. |
| `safetyFactors` | 해당 경로의 반경 내 가장 가까운 한 구간에 배정된 시설의 유형별 개수입니다. |
| `sameAsTypes` | 좌표 목록이 완전히 같은 다른 선택 유형입니다. 없으면 빈 배열입니다. |

후보가 1~2개이거나 같은 후보가 여러 기준에서 선택되면 세 항목의 경로가 같을 수 있습니다. 세 개의 서로 다른 경로를 보장하지 않습니다. 기본 경로 조회가 성공한 뒤 추가 요청이 실패해도 이미 확보한 후보로 응답합니다.

## 경로 선정과 점수

1. `MapboxRouteClient.find`는 `mapbox/walking`, `alternatives=true`, 전체 GeoJSON 경로를 요청합니다. 후보가 10개 미만이면 보행로 선호도 1, -1, 0.5, -0.5 순으로 최대 4회 추가 요청합니다. 좌표 배열이 정확히 같은 결과를 중복 제거하고, 먼저 확보한 후보부터 최대 10개를 유지합니다. 10개를 확보하면 추가 요청을 중단합니다.
2. `RouteService.recommend`는 확보한 후보 전체를 평가합니다. 따라서 아래의 최상위 경로들은 **평가 대상 후보 중** 선택한 결과이며 도로망 전체의 최적 경로가 아닙니다.
3. 기존 `SafetyFacilityService.findFacilitiesInBounds`로 후보를 감싸는 사각 영역의 시설을 한 번 조회합니다. 현재 PostGIS는 사용하지 않습니다.
4. `RouteScorer`는 경로를 기본 100m 단위로 나누고, `FacilitySafetyFactor`는 각 시설을 반경 80m 내 가장 가까운 구간 대표점 하나에만 배정합니다. 중복 시설 ID는 한 번만 반영합니다.
5. 시설 기여값은 `가중치 × (1 - 대표점까지 거리 / 반경)²`입니다. 각 구간은 `100 × (1 - exp(-기여값 합))`으로 포화시키고, 0보다 큰 구간만 평균 및 최솟값 계산에 사용합니다. 전체 점수는 기본적으로 `평균 × 0.8 + 최솟값 × 0.2`입니다. 마지막 짧은 구간도 한 구간으로 동일하게 셉니다.
6. `FASTEST`는 평가 대상에서 예상 시간이 최소인 경로입니다. `SAFEST`는 관측된 안전 점수가 가장 높은 경로로 시간·거리 감점이 없습니다. 전부 `null`이면 최단 거리 후보를 대신 반환합니다. `RECOMMENDED`는 `안전점수 - 1.0 × (해당 시간 - 최단 시간) / 60`이 최대인 경로입니다. 이때 점수 `null`은 **순위 비교에만** 50으로 취급하며 응답은 `null`입니다.

이 계산은 구간 대표점 기준으로 실제 경로 선까지의 최단거리와 다를 수 있습니다. 같은 유형이 겹치는 경우에는 억지로 서로 다른 경로를 만들지 않습니다.

### 설정과 확장

시설 유형과 기본 가중치는 [FacilityScore](../../src/main/java/com/safepath/backend/route/score/FacilityScore.java)에서 관리합니다. 기본값은 CCTV 1, SECURITY_LIGHT 1, POLICE 2, EMERGENCY_CALL_BOX 1.5, SAFETY_GUARDIAN_STORE 1입니다. DB의 `facility_type`이 enum 이름과 일치해야 하며 알 수 없는 유형은 점수에서 제외됩니다.

`safepath.route.score` 아래의 설정으로 기본값을 덮어쓸 수 있습니다. 예: `safepath.route.score.cctv=2`, `safepath.route.score.duration-weight=1.0`. 시설 설정 키는 `cctv`, `light`, `police`, `emergency-call-box`, `safety-guardian-store`입니다. `segment-meters` 기본 100, `radius-meters` 80, `weakest-weight` 0.2, `duration-weight` 1.0입니다. 값은 임시 정책값이며 변경 후 재시작이 필요합니다.

`SafetyFactor`는 구간별 기여를 계산하는 확장 지점입니다. 현재는 시설 전략만 서비스에 연결되어 있으므로 위험 이벤트·SOS 등을 도입할 때는 실제 데이터 접근, 전략 구현 및 서비스 연결이 추가로 필요합니다. `ScorePolicy`의 `distance-weight`, `safest-distance-weight`, `safest-duration-weight` 설정은 현재 경로 선정에 사용되지 않습니다.

## 프런트 연동

1. 출발지와 목적지를 보내고 `routes`의 `type`으로 세 카드를 구분합니다.
2. 선택한 카드의 `coordinates`를 순서대로 지도 SDK의 폴리라인에 전달합니다. API는 이름이 있는 위도·경도 객체를 주지만 GeoJSON 배열은 `[longitude, latitude]` 순서입니다.
3. `durationSeconds / 60`으로 분 단위를 표시하고, `safetyScore == null`은 ‘정보 부족’으로 표시합니다. `observedFraction`도 함께 고려합니다.
4. `sameAsTypes`가 있으면 ‘추천·가장 빠름 동일’처럼 표시합니다. 선택한 경로의 유형·좌표·거리·시간·점수는 세션 시작 요청의 `selectedRoute`에 전달합니다.

## 테스트

Postman에서 위 요청을 사용하고 **Authorization → Bearer Token**에 Firebase ID 토큰을 넣습니다. Body는 **raw → JSON**을 선택합니다. 성공 시 세 유형, 좌표, 거리·시간, 점수, 관측 비율 및 동일 경로 표시를 확인합니다. 좌표 예시는 경로 수나 특정 소요 시간을 보장하지 않습니다.

범위를 벗어난 위도, 누락된 필드, 동일한 출발·도착 좌표를 각각 보내 400을 확인하고, 인증 없이 보내 401을 확인할 수 있습니다. 실제 데이터와 토큰을 이용한 수동 테스트는 외부 Mapbox 사용량이 발생합니다.

단위 테스트 및 빌드:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.route.*"
.\gradlew.bat build
```

경로 단위 테스트는 실제 Mapbox·DB 연동 검증을 대신하지 않습니다. 수십만 건 DB에 대한 인덱스 사용 여부와 성능, 실제 점수의 타당성은 별도 검증이 필요합니다.

## 오류와 운영 확인

| 상태 | 코드 또는 상황 |
| --- | --- |
| 400 | 필수값 누락·좌표 범위 오류·동일 지점·20km 제한 등 |
| 401 / 403 | 인증 실패 / 권한 거부. Spring Security가 처리합니다. |
| 422 | `ROUTE_NOT_FOUND`: 기본 요청에서 사용 가능한 경로가 없음 |
| 502 | `ROUTING_PROVIDER_ERROR`: Mapbox HTTP·응답·네트워크 오류 |
| 503 | `ROUTING_NOT_CONFIGURED`, `SAFETY_DATA_UNAVAILABLE`, `ROUTING_INTERRUPTED` |
| 504 | `ROUTING_TIMEOUT`: 기본 Mapbox 요청 시간 초과 |

추가 후보 요청 실패는 성공한 기본 후보를 유지하고 WARN 로그로 기록합니다. INFO 로그의 `returned`, `added`, `unique`는 반환·신규·누적 후보 수를 나타냅니다. 요청별 타임아웃은 10초, 연결 타임아웃은 5초이며, 추가 요청이 순차 실행되어 전체 시간이 늘어날 수 있습니다. 로그에는 토큰과 좌표를 남기지 않습니다.
