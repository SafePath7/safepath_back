# REST 연결 준비 및 WebSocket 보안 검토 (2026-11-15)

## 구현 범위와 인증 경계

공개 Controller·REST 경로·HTTP 상태·오류 코드는 추가하거나 변경하지 않았다.
GuardianMonitoringQueryService는 미래 Controller용 내부 Bean이다.

```text
기존 FirebaseAuthenticationFilter
→ Spring Security가 제공한 Authentication
→ GuardianMonitoringQueryService
→ UserService.findUserIdByFirebaseUid(principal String)
→ DB guardianId
→ 기존 GuardianMonitoringService
→ 권한 검증 후 내부 데이터 반환
```

getMonitoringStatus(UUID, Authentication)는 ActiveSession을 반환한다.
getMonitoredLocation(UUID, Authentication)는 Optional<LocationUpdateRequest>를 반환한다.
guardianId를 별도 요청 인자로 받지 않는다. null·미인증·익명·String이 아닌 principal·공백 UID를 거부한다.
Authentication은 반드시 기존 보안 체인이 제공한 값을 전달해야 한다. JSON·헤더에서 Authentication이나
principal을 직접 생성하면 안 된다. 이 Service 자체가 Firebase 서명 검증을 다시 수행하는 것은 아니다.
기존 business Service의 Long 인자는 여전히 신뢰된 내부 호출자를 전제로 한다.

기존 검증은 활성 세션 확인 → 소유자 본인 차단 → ACCEPTED → 요청별 권한 → 세션 선택 여부다.
상태는 STATUS, 위치는 LOCATION으로 분리한다. 종료된 세션은 없어진 것으로 처리한다.
인증·회원·권한 조회 장애는 전파하며 데이터를 반환하지 않는다.
위치 미수신은 Optional.empty()다. HTTP 변환은 아직 하지 않는다.

## 승인 대기 API와 DTO 후보

예정 경로만 확인되었으며 현재 공개되지 않았다.

- GET /api/guardian/monitoring/sessions/{sessionId}/status
- GET /api/guardian/monitoring/sessions/{sessionId}/location

sessionId는 경로 UUID, 요청자는 검증된 Authentication에서 확인한다.
guardianId query/body/header는 요청자 식별에 사용하지 않는다.

DTO 후보 (아직 클래스나 HTTP 계약으로 구현하지 않음):

- MonitoringStatusResponse: id(UUID), startedAt(Instant). 기존 ActiveSessionResponse 재사용도 선택지다.
- MonitoredLocationResponse: latitude(double), longitude(double), accuracyMeters(Double), capturedAt(Instant).

status 값의 정의·응답 래퍼·위치 미수신·오래된 위치·서버 수신 시각 포함 여부는 팀 승인 대상이다.
WebSocket LocationUpdateRequest를 내부에서 사용한다는 사실이 REST JSON 계약을 확정하지 않는다.
향후 Controller는 query Service 호출과 승인된 DTO 변환만 담당하도록 권장한다.

## 오류 검토: 현재 예외와 제안은 별개

오류 최상위 형식 {code, message}만 확정됐다. 아래 HTTP는 제안이며 코드에 반영하지 않았다.

| 상황 | 현재 동작/예외 | 제안 HTTP |
|---|---|---|
| 토큰 없음·무효 | 보안 체인 401, 내부 경계 BadCredentialsException | 401 |
| DB 미가입 | AccessDeniedException | 403 |
| 잘못된 UUID | Controller 미구현; 향후 Spring 경로 변환 실패 예상 | 400 |
| 미수락 | AccessDeniedException | 403 또는 존재 은닉 정책에 따른 404 |
| STATUS 없음 | AccessDeniedException | 동일 |
| LOCATION 없음 | AccessDeniedException | 동일 |
| 미선택 | AccessDeniedException | 동일 |
| 종료·미존재 세션 | NoSuchElementException | 404 |
| 위치 미수신 | Optional.empty(), 예외 없음 | 204 또는 승인된 위치 없음 응답 |
| 권한·회원 DB 장애 | DataAccessException 등 원래 예외 전파 | 503(가용성 장애), 기타 500 |

현재 AccessDeniedException은 미가입·권한 거부를 구분하는 타입이 아니다.
NoSuchElementException도 위치 없음과 세션 없음을 혼용하면 안 된다.
분류별 오류 코드를 제공하려면 내부 예외 분류 또는 명시적 변환 정책부터 승인해야 한다.
필터 단계의 401은 body 없이 반환될 수 있어, 모든 오류에 JSON을 보장하려면 보안 계층 계약도 필요하다.
SafeReturnSessionExceptionHandler는 기존 세션 Controller에만 적용한다. 무조건 확장하면
권한 거부가 USER_NOT_REGISTERED로 잘못 변환될 수 있으므로 이번에는 변경하지 않았다.

## WebSocket 읽기 전용 보안 점검

근거 파일은 websocket/StompAuthenticationChannelInterceptor.java,
InMemoryChannelRegistry.java, LocationUpdateController.java, WebSocketSessionEventListener.java,
config/WebSocketConfig.java, config/SecurityConfig.java다. 이번에는 수정하거나 공격 재현 테스트를 실행하지 않았다.

