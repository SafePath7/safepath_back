# RDS Flyway V1 적용 전 점검

이 문서는 준비와 읽기 전용 점검 절차입니다. 이 단계에서는 `migrate`를 실행하지 않습니다. 예상과 다른 결과나 오류가 있으면 중단하고 담당자와 확인합니다. RDS 주소와 비밀번호를 저장소나 명령 기록에 남기지 않습니다.

## 준비

- Spring Boot를 실행하지 않는 독립 Flyway CLI 12.4.0을 별도로 설치하고 `flyway -v`로 버전을 확인합니다. PostgreSQL 지원 모듈과 JDBC 드라이버가 CLI 배포판에 포함되어 있는지도 확인합니다. 프로젝트 Gradle 의존성은 변경하지 않습니다.
- PostgreSQL `psql` 클라이언트를 준비합니다. 아래 명령은 프로젝트 루트의 macOS/Linux 셸 기준입니다.
- AWS 공식 경로에서 해당 RDS 인증서의 CA 번들을 내려받아 로컬 파일로 보관합니다. RDS 인스턴스의 실제 엔드포인트를 사용하고 `verify-full`로 인증서 체인과 호스트 이름을 검증합니다. 인증서 경로의 공백 등 URL 특수문자는 JDBC URL에 넣기 전에 인코딩해야 합니다.
- DB 담당자로부터 실제 RDS 엔드포인트, 포트와 migrator 비밀번호를 확인합니다. 로컬 Docker `.env`의 `POSTGRES_*` 값은 사용하지 않습니다. `safepath_app` 권한 부여는 별도 작업입니다.
- CLI가 자동으로 읽는 작업 디렉터리, 사용자 홈 및 설치 디렉터리의 Flyway 설정과 기존 `FLYWAY_*` 환경변수를 점검해 URL, 사용자, 스키마, 콜백 등이 섞이지 않도록 합니다. 이 절차는 Flyway CLI의 실제 배포판 구성과 권한에 따라 실행 전에 검증해야 합니다.

Windows 팀원은 개인 Docker DB에서 `local` 프로필을 사용하며 CLI 설치가 필요하지 않습니다. 공용 RDS는 DB 담당자만 CLI로 관리하고 `dev`의 Flyway는 비활성화 상태로 유지합니다. 서울 리전의 [AWS 공식 CA 번들](https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem)을 사용하며 [AWS 인증서 안내](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html)에서 최신 정보를 확인합니다.

DB 담당자 Mac에는 공식 macOS ARM64 배포판을 `~/.local/opt/flyway-12.4.0`에 설치했습니다. 다음 명령은 현재 셸에만 경로를 추가합니다.

```sh
export PATH="$HOME/.local/opt/flyway-12.4.0:$PATH"
flyway -v
```

CA 번들을 로컬의 안전한 경로에 저장한 뒤 아래 `RDS_CA`에 해당 절대 경로를 지정합니다. 번들은 공개 인증서이며 DB 비밀번호가 아닙니다.

## 접속 정보 설정

아래 값은 예시 자리표시자입니다. 포트는 실제 RDS 설정에 맞게 확인합니다. 비밀번호 입력은 화면에 표시되지 않으며 명령줄 인자로 전달하지 않습니다.

```sh
export RDS_HOST='<확인한-RDS-인스턴스-엔드포인트>'
export RDS_PORT='5432'
export RDS_CA='/절대/경로/rds-ca-bundle.pem'
export FLYWAY_USER='safepath_migrator'
printf 'RDS migrator 비밀번호: '
stty -echo; IFS= read -r FLYWAY_PASSWORD; stty echo; printf '\n'
export FLYWAY_PASSWORD
export PGPASSWORD="$FLYWAY_PASSWORD"
export PGSSLMODE='verify-full'
export PGSSLROOTCERT="$RDS_CA"
```

`RDS_HOST`를 AWS 콘솔에서 확인한 실제 `ap-northeast-2` RDS 엔드포인트와 대조하고 `localhost`, `127.0.0.1`, Docker 서비스 이름이 아닌지 확인합니다. `verify-full`은 실제 엔드포인트의 인증서 이름을 검증하므로 임의 별칭을 쓰지 않습니다. SSL 실패 시 인증서 검증을 끄지 말고 중단합니다.

