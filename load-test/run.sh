#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

scenario=${1:-}
if [[ -z "$scenario" || ! -f "scenarios/$scenario.js" ]]; then
  echo "사용법: ./load-test/run.sh <시나리오>"
  echo "시나리오: $(ls scenarios | sed 's/\.js$//' | tr '\n' ' ')"
  exit 1
fi

base_url=${LOAD_TEST_BASE_URL:-http://localhost:8081}
grafana_url=${GRAFANA_URL:-http://localhost:3000}

fail() {
  echo "연결 확인 실패: $1"
  exit 1
}

curl -sf "$base_url/api/actuator/health" > /dev/null || fail "앱($base_url)이 응답하지 않습니다. 앱을 먼저 켜세요."
curl -sf "$grafana_url/api/health" > /dev/null || fail "Grafana($grafana_url)가 꺼져 있습니다."
curl -sf -G "$grafana_url/api/datasources/proxy/uid/prometheus/api/v1/query" \
  --data-urlencode 'query=count_over_time(process_uptime_milliseconds[20s])' | grep -q '"result":\[{' \
  || fail "앱 지표가 Grafana로 들어오지 않습니다. 앱을 -Ploadtest와 loadtest 프로파일로 켰는지 확인하세요."
echo "연결 확인 완료: 앱, Grafana, 앱 지표 수신"

{
  echo "SET FOREIGN_KEY_CHECKS = 0;"
  for table in Complaint_Details Complaints Message_file_groups Messages Conversations Rule_Documents Files \
      Invitation_codes Rooms Buildings Refresh_sessions User_agreements User_notifications Notifications Users; do
    echo "TRUNCATE TABLE $table;"
  done
  echo "SET FOREIGN_KEY_CHECKS = 1;"
  cat seed.sql
} | docker compose -f ../compose.yaml exec -T db sh -c \
  'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot --default-character-set=utf8mb4 zipsai_loadtest' > /dev/null

mkdir -p results
result=results/$(date '+%Y%m%d-%H%M%S')-$scenario
echo "테스트 데이터 초기화 완료. 시나리오 시작: $scenario"
K6_OTEL_EXPORTER_PROTOCOL=http/protobuf K6_OTEL_HTTP_EXPORTER_ENDPOINT=localhost:4318 \
K6_OTEL_HTTP_EXPORTER_INSECURE=true K6_OTEL_METRIC_PREFIX=k6_ K6_OTEL_EXPORT_INTERVAL=5s \
  k6 run --out opentelemetry --summary-export "$result.json" "scenarios/$scenario.js" | tee "$result.txt"
echo "결과 저장: load-test/$result.txt"
