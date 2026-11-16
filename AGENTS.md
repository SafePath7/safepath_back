# SafePath Backend AI Harness

Codex는 작업 전에 반드시 [공통 규칙](docs/ai-rules/common.md)을 읽고 적용한다. 기존 구현 분석·재사용, 협업 코드 보호, 요청 범위 제한이 모든 작업의 최우선 규칙이다.

## 작업 전 규칙 선택

1. 관련 패키지, 기존 Controller·Service·Repository·DTO·Entity와 호출 관계를 먼저 분석한다.
2. 기존 구현을 재사용 또는 확장할 수 있는지 확인하고 중복 구현하지 않는다.
3. 다른 팀원 담당 코드, 공통 API·DB·보안 구조의 변경은 이유와 영향 범위를 설명하고 사용자 승인을 받은 뒤에만 진행한다.
4. 요청 범위 밖 파일과 대규모 포맷·리팩터링을 수행하지 않는다.

`common.md`는 항상 필수다. 작업 유형에 따라 다음 규칙을 추가로 읽는다.

| 작업 | 필수 추가 규칙 | 필요 시 추가 규칙 |
|---|---|---|
| 일반 Spring/REST 백엔드 | [backend.md](docs/ai-rules/backend.md) | database, integration, testing |
| DB 스키마·Entity·Repository·Flyway | [database.md](docs/ai-rules/database.md), [backend.md](docs/ai-rules/backend.md) | integration, testing |
| Firebase·WebSocket/STOMP·FCM 연동 | [integration.md](docs/ai-rules/integration.md) | backend, database, testing |
| 테스트 작성·수정·실행 또는 리팩터링 | [testing.md](docs/ai-rules/testing.md) | backend, database, integration |

복합 작업은 표의 관련 규칙을 모두 읽고 적용한다. `.agents/skills/` 아래 Skill은 같은 선택 기준을 작업 절차로 제공하지만, Skill을 선택하지 않아도 이 파일과 연결된 규칙은 적용된다.

## 작업 후 검증

코드 수정 뒤에는 관련 테스트와 빌드를 가능한 범위에서 실행하고, 변경 파일·기존 기능 영향·검증 결과·실행하지 못한 검증 사유를 보고한다. 테스트 통과만을 위해 운영 코드를 변경하거나 기존 테스트를 삭제·무력화하지 않는다.

## 자동화 경계

이 하네스는 규칙 탐색과 작업 절차를 제공하며, Git Hook이나 CI를 생성·변경하지 않는다. 정적 분석·테스트·CI 연결처럼 자동 검증을 추가하려면 먼저 현재 Gradle/CI 구성과 영향 범위를 분석하고 사용자 승인을 받는다. Codex lifecycle Hook은 지원되는 신뢰된 `.codex` 설정 또는 설치된 플러그인 구성이 필요하므로, 별도 구성 없이 Hook이 동작한다고 가정하지 않는다.
