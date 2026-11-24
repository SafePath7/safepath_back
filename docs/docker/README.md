# Docker 로컬 개발 환경

## 세 서비스 함께 실행

1. Docker Desktop을 실행하고 이 백엔드 저장소와 AI 저장소를 각각 내려받습니다. 두 저장소를 합치지 않습니다.
2. 백엔드 루트에 `.env`가 없으면 `.env.example`을 복사합니다. 이미 있다면 덮어쓰지 말고 필요한 항목만 추가합니다. `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`는 기존 DB를 사용했다면 기존 값을 유지합니다.
3. `.env`의 `AI_PROJECT_PATH`에 AI 프로젝트 폴더를 지정합니다. 상대 경로는 백엔드의 `compose.yaml` 기준입니다. 예를 들어 나란히 있는 `../safepath_AI` 또는 자신의 절대 경로를 사용할 수 있습니다. 해당 폴더에 AI Dockerfile이 있어야 합니다.
4. Firebase 서비스 계정 JSON을 저장소 밖의 안전한 곳에 두고 `FIREBASE_SERVICE_ACCOUNT_PATH`에 호스트에서의 파일 경로를 지정합니다. 컨테이너에는 읽기 전용으로 연결됩니다. IntelliJ와 터미널에서 직접 실행할 때 쓰는 `GOOGLE_APPLICATION_CREDENTIALS`에도 호스트 경로를 별도로 지정합니다. JSON과 `.env`를 Git에 올리지 마세요.
5. 전화번호용 `SAFEPATH_PHONE_ENCRYPTION_KEY`와 `SAFEPATH_PHONE_HASH_KEY`를 서로 다른 Base64 인코딩 32바이트 값으로 설정합니다. 각각 `openssl rand -base64 32`로 생성할 수 있습니다. 기존 전화번호가 있는 DB는 반드시 기존 키를 유지하세요. 키를 바꾸면 복호화와 중복 확인에 영향을 줍니다.
6. 다음 명령을 백엔드 프로젝트 루트에서 실행합니다. 실행 전에 아래 Flyway 주의사항과 포트 충돌을 확인하세요.

```sh
docker compose config --quiet
docker compose up -d
docker compose ps
docker compose logs -f backend ai
```

Compose는 DB가 `healthy`가 된 후 백엔드를 시작합니다. AI는 백엔드 컨테이너 시작 후 시작하지만, 백엔드 API가 준비될 때까지 기다리지는 않습니다. `ps`의 실행 상태는 API의 정상 응답을 보장하지 않습니다. 각각 별도로 확인합니다.

```sh
curl 'http://localhost:8080/api/safety-facilities?zoom=10&southWestLat=37.0&southWestLng=126.0&northEastLat=38.0&northEastLng=128.0'
curl http://localhost:8000/health
```

첫 요청은 인증 없이 접근 가능한 실제 DB 조회 API입니다. 두 번째는 `mode`가 `model`이고 `models_loaded`에 `whisper`, `yamnet`, `speaker`가 있는지 확인합니다. 모델 다운로드와 로딩 중에는 AI 컨테이너가 실행 중이어도 응답하지 않을 수 있습니다. 포트 8080 또는 8000을 다른 프로세스가 사용 중이면 종료 여부를 직접 결정하거나 Compose 포트를 조정하세요. Spring Boot는 `postgres:5432`로 DB에, AI는 `http://backend:8080`으로 백엔드에 연결합니다. 백엔드에서 AI를 호출하는 기능은 현재 구현되어 있지 않습니다.

AI의 백엔드 호출은 기능에 따라 Firebase ID 토큰이 필요하며 음성 프로필 관련 호출은 내부 키도 확인합니다. 해당 기능을 사용하려면 `.env`의 `AI_BACKEND_KEY`와 `SAFEPATH_AI_INTERNAL_KEY`에 같은 비밀값을 설정하고, 음성 저장소 등 해당 기능의 별도 설정을 준비하세요. 키와 토큰 없이 네트워크만 확인하려면 AI 컨테이너에서 다음 공개 API를 호출할 수 있습니다.

```sh
docker compose exec ai python -c "import urllib.request; print(urllib.request.urlopen('http://backend:8080/api/safety-facilities?zoom=10&southWestLat=37.0&southWestLng=126.0&northEastLat=38.0&northEastLng=128.0').status)"
```

이는 네트워크와 공개 API 확인이며 인증된 AI 기능의 전체 검증은 아닙니다.

## AI 모델과 더미 모드

