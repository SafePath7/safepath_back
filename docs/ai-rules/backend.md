# Spring 백엔드 규칙

## 네이밍

| 항목 | 규칙 | 예시 |
|---|---|---|
| 클래스 | PascalCase | `RouteService` |
| 메서드·변수 | camelCase | `calculateSafetyScore()`, `safetyScore` |
| 상수 | UPPER_SNAKE_CASE | `DEFAULT_SEARCH_RADIUS` |
| 패키지 | 영문 소문자 | `com.safepath.backend.route` |
| Boolean | `is`, `has`, `can` 등 | `isConnected` |

의미 없는 축약어를 피하고 프로젝트 내 합의된 명칭을 유지한다. Controller는 `XxxController`, Service는 `XxxService`, Repository는 `XxxRepository`, Entity는 도메인명, DTO는 `XxxRequest`·`XxxResponse`를 사용한다.

## API

- URL은 소문자·복수형 명사·`kebab-case`를 사용하고 CRUD 동사를 넣지 않는다.
- Path Variable과 JSON Request/Response 필드는 `camelCase`를 사용한다.
- 기존 URL·버전·Request/Response 구조와 API 명세의 필드명을 임의로 변경하지 않는다.
- 일반 성공은 200, 생성은 201, 잘못된 요청은 400, 인증 필요는 401, 권한 없음은 403, 데이터 없음은 404, 충돌은 409, 서버 오류는 500을 기본으로 한다. 필요 시 204 등 표준 상태 코드를 쓴다.
- 기존 공통 응답 DTO와 오류 코드·메시지·응답 형식을 재사용하고 새로운 공통 형식을 임의로 만들지 않는다.

## 패키지, 계층, 예외

- 기능 기준 패키지를 사용한다. Controller·Service·Repository·Entity는 기능 패키지 바로 아래, DTO는 `dto`, 기능별 예외는 `exception`, 여러 기능 공통 코드는 `global`에 둔다.
- 흐름은 `Controller → Service → Repository`다. Controller는 HTTP, Service는 비즈니스 로직, Repository는 DB 접근, Entity는 매핑, DTO는 API 데이터 전달을 맡는다.
- Controller의 Repository 직접 호출·비즈니스 로직, Repository의 비즈니스 로직, Entity의 API Request/Response 직접 사용, 계층 무시 의존성을 금지한다.
- 기능별 예외는 도메인 `exception`, 공통 예외는 `global.exception`에 두고 `@RestControllerAdvice`와 기존 공통 예외 처리를 재사용한다.

## 구현 품질

- 생성자 주입을 사용하고 Lombok 사용 시 `@RequiredArgsConstructor`를 사용할 수 있다. 필드 `@Autowired`는 사용하지 않는다.
- 미사용 import·변수·메서드, `System.out.println()`, 불필요한 주석 처리 코드를 제거한다.
- 중복을 최소화하고 지나치게 긴 메서드는 책임별로 분리한다. 기존 SLF4J 로깅 방식을 사용한다.
- 정상 동작 코드를 불필요하게 전면 수정하지 않는다.