다음 JDBC URL 예시는 CA 경로에 URL 특수문자가 없는 경우입니다. 실제 엔드포인트, CA 및 포트를 확인하고 로컬 주소가 아닌지 확인하세요.

```sh
export FLYWAY_URL="jdbc:postgresql://${RDS_HOST}:${RDS_PORT}/safepath?sslmode=verify-full&sslrootcert=${RDS_CA}"
export FLYWAY_LOCATIONS="filesystem:$(pwd)/src/main/resources/db/migration"
```

## 대상과 스키마 확인

먼저 마이그레이션 디렉터리에 `V1__create_tables.sql`만 있는지 확인합니다. 변경된 V1이나 다른 마이그레이션이 있다면 중단합니다. V1이 팀에서 검토한 커밋과 일치하는지도 확인합니다.

```sh
ls -la src/main/resources/db/migration
git status --short -- src/main/resources/db/migration/V1__create_tables.sql
```

다음 명령은 확인된 RDS에 읽기 전용 조회를 수행합니다. `current_database`가 `safepath`, `current_user`가 `safepath_migrator`인지, TLS가 활성화되어 있는지 확인합니다. `public`에 테이블·뷰·시퀀스·기타 관계 객체가 하나라도 있거나 `flyway_schema_history`가 존재하면 이 최초 적용 절차를 중단합니다. 권한 때문에 목록이나 TLS 상태를 확인할 수 없는 경우에도 중단합니다.

```sh
psql -X -v ON_ERROR_STOP=1 -h "$RDS_HOST" -p "$RDS_PORT" -U safepath_migrator -d safepath <<'SQL'
SELECT current_database(), current_user, inet_server_addr(), inet_server_port(), version();
SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();
SELECT n.nspname, c.relname, c.relkind
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
ORDER BY c.relname;
SQL
```

`flyway_schema_history`가 존재하면 적용을 중단합니다. 담당자가 이력을 확인해야 하는 경우에만 다음 읽기 전용 조회를 사용하고, 결과를 검토하기 전에는 다음 단계로 넘어가지 않습니다.

```sh
psql -X -v ON_ERROR_STOP=1 -h "$RDS_HOST" -p "$RDS_PORT" -U safepath_migrator -d safepath -c 'SELECT installed_rank, version, description, script, success FROM public.flyway_schema_history ORDER BY installed_rank;'
```

## Flyway 확인

다음 두 명령은 지정한 RDS에 연결하지만 마이그레이션을 적용하지 않습니다. `info`에서 대상 DB 및 스키마를 확인하고 V1만 `Pending`인지 확인합니다. 빈 DB의 `validate`는 아직 적용되지 않은 V1 때문에 실패할 수 있습니다. 그 경우 다른 오류가 없는지 확인하고 중단해 담당자와 검토합니다. `validate`는 스키마의 모든 객체를 검사하지 않으므로 앞의 DB 조회를 대신하지 않습니다.

```sh
flyway -schemas=public -defaultSchema=public -createSchemas=false -baselineOnMigrate=false info
flyway -schemas=public -defaultSchema=public -createSchemas=false -baselineOnMigrate=false validate
```

대상이 다르거나 예상 밖의 이력·객체·마이그레이션 또는 검증 오류가 있으면 진행하지 않습니다. 점검과 실제 적용 사이에 DB 상태가 바뀔 수 있으므로, 별도 승인된 적용 직전에 담당자가 다시 확인해야 합니다. 자동 baseline, repair, clean을 사용해 불일치를 우회하지 않습니다.

적용 직전에는 엔드포인트·계정·DB·TLS, `public`의 빈 상태, Flyway 이력 부재, V1 파일 및 `info` 결과를 다시 확인하고 다른 변경 작업이 없는지 담당자와 조율합니다. 실패 시 출력과 오류를 보존하고 작업을 중단합니다. DB를 초기화하거나 수동으로 SQL을 적용하지 않습니다.

작업 후 현재 셸에서 비밀번호를 제거합니다.

```sh
unset FLYWAY_PASSWORD PGPASSWORD FLYWAY_URL FLYWAY_USER FLYWAY_LOCATIONS RDS_HOST RDS_PORT RDS_CA PGSSLMODE PGSSLROOTCERT
```
