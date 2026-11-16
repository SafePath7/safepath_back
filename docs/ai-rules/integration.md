# 연동과 보안 규칙

## Firebase Authentication

- 기존 Firebase Authentication, 인증 필터, 보안 설정과 사용자 인증 흐름을 우선 재사용한다.
- 인증·권한 검증을 제거하거나 우회하지 않는다.
- 사용자 식별은 서버에서 검증된 인증 정보를 기준으로 처리한다.

## WebSocket, STOMP, FCM

- 기존 WebSocket/STOMP 연결·인증 구조, STOMP CONNECT 검증, 채널 참여·구독 권한 검증을 유지한다.
- 인증되지 않은 사용자의 비인가 채널 접근을 막고, 클라이언트 사용자 ID를 검증 없이 신뢰하지 않는다.
- 기존 채널 관리·메시지 중계·FCM 및 알림 기능을 중복 구현하지 않고 우선 재사용한다.

## 민감 정보

- API/Secret Key, Firebase 서비스 계정 JSON, Access/Refresh Token, DB 비밀번호·인증 정보를 코드나 Git에 넣지 않는다.
- 토큰을 로그에 출력하지 않는다.
- 민감 정보는 환경변수 또는 기존 비밀 관리 방식을 사용하며, 테스트 편의를 위해 보안 검증을 제거하지 않는다.
