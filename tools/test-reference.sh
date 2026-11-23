#!/usr/bin/env bash
# Run only on an idle disposable emulator. Uses a separate SQLite DB and fresh test secrets.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
reference_dir=$(cd "${1:?Supply the extracted v0.2 server directory}" && pwd)
serial=${2:?Supply a disposable Android emulator serial}
case "$serial" in emulator-*) ;; *) echo "Use a disposable emulator for the test accounts." >&2; exit 2;; esac
tmp=$(mktemp -d)
api_pid=
tls_pid=
cleanup() {
    adb -s "$serial" reverse --remove tcp:41892 >/dev/null 2>&1 || true
    if [[ -n "$tls_pid" ]]; then kill "$tls_pid" 2>/dev/null || true; fi
    if [[ -n "$api_pid" ]]; then kill "$api_pid" 2>/dev/null || true; fi
    rm -rf "$tmp"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=Circle Alert disposable test' \
    -addext 'subjectAltName=IP:127.0.0.1' -keyout "$tmp/key" -out "$tmp/cert" >/dev/null 2>&1
access=$(openssl rand -hex 24)
PORT=41893 APP_URL=https://127.0.0.1:41892 NODE_ENV=test DB_MODE=sqlite SQLITE_PATH="$tmp/db.sqlite" \
    TEST_ACCESS_CODE="$access" node "$reference_dir/server.js" > "$tmp/api.log" 2>&1 &
api_pid=$!
node "$repo_dir/tools/test-tls.cjs" "$tmp/cert" "$tmp/key" > "$tmp/tls.log" 2>&1 &
tls_pid=$!
for _ in {1..40}; do
    if curl --noproxy '*' --cacert "$tmp/cert" -fsS https://127.0.0.1:41892/healthz 2>/dev/null | grep -q '"ready":true'; then break; fi
    sleep 0.25
done
curl --noproxy '*' --cacert "$tmp/cert" -fsS https://127.0.0.1:41892/healthz | grep -q '"ready":true'
adb -s "$serial" reverse tcp:41892 tcp:41892 >/dev/null
adb -s "$serial" install --no-streaming -r "$repo_dir/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$serial" install --no-streaming -r "$repo_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s "$serial" shell -n input keyevent 82
for permission in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb -s "$serial" shell -n pm revoke uk.co.agentengines.circlealert "android.permission.$permission" >/dev/null 2>&1 || true
done
ca=$(openssl x509 -in "$tmp/cert" -outform DER | openssl base64 -A)
adb -s "$serial" shell -n am instrument -w -r -e testCa "$ca" -e testAccess "$access" \
    uk.co.agentengines.circlealert.test/androidx.test.runner.AndroidJUnitRunner | tee "$tmp/results"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$tmp/results"