기본 설정은 `AI_INSTALL_MODELS=1`, `AI_LOAD_MODELS=1`로 실제 모델 패키지를 빌드하고 모델을 로드합니다. 첫 빌드와 모델 다운로드에는 시간과 인터넷 연결, 충분한 디스크와 메모리가 필요합니다. `ai_model_cache` named volume은 `/cache`에 연결되어 YAMNet·ECAPA와 Hugging Face의 Whisper 가중치를 컨테이너 재생성 후에도 보관합니다. 기존 단독 테스트 컨테이너가 별도 캐시를 사용하지 않았다면 첫 Compose 실행에서 다시 다운로드합니다. `docker compose logs -f ai`로 로딩 진행 상황을 확인하세요.

실제 모델은 음성을 분석하며, 더미 모드는 모델 추론 없이 고정 결과를 반환합니다. 더미 모드로 바꾸려면 백엔드 `.env`에 다음 두 값을 지정하고 AI 이미지를 다시 빌드합니다.

```properties
AI_INSTALL_MODELS=0
AI_LOAD_MODELS=0
```

```sh
docker compose up -d --build ai
curl http://localhost:8000/health
```

`mode`가 `dummy`인지 확인합니다. 모델 모드로 돌아갈 때는 두 값을 `1`로 바꾸고 같은 빌드 명령을 실행합니다. 두 설정은 함께 변경해야 합니다. 캐시 볼륨은 모드를 바꾸어도 보존됩니다. 실제 모델 환경의 자세한 내용은 AI 저장소 README를 참고하세요.

## DB만 실행하거나 직접 개발하기

AI 저장소가 없는 팀원도 `docker compose up -d postgres`로 DB만 실행할 수 있습니다. 전체 `docker compose up -d`는 AI 빌드 경로가 없으면 빌드 단계에서 실패합니다. `docker compose config --quiet`는 경로 존재와 빌드 성공을 보장하지 않습니다. 백엔드나 AI의 환경 설정은 DB만 실행할 때 필요하지 않습니다.

호스트에서 IntelliJ 또는 `sh gradlew bootRun`으로 실행할 때 기존 `local` 프로필과 `localhost:5433/safepath`를 사용합니다. 루트 [README](../../README.md#로컬-개발-시작)의 환경변수 설정을 참고하세요. 백엔드를 호스트에서 실행할 때 같은 8080 포트를 사용하는 컨테이너를 동시에 실행할 수 없습니다.

### Windows PowerShell

`.env`가 없다면 `Copy-Item .env.example .env`로 만들고 위 설정을 입력합니다. DB만 실행하려면 `docker compose up -d postgres`를 사용합니다. IntelliJ에서 `local` 프로필과 환경변수를 지정하거나, PowerShell에 환경변수를 안전하게 설정한 뒤 `.\gradlew.bat bootRun`을 실행합니다.

## DB 데이터와 Flyway

기존 Compose 프로젝트 이름과 `postgres_data` 볼륨 선언을 유지합니다. 같은 프로젝트 폴더와 Compose 프로젝트 이름을 사용해야 기존 볼륨을 찾습니다. `COMPOSE_PROJECT_NAME`, `-p` 옵션 또는 폴더 이름을 변경하면 다른 볼륨이 선택될 수 있습니다. `docker compose stop`과 `docker compose down`은 기본적으로 named volume을 유지합니다. `docker compose down -v`와 볼륨 삭제는 DB 데이터를 삭제하므로 사용하지 마세요. 초기화된 볼륨에서는 `.env`의 계정과 비밀번호를 바꿔도 기존 DB 계정에 자동 반영되지 않습니다.

컨테이너는 환경변수로 DB 연결을 받는 기존 `dev` 프로필을 사용하고, Compose에서 Flyway를 별도로 켭니다. 호스트에서 직접 실행할 때는 기존 `local` 프로필을 사용합니다. 시작 시 미적용 마이그레이션이 있으면 자동 적용됩니다. V1은 테이블을 생성하고, V2는 `safety_facilities`에 `source`, `external_id`를 추가하며, V3는 `voice_profiles`에 `baseline_details`, `urgent_audio_file_url`을 추가합니다. 이력과 실제 스키마에 따라 적용 또는 실패할 수 있으며, 스키마 변경과 잠금이 기존 데이터 및 실행에 영향을 줄 수 있습니다. 기존 DB의 `flyway_schema_history`와 백업을 먼저 확인하고 미적용 파일을 검토한 뒤 백엔드를 시작하세요. JPA는 스키마를 자동 변경하지 않고 검증합니다. 공용 DB 마이그레이션은 DB 담당자가 수행합니다.

## 종료와 다시 빌드

```sh
docker compose stop
docker compose start
docker compose logs -f postgres backend ai
docker compose up -d --build
```

`up -d --build`는 코드 변경 후 백엔드와 AI 이미지를 다시 빌드합니다. `docker compose down`으로 컨테이너와 네트워크를 제거해도 기본적으로 DB 볼륨은 남습니다. 호스트에서 DB에 연결할 때 주소는 `127.0.0.1:5433`입니다.
