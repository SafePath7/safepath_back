# 환경과 배포 계획

## 프로필

| 프로필 | 연결 대상과 환경변수 | Flyway |
|---|---|---|
| 기본 | DB 연결 값이 설정되어 있지 않음 | 비활성화 |
| `local` | `localhost:5433/safepath`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | 자동 실행 |
| `dev` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 비활성화 |

`local`은 Docker PostgreSQL용이며 JPA `ddl-auto=validate`를 사용합니다. `.env`는 Compose가 읽지만 Spring Boot 실행 환경에는 자동으로 전달되지 않습니다. 자세한 실행 방법은 [Docker 문서](../docker/README.md)를 참고합니다.

## AWS RDS와 향후 배포

공용 AWS RDS PostgreSQL에 연결할 때는 `dev` 프로필의 환경변수에 JDBC URL과 계정 정보를 안전하게 제공할 계획입니다. 실제 자격 증명은 저장소에 넣지 않습니다. `dev`에서는 Flyway 자동 적용이 비활성화되어 있습니다. 공용 DB 변경은 담당자가 마이그레이션을 검토한 후 수동 적용하며, 구체적인 실행 절차와 권한은 적용 전에 정해야 합니다.

향후 Spring Boot를 EC2에 배포하고 RDS에 연결할 계획입니다. 현재 저장소에는 EC2 배포 설정이나 자동 배포 파이프라인이 구현되어 있지 않습니다. 네트워크 접근, 자격 증명 전달, 배포 및 장애 복구 절차는 별도 설계가 필요합니다.
