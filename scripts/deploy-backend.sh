#!/usr/bin/env bash
set -Eeuo pipefail

COMMIT_SHA="${1:-}"
APP_DIR="/home/ssm-user/safepath"
CURRENT_JAR="$APP_DIR/backend.jar"
BACKUP_JAR="$APP_DIR/backend.jar.previous"
NEW_JAR="$APP_DIR/backend.jar.new"
SERVICE="safepath-backend"
BUCKET="safepath-dev-risk-media-kr"

HEALTH_URL="http://localhost:8080/api/safety-facilities?zoom=3&southWestLat=35.85&southWestLng=128.58&northEastLat=35.86&northEastLng=128.59"

# S3 경로에 사용할 커밋 ID 확인
if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "오류: 올바른 커밋 ID가 필요합니다."
  exit 1
fi

# 배포가 동시에 실행되지 않도록 잠금
exec 9>/tmp/safepath-backend-deploy.lock
if ! flock -n 9; then
  echo "오류: 다른 배포가 진행 중입니다."
  exit 1
fi

cleanup() {
  rm -f "$NEW_JAR"
}
trap cleanup EXIT

wait_for_health() {
  for attempt in {1..30}; do
    if systemctl is-active --quiet "$SERVICE" &&
       [[ "$(curl -s -o /dev/null -w '%{http_code}' \
         --max-time 5 "$HEALTH_URL" || true)" == "200" ]]; then
      return 0
    fi

    echo "백엔드 시작 대기 중: $attempt/30"
    sleep 5
  done

  return 1
}

rollback() {
  # 복구 중 같은 신호가 들어와 함수가 다시 실행되는 것을 방지
  trap - INT TERM

  echo "배포 실패: 이전 JAR로 복구합니다."

  # 임시 파일을 거쳐 이전 JAR로 교체
  if ! cp -p "$BACKUP_JAR" "$NEW_JAR" ||
     ! mv -f "$NEW_JAR" "$CURRENT_JAR"; then
    echo "오류: 이전 JAR을 복구하지 못했습니다."
    exit 1
  fi

  if ! systemctl restart "$SERVICE"; then
    echo "오류: 이전 버전 서비스 재시작에 실패했습니다."
    exit 1
  fi

  if wait_for_health; then
    echo "이전 버전 복구 및 상태 확인 완료"
  else
    echo "오류: 이전 버전 복구 후에도 정상 응답을 확인하지 못했습니다."
  fi

  # 복구가 성공해도 새 버전 배포는 실패로 기록
  exit 1
}

if [[ ! -f "$CURRENT_JAR" ]]; then
  echo "오류: 현재 실행 중인 JAR이 없습니다: $CURRENT_JAR"
  exit 1
fi

echo "새 JAR 다운로드: $COMMIT_SHA"
aws s3 cp \
  "s3://$BUCKET/deploy/$COMMIT_SHA/backend.jar" \
  "$NEW_JAR" \
  --region ap-northeast-2

if [[ ! -s "$NEW_JAR" ]]; then
  echo "오류: 다운로드한 JAR이 비어 있습니다."
  exit 1
fi

# 서비스 계정이 읽을 수 있도록 소유자와 권한 설정
chown ssm-user:ssm-user "$NEW_JAR"
chmod 644 "$NEW_JAR"

echo "현재 JAR 백업"
cp -p "$CURRENT_JAR" "$BACKUP_JAR"

# 교체 중 중단 요청이 오면 이전 버전 복구 시도
trap 'rollback' INT TERM

echo "새 JAR 반영"
mv -f "$NEW_JAR" "$CURRENT_JAR"

echo "백엔드 서비스 재시작"
if ! systemctl restart "$SERVICE"; then
  rollback
fi

if ! wait_for_health; then
  rollback
fi

trap - INT TERM
echo "배포 및 백엔드 상태 확인 완료: $COMMIT_SHA"
