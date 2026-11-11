# Docker 로컬 PostgreSQL

## 준비와 실행

Docker Desktop을 설치하고 Docker 엔진을 실행합니다. 프로젝트 루트에서 `.env.example`을 `.env`로 복사하고 빈 값을 채웁니다. `POSTGRES_DB`는 `safepath`, `POSTGRES_USER`는 로컬 계정명, `POSTGRES_PASSWORD`는 로컬 전용 비밀번호입니다. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`는 `dev` 프로필용으로 로컬에서는 비워 둡니다.

macOS/Linux:

```sh
cp .env.example .env
# .env의 POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD를 입력한 후 실행합니다.
docker compose up -d
docker compose ps
```

`postgres` 서비스가 healthy인지 확인합니다. 이미 `.env`가 있다면 복사 명령으로 덮어쓰기 전에 기존 설정을 확인하세요. `POSTGRES_DB=safepath`, `POSTGRES_USER`, `POSTGRES_PASSWORD`를 Compose가 읽습니다. Spring Boot `local`은 `POSTGRES_USER`, `POSTGRES_PASSWORD`를 별도로 전달받아야 합니다. IntelliJ 실행 설정에 추가하거나 [루트 README](../../README.md)의 터미널 실행 방법을 사용하세요. `.env`는 Git에서 제외됩니다.

## Windows PowerShell

Docker Desktop을 실행하고 프로젝트 루트 PowerShell에서 아래 명령을 사용합니다. 기존 `.env`가 있으면 덮어쓰지 마세요.

```powershell
Copy-Item .env.example .env
# .env의 POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD를 입력합니다.
docker compose up -d
docker compose ps
```

`healthy` 상태를 확인한 후 IntelliJ 실행 설정에서 `local` 프로필과 `.env`의 `POSTGRES_USER`, `POSTGRES_PASSWORD`를 환경변수로 지정하고 앱을 실행하세요. 터미널에서는 PowerShell 환경변수에 같은 값을 직접 설정한 뒤 다음 명령을 실행할 수 있습니다. 비밀번호는 명령 기록에 입력하지 말고 숨김 입력을 사용하거나 IntelliJ 실행 설정에서 지정하세요.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:POSTGRES_USER = 'safepath_local' # .env의 값과 일치시킵니다.
$secret = Read-Host '로컬 DB 비밀번호' -AsSecureString
$env:POSTGRES_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
.\gradlew.bat bootRun
```

앱을 종료한 뒤 같은 환경에서 `.\gradlew.bat test`로 테스트할 수 있습니다. 작업이 끝나면 `Remove-Item Env:POSTGRES_PASSWORD`로 현재 PowerShell 환경의 비밀번호를 제거하세요. `local` 프로필에서 Flyway가 자동 실행되므로 Windows 팀원은 Flyway CLI를 설치하지 않습니다. 공용 RDS 마이그레이션은 DB 담당자만 수행합니다.

## 접속

호스트 접속 주소는 `127.0.0.1:5433`, DB 이름은 `safepath`이며 사용자 이름은 `.env`의 `POSTGRES_USER`입니다. 컨테이너 내부의 `psql`은 다음과 같이 실행할 수 있습니다.

```sh
docker compose exec postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

## 보존과 종료

데이터는 named volume `postgres_data`의 PostgreSQL 18용 경로 `/var/lib/postgresql`에 보관됩니다. 다음 명령으로 컨테이너를 중지하거나 다시 시작해도 볼륨은 유지됩니다.

```sh
docker compose stop
docker compose start
```

`docker compose down`도 기본적으로 named volume을 보존합니다. 반면 **`docker compose down -v`나 볼륨 삭제 명령은 DB 데이터를 삭제할 수 있으므로 사용 전에 팀과 데이터를 확인하세요.** 기존 볼륨이 초기화된 후 `.env`의 DB 이름이나 계정·비밀번호를 변경해도 기존 DB에 자동 반영되지는 않습니다.
