#!/bin/bash
# 로컬 개발/테스트용 JWT(HS256) 발급 스크립트. 운영 토큰은 외부 인증 서버가 발급한다.
#
# 사용법: JWT_SECRET=<앱과 같은 비밀키> scripts/dev-token.sh [사번=E12345] [유효시간(초)=3600] [역할=ADMIN]
#         역할은 쉼표로 여러 개 지정할 수 있다: AGENT(청약 접수), UNDERWRITER(심사), OPERATOR(계약 관리), ADMIN(전체)
# 예시:   TOKEN=$(JWT_SECRET=... scripts/dev-token.sh E10001 600 AGENT,OPERATOR)
#         curl -H "Authorization: Bearer $TOKEN" ...
set -euo pipefail

emp_id="${1:-E12345}"
ttl="${2:-3600}"
roles="${3:-ADMIN}"

if [ -z "${JWT_SECRET:-}" ]; then
  echo "JWT_SECRET 환경변수를 앱과 같은 값으로 지정해 주세요." >&2
  exit 1
fi
if [ "${#JWT_SECRET}" -lt 32 ]; then
  echo "JWT_SECRET 은 32바이트 이상이어야 합니다. (HS256)" >&2
  exit 1
fi
if ! [[ "$emp_id" =~ ^[A-Za-z0-9_.@-]+$ ]]; then
  echo "사번은 영문/숫자/_.@- 만 사용할 수 있습니다: $emp_id" >&2
  exit 1
fi
if ! [[ "$ttl" =~ ^[0-9]+$ ]]; then
  echo "유효시간은 초 단위 숫자여야 합니다: $ttl" >&2
  exit 1
fi

if ! [[ "$roles" =~ ^[A-Z_]+(,[A-Z_]+)*$ ]]; then
  echo "역할은 대문자와 _ 로 된 이름을 쉼표로 구분해 주세요: $roles" >&2
  exit 1
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
roles_json="[\"${roles//,/\",\"}\"]"
payload=$(printf '{"sub":"%s","roles":%s,"iat":%d,"exp":%d}' "$emp_id" "$roles_json" "$now" "$((now + ttl))" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
