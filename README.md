# SafePath Backend

[[한국어]](README.md) | [[日本語]](README.ja.md)

SafePath 서비스의 백엔드 API 서버입니다. Spring Boot 기반으로 구성되어 있으며, 웹 API, 보안, 입력값 검증, JPA 및 PostgreSQL 연동을 위한 의존성을 포함합니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.2
- Gradle Wrapper
- Spring Web MVC, Spring Security, Spring Validation
- Spring Data JPA
- PostgreSQL
- Lombok

## 시작하기

### 요구 사항

- JDK 21
- PostgreSQL (데이터베이스 기능을 사용할 경우)

### 실행

프로젝트 루트에서 다음 명령을 실행합니다.

```powershell
./gradlew.bat bootRun
```

macOS 또는 Linux에서는 다음을 사용합니다.

```bash
./gradlew bootRun
```

별도 포트를 지정하지 않았다면 애플리케이션은 기본적으로 `http://localhost:8080`에서 실행됩니다.

## 테스트

```powershell
./gradlew.bat test
```

현재 테스트는 Spring 애플리케이션 컨텍스트가 정상적으로 로드되는지 확인합니다.

## 빌드

```powershell
./gradlew.bat build
```

빌드 결과물은 `build/libs` 디렉터리에 생성됩니다.

## 설정

기본 설정 파일은 `src/main/resources/application.properties`입니다.

현재 데이터베이스 접속 정보는 포함되어 있지 않습니다. JPA를 이용하는 기능을 추가할 때 PostgreSQL 연결 정보를 환경 변수 또는 별도 프로파일 설정으로 관리하세요. 예시는 다음과 같습니다.

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/safepath
spring.datasource.username=DB_USERNAME
spring.datasource.password=DB_PASSWORD
spring.jpa.hibernate.ddl-auto=update
```

민감한 값은 저장소에 커밋하지 않도록 주의하세요.

## 프로젝트 구조

```text
src/
├── main/
│   ├── java/com/safepath/backend/
│   │   └── BackendApplication.java   # Spring Boot 진입점
│   └── resources/
│       └── application.properties    # 애플리케이션 설정
└── test/
    └── java/com/safepath/backend/    # 테스트 코드
```

## 주요 명령어

| 명령어 | 설명 |
| --- | --- |
| `./gradlew.bat bootRun` | 로컬 개발 서버 실행 (Windows) |
| `./gradlew.bat test` | 테스트 실행 |
| `./gradlew.bat build` | 테스트 후 애플리케이션 빌드 |
| `./gradlew.bat clean` | 빌드 산출물 정리 |
