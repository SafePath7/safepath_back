# 백엔드 아키텍처

## 기능별 구성

`develop`을 기준으로 다음 두 기능 브랜치를 독립적으로 검토합니다. 양쪽은 같은 공통 인증·오류 처리 커밋을 공유합니다.

| 브랜치 | 패키지 | API |
|---|---|---|
| `feature/guardian-registration-api` | `guardian` | 보호자 등록·상태·권한 7개 |
| `feature/risk-event-api` | `risk` | 위치·위험 이벤트·미디어 7개 |

공통 `security`는 검증된 Firebase UID와 관리자 권한을 전달하고 `common`은 오류 응답을 처리합니다. Firebase 서명·만료 검증 구현체는 공통 담당 모듈을 연결해야 합니다.

도메인별 Controller → Service → Repository 구조로 요청을 처리하고 `JdbcTemplate`으로 PostgreSQL에 접근합니다. 쓰기는 트랜잭션과 필요한 행 잠금을 사용합니다. 위험 이벤트 브랜치의 위치 조회는 DB의 기존 보호자 연결 상태·위치 조회 권한만 읽으므로 보호자 API 클래스에 의존하지 않습니다.

V1의 공통 테이블 정의는 변경하지 않습니다. 위치 테이블 V2와 비공개 파일 저장소는 위험 이벤트 브랜치에만 포함됩니다. 미디어 파일 처리와 DB 실패 보상 동작은 해당 브랜치의 문서와 테스트에서 설명합니다.

API 범위와 실행·검증 방법은 [기능별 API 안내](../api/README.md), 테이블 정의는 [DB 문서](../database/README.md)를 참고하세요.
