# 회원 탈퇴와 S3 원본 삭제 설계 메모

현재 구현된 기능이 아니라, S3 저장 경로와 객체 소유권 규칙을 확인한 뒤 구현할 작업이다. 현재 코드는 파일이 있으면 탈퇴를 보류한다.

## 정책 변경

탈퇴 시 본인 소유 귀가 세션은 보존 요청 여부와 관계없이 삭제한다. 이는 기존 위험 세션 보관 정책과 충돌하므로 PR과 배포 검토에 명시한다. Firebase Auth 계정은 유지한다.

## 구현 조건

- `voice_profiles.audio_file_url`과 `risk_event_medias.storage_url`의 실제 형식, 허용 버킷, 사용자별 키 규칙 및 객체 공유 여부를 확인한다. DB에 URL이 있다는 사실만으로 해당 객체를 영구 삭제할 권한을 추론하지 않는다.
- 작업과 객체 목록을 S3 호출 전에 DB 트랜잭션에 기록하고, 같은 사용자에 대한 중복 요청과 동시 작업을 방지한다. 재시작 후 미완료 객체를 재시도할 수 있어야 한다.
- 작업이 진행되는 동안 새 파일이나 참조가 생기지 않도록 업로드 및 기록 생성 경로와 탈퇴 상태를 연동한다. 다른 회원이 참조하는 객체는 소유권 정책을 확인하기 전 삭제하지 않는다.
- 각 객체의 모든 버전과 삭제 마커를 확인해 삭제하고, 모든 객체 삭제가 확인된 뒤에만 관련 DB 데이터를 트랜잭션으로 정리한다. 실패와 작업 중 상태는 완료로 응답하지 않는다.
- S3와 DB는 하나의 트랜잭션이 아니므로, S3 삭제 후 DB 기록 전에 서버가 중단되어도 같은 객체를 안전하게 재처리해야 한다.

## S3 권한 및 운영 조건

일반 버킷에 대해 버전 목록 조회용 `s3:ListBucketVersions`, 객체 삭제용 `s3:DeleteObject`, 특정 버전과 삭제 마커 삭제용 `s3:DeleteObjectVersion`을 대상 버킷과 객체 범위에 맞춰 부여한다. 버전 목록은 페이지를 끝까지 읽고 정확히 일치하는 키만 처리한다. 단순 삭제는 버전 관리 버킷에서 삭제 마커만 만들 수 있으므로 영구 삭제로 간주하지 않는다.

Object Lock 보존 기간이나 법적 보류가 있으면 삭제가 거부될 수 있다. 보호를 우회하거나 보류를 해제하지 않고 실패 상태로 남겨 운영자가 처리하도록 한다. 복제 버킷이나 백업의 삭제 정책은 별도로 확인해야 한다.

참고: [AWS 버전 객체 삭제](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjectVersions.html), [버전 목록 권한](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectVersions.html), [Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html).