| 항목 | 현재 구현과 한계 |
|---|---|
| CONNECT | Bearer ID 토큰 검증 → DB ID → 세션 속성 저장 |
| 지정된 위치 SUBSCRIBE | 인증된 세션 속성과 채널 참가자 집합 검사 |
| 미선택 보호자 | 새 시작 경로는 선택자만 참가자로 등록; SUBSCRIBE 자체는 선택 Store를 재조회하지 않음 |
| LOCATION 없는 보호자 | 새 시작 시 검사; SUBSCRIBE/재연결 시 DB 권한 재검사 없음 |
| /topic 직접 SEND | preSend에서 SEND 제한 없음. Controller를 우회하는 브로커 전송 위험 |
| 미허용 destination | 위치 prefix/suffix 불일치면 검사 없이 return. 다른 토픽·와일드카드 구독 우회 위험 |
| 권한 취소 | 고정 참가자 집합·브로커 구독은 유지되며 outbound 재인가 없음 |
| 세션 종료 | close()는 Registry만 삭제. 정상 relay의 후속 SEND는 거부하지만 브로커 구독은 삭제하지 않음 |
| 재연결 | Firebase 재검증 및 고정 참가자 재검사만 수행. 관계·LOCATION 최신 상태는 확인하지 않음 |
| channelId 노출 | 정상 정확한 토픽은 토큰+참가자 검사로 보호. destination 우회 위험 때문에 ID 비밀성만으로 안전 보장 불가 |

### 위험도와 수정 위치

높음: 미허용 SUBSCRIBE의 기본 허용. StompAuthenticationChannelInterceptor의 위치 검사 밖 return과
WebSocketConfig의 /topic simple broker 조합으로 허용 범위 밖 구독이 브로커에 도달할 수 있다.
채널 토픽 UUID를 정확히 식별·검증하고 그 외 구독 및 패턴 구독은 기본 거부해야 한다.

높음: /topic 직접 SEND 기본 허용. 위치 Controller의 소유자·DTO 검사를 우회해 메시지를 주입할 수 있는 구조다.
인터셉터 또는 합의된 메시지 인가 계층에서 /app의 정확한 위치 SEND만 허용하고 브로커 직접 SEND를 거부해야 한다.

높음: 권한 취소 후 수신 유지. 기존 가입 보호자는 소유자의 정상 송신이 계속되는 동안 DB 권한 변경과
무관하게 수신할 수 있다. SUBSCRIBE의 LOCATION 검증과 별개로 취소 시 기존 구독·연결을 제거하거나
배달 직전에 권한을 재검증하는 수명주기 정책이 필요하다.

중간: 종료 후 브로커 구독 잔존. 정상 relay는 채널이 없으면 거부하지만, 종료 전 검사를 통과한
진행 중 메시지는 뒤늦게 도착할 수 있다. 직접 브로커 SEND 공백까지 결합하면 잔존 구독에 임의 메시지가
전달될 위험이 있다. Registry close와 브로커 구독 종료는 동일 기능이 아니다.

위험도는 정적 코드 검토에 따른 평가다. 현재 구현의 차단 공백은 확인했지만 실제 우회 수신·주입은
이번 단계에서 실행 검증하지 않았다. 김규식 담당자 수정 후 로컬 보안 통합 테스트가 필요하다.

공식 참고: https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html
이 문서도 브로커 prefix로의 클라이언트 SEND 거부와 명시 허용 이외 MESSAGE/SUBSCRIBE 기본 거부를 설명한다.

## 테스트와 후속 작업

기존 테스트로 STATUS/LOCATION 분리, 미선택·종료 거부, 위치 미수신을 재사용한다.
GuardianMonitoringQueryServiceTest는 실제 FirebaseAuthenticationFilter(토큰 검증 결과만 mock)를 사용하여
인증 principal→DB ID 전달, 위조 request ID 무시, 미인증·미가입·조회 장애를 검증한다.
테스트에 Controller나 REST 경로를 추가하지 않는다. 아직 존재하지 않는 실제 보호자 REST의
end-to-end 보안을 검증한 것은 아니며, 향후 Controller는 동일 경계를 사용해야 한다.
GuardianPermissionBeanTest에 내부 조회 Bean의 주입 확인을 추가한다.

후속 순서: 오류·DTO 계약 승인 → Controller/한정 예외 처리 → 필터 오류 JSON 정책 →
WebSocket destination 기본 거부·취소 수명주기 → 실제 위치 저장 연결 → 실제 토큰·프론트 통합 검증.

### 실제 실행 결과

```powershell
.\gradlew.bat classes testClasses test --tests "com.safepath.backend.session.*" --tests "com.safepath.backend.websocket.*" --tests "com.safepath.backend.config.SecurityConfigIntegrationTest" --tests "com.safepath.backend.guardian.GuardianAccessTests"
```

2026-11-15: BUILD SUCCESSFUL, 기존 134개 + 신규 인증 경계 7개 = 총 141개 통과.
실패·오류·건너뜀 0. main/test 컴파일 및 새 Bean을 포함한 기존 앱 기동 회귀 통과.
WebSocket 공격 재현이나 미구현 보호자 REST의 Postman 테스트는 실행하지 않았다.