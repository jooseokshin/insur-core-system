#!/bin/bash
# 실행 중인 앱에 대한 통합 점검: 인증 → 청약 → 조회 → 상태 변경 → 이력, 역할/데이터 권한과 오류 응답을 curl 로 확인한다.
#
# 사전 준비: docker compose up -d, 앱 실행(JWT_SECRET 지정), curl · jq · openssl 설치
# 사용법:   JWT_SECRET=<앱과 같은 비밀키> scripts/e2e-check.sh [--cleanup]
#   BASE_URL  앱 주소 (기본 http://localhost:8080)
#   --cleanup 점검이 만든 계약/담보/이력을 끝나고 지운다. (docker 컨테이너 insur-postgres 의 DB 를 사용)
#
# 점검이 실제 DB 에 데이터를 만든다. 거래 UUID 는 실행마다 달라지는 e2e-<epoch>-* 형태이고, 채번 일련번호는 되돌리지 않는다.
# 실패 항목이 하나라도 있으면 종료 코드 1 로 끝난다.
set -uo pipefail

cd "$(dirname "$0")/.."
BASE="${BASE_URL:-http://localhost:8080}/api/contract"
RUN="e2e-$(date +%s)"
CLEANUP=0; [ "${1:-}" = "--cleanup" ] && CLEANUP=1

for bin in curl jq openssl; do
  command -v "$bin" >/dev/null || { echo "$bin 이 필요합니다." >&2; exit 2; }
done
[ -n "${JWT_SECRET:-}" ] || { echo "JWT_SECRET 환경변수를 앱과 같은 값으로 지정해 주세요." >&2; exit 2; }
curl -s -o /dev/null --max-time 3 "${BASE_URL:-http://localhost:8080}/api/contract/nothing" -X POST \
  || { echo "앱에 연결할 수 없습니다: ${BASE_URL:-http://localhost:8080}" >&2; exit 2; }

pass=0; fail=0

tok() { scripts/dev-token.sh "$1" 600 "$2"; }

# call <이름> <기대 HTTP> <토큰|none> <경로> <JSON>  → CODE, RESP 설정
call() {
  local name=$1 expect=$2 token=$3 path=$4 json=$5 auth=()
  [ "$token" != none ] && auth=(-H "Authorization: Bearer $token")
  local url="$BASE/$path"
  case "$path" in premium/*|admin/*) url="${BASE%/contract}/$path" ;; esac   # 보험료 산출은 /api/premium, 요율 관리는 /api/admin 아래에 있다
  local out; out=$(curl -s -w '\n%{http_code}' -X POST "$url" -H 'Content-Type: application/json' ${auth[@]+"${auth[@]}"} -d "$json")
  CODE=$(tail -1 <<<"$out"); RESP=$(sed '$d' <<<"$out")
  if [ "$CODE" = "$expect" ]; then
    pass=$((pass+1)); printf '  OK   %-56s %s %s\n' "$name" "$CODE" "$(jq -r '.header.responseCode // empty' <<<"$RESP" 2>/dev/null)"
  else
    fail=$((fail+1)); printf '  FAIL %-56s 기대 %s, 실제 %s  %s\n' "$name" "$expect" "$CODE" "$RESP"
  fi
}

# check <이름> <실제값> <기대값>
check() {
  if [ "$2" = "$3" ]; then pass=$((pass+1)); printf '  OK   %-56s %s\n' "$1" "$2"
  else fail=$((fail+1)); printf '  FAIL %-56s 기대 [%s], 실제 [%s]\n' "$1" "$3" "$2"; fi
}

AGENT1=$(tok E100 AGENT); AGENT2=$(tok E200 AGENT); UW=$(tok U300 UNDERWRITER); OP=$(tok O400 OPERATOR); ADM=$(tok A500 ADMIN)
PH="PH-$RUN"   # 점검 데이터만 골라내기 위한 이 실행 전용 계약자/피보험자 ID
IN="IN-$RUN"
XCODE="X${RUN: -8}"   # 요율 관리 점검용 새 담보코드 (점검이 끝나면 지운다)
APPLY='{"header":{"globalTxId":"'$RUN'-1","empId":"HACKER"},"body":{"insrClsCd":"01","startDate":"2026-10-10","endDate":"2027-10-10","policyholderId":"'$PH'","insuredId":"'$IN'","totalPremium":850000,"rating":{"driverAge":35,"discountGrade":"3"},"coverages":[{"cvrgCd":"CA001","isrdAmt":100000000,"prem":500000},{"cvrgCd":"CA002","isrdAmt":20000000,"prem":350000}]}}'

echo "[1] 인증"
call "토큰 없음 → 401" 401 none apply "$APPLY"
call "잘못된 토큰 → 401" 401 "garbage" apply "$APPLY"

echo "[1-1] 보험료 산출"
QUOTE='{"header":{"globalTxId":"'$RUN'-q"},"body":{"insrClsCd":"01","startDate":"2026-10-10","endDate":"2027-10-10","rating":{"driverAge":35,"discountGrade":"3"},"coverages":[{"cvrgCd":"CA001","isrdAmt":100000000},{"cvrgCd":"CA002","isrdAmt":20000000}]}}'
call "토큰 없음 → 401" 401 none premium/quote "$QUOTE"
call "AGENT 보험료 산출 → 200" 200 "$AGENT1" premium/quote "$QUOTE"
check "  365일, 담보별 보험료" "$(jq -r '"\(.body.days) \(.body.coverages[0].prem) \(.body.coverages[1].prem)"' <<<"$RESP")" "365 500000 350000"
check "  총보험료 = 이어지는 청약의 총보험료(850000)" "$(jq -r '.body.totalPremium' <<<"$RESP")" "850000"
call "자동차 보험에 화재 담보 → 400" 400 "$AGENT1" premium/quote "${QUOTE/CA001/FI001}"
check "  오류코드 E-PRM-001" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-001"
call "담보코드 중복 → 400" 400 "$AGENT1" premium/quote "${QUOTE/CA002/CA001}"
check "  오류코드 E-PRM-002" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-002"

# 요청 JSON 에 rating 객체를 넣은 변형은 문자열 치환 대신 jq 로 만든다
with_rating() { jq -c --argjson r "$2" '.body.rating = $r' <<<"$1"; }
call "요율 계수(만 22세 × 1등급 = 1.2) 반영 산출 → 200" 200 "$AGENT1" premium/quote "$(with_rating "$QUOTE" '{"driverAge":22,"discountGrade":"1"}')"
check "  계수 곱 1.2, 총보험료 1020000" "$(jq -r '"\(.body.combinedFactor) \(.body.totalPremium)"' <<<"$RESP")" "1.2 1020000"
call "자동차에 건물 구조 등급 → 400" 400 "$AGENT1" premium/quote "$(with_rating "$QUOTE" '{"driverAge":35,"discountGrade":"3","structureGrade":"1"}')"
check "  오류코드 E-PRM-003" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-003"
call "계수표에 없는 만 17세 → 400" 400 "$AGENT1" premium/quote "$(with_rating "$QUOTE" '{"driverAge":17,"discountGrade":"3"}')"
check "  오류코드 E-PRM-004" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-004"
# 필수 계수 정책: 자동차는 운전자 나이·할인할증 등급이 필수
call "필수 계수 없이 산출 → 400" 400 "$AGENT1" premium/quote "$(jq -c 'del(.body.rating)' <<<"$QUOTE")"
check "  오류코드 E-PRM-005" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-005"
call "필수 계수 일부(운전자 나이만) → 400" 400 "$AGENT1" premium/quote "$(with_rating "$QUOTE" '{"driverAge":35}')"
check "  빠진 계수(할인할증 등급)를 안내" "$(jq -r '.header.responseMsg | contains("DISCOUNT_GRADE(discountGrade)") and (contains("DRIVER_AGE")|not)' <<<"$RESP")" "true"
call "필수 계수 없이 청약 → 400" 400 "$AGENT1" apply "$(jq -c --arg tx "$RUN-norating" 'del(.body.rating) | .header.globalTxId = $tx' <<<"$APPLY")"
check "  오류코드 E-PRM-005" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-005"

echo "[2] 청약 (AGENT)"
call "UNDERWRITER 는 청약 불가 → 403" 403 "$UW" apply "$APPLY"
call "AGENT E100 청약 → 200" 200 "$AGENT1" apply "$APPLY"
CNO=$(jq -r '.body.contractNo' <<<"$RESP"); ANO=$(jq -r '.body.applNo' <<<"$RESP")
check "응답 empId 는 요청 헤더가 아니라 토큰 sub" "$(jq -r '.header.empId' <<<"$RESP")" "E100"
check "증권번호 형식" "$([[ $CNO =~ ^POL-CAR-[0-9]{4}-[0-9]{6}$ ]] && echo ok)" "ok"
call "같은 globalTxId 재전송 → 200" 200 "$AGENT1" apply "$APPLY"
check "재전송 증권번호 동일" "$(jq -r '.body.contractNo' <<<"$RESP")" "$CNO"
DIFF=${APPLY/\"insuredId\":\"$IN\"/\"insuredId\":\"$IN-X\"}   # 같은 거래 UUID, 피보험자만 다른 요청
call "같은 globalTxId + 다른 내용 → 409" 409 "$AGENT1" apply "$DIFF"
check "  오류코드 E-CTR-019" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-019"
call "다른 사번이 같은 globalTxId + 다른 내용 → 409" 409 "$AGENT2" apply "$DIFF"
check "  내용 일치 여부 대신 사번 오류 E-CTR-015" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-015"
call "다른 사번이 같은 globalTxId → 409" 409 "$AGENT2" apply "$APPLY"
check "  오류코드 E-CTR-015" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-015"
TAMPER=${APPLY/\"prem\":500000/\"prem\":400000}; TAMPER=${TAMPER/\"prem\":350000/\"prem\":450000}; TAMPER=${TAMPER/$RUN-1/$RUN-tamper}
call "합계만 맞추고 담보별 보험료 변경 → 400" 400 "$AGENT1" apply "$TAMPER"
check "  오류코드 E-CTR-020 (산출 보험료와 불일치)" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-020"
# 요율 계수 조건으로 청약: 이후 목록 점검의 계약자 조건과 섞이지 않도록 계약자 ID 를 따로 쓴다
RAPPLY=$(jq -c --arg tx "$RUN-rating" --arg ph "$PH-R" '.header.globalTxId = $tx | .body.policyholderId = $ph
  | .body.rating = {"driverAge":22,"discountGrade":"1"}
  | .body.coverages[0].prem = 600000 | .body.coverages[1].prem = 420000 | .body.totalPremium = 1020000' <<<"$APPLY")
RAPPLY_BASE=$(jq -c --arg tx "$RUN-rating-base" '.header.globalTxId = $tx
  | .body.coverages[0].prem = 500000 | .body.coverages[1].prem = 350000 | .body.totalPremium = 850000' <<<"$RAPPLY")   # 계수를 무시한 기본 보험료
call "요율 계수 조건(만 22세 · 1등급) + 계수 반영 보험료 청약 → 200" 200 "$AGENT1" apply "$RAPPLY"
RCNO=$(jq -r '.body.contractNo' <<<"$RESP")
call "같은 조건에서 계수를 무시한 기본 보험료 청약 → 400" 400 "$AGENT1" apply "$RAPPLY_BASE"
check "  오류코드 E-CTR-020" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-020"
call "계수 조건이 저장되어 조회된다" 200 "$AGENT1" inquiry "{\"header\":{},\"body\":{\"contractNo\":\"$RCNO\"}}"
check "  운전자 나이 22, 할인할증 1등급" "$(jq -r '"\(.body.driverAge) \(.body.discountGrade)"' <<<"$RESP")" "22 1"
UNKNOWN=${APPLY/CA002/ZZ999}; UNKNOWN=${UNKNOWN/$RUN-1/$RUN-unknown}
call "요율표에 없는 담보코드로 청약 → 400" 400 "$AGENT1" apply "$UNKNOWN"
check "  오류코드 E-PRM-001" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-PRM-001"
BAD=${APPLY/\"totalPremium\":850000/\"totalPremium\":1}; BAD=${BAD/$RUN-1/$RUN-bad}
call "총보험료 불일치 → 400" 400 "$AGENT1" apply "$BAD"
check "  오류코드 E-CTR-003" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-003"

echo "[3] 조회 범위"
INQ="{\"header\":{\"globalTxId\":\"$RUN-inq\"},\"body\":{\"contractNo\":\"$CNO\"}}"
call "접수자 본인(AGENT E100) 조회 → 200" 200 "$AGENT1" inquiry "$INQ"
check "  담보 2건, 이력은 응답에 없음" "$(jq -r '"\(.body.coverages|length)/\(.body|has("statusHistory"))"' <<<"$RESP")" "2/false"
call "다른 AGENT(E200) 조회 → 404 (존재 숨김)" 404 "$AGENT2" inquiry "$INQ"
check "  오류코드 E-CTR-010" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-010"
call "UNDERWRITER 는 전체 조회 → 200" 200 "$UW" inquiry "$INQ"
call "청약번호로 조회 → 200" 200 "$ADM" inquiry "{\"header\":{},\"body\":{\"applNo\":\"$ANO\"}}"
check "  header.globalTxId 자동 채번" "$([ -n "$(jq -r '.header.globalTxId' <<<"$RESP")" ] && echo ok)" "ok"

echo "[4] 상태 변경"
ST() { echo "{\"header\":{\"globalTxId\":\"$RUN-st-$2\"},\"body\":{\"contractNo\":\"$CNO\",\"ctrStatCd\":\"$1\"}}"; }
call "AGENT 는 상태 변경 불가 → 403" 403 "$AGENT1" status-change "$(ST 20 a)"
call "OPERATOR 는 승인(20) 불가 → 403" 403 "$OP" status-change "$(ST 20 b)"
call "UNDERWRITER 가 해지(30) 시도 → 403" 403 "$UW" status-change "$(ST 30 c)"
call "청약 → 해지 직행은 ADMIN 도 → 409" 409 "$ADM" status-change "$(ST 30 d)"
check "  오류코드 E-CTR-014" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-014"
call "UNDERWRITER 승인(10→20) → 200" 200 "$UW" status-change "$(ST 20 e)"
check "  변경 전/후" "$(jq -r '"\(.body.beforeCtrStatCd)->\(.body.afterCtrStatCd)"' <<<"$RESP")" "10->20"
call "응답을 못 받아 같은 거래 UUID 로 승인 재전송 → 200 (처음 결과)" 200 "$UW" status-change "$(ST 20 e)"
check "  변경 전/후가 처음과 같음" "$(jq -r '"\(.body.beforeCtrStatCd)->\(.body.afterCtrStatCd)"' <<<"$RESP")" "10->20"
call "같은 거래 UUID 로 다른 상태 요청 → 409" 409 "$UW" status-change "$(ST 90 e)"
check "  오류코드 E-CTR-019" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-019"
call "UNDERWRITER 승인 재시도(새 거래 UUID, 20→20) → 409" 409 "$UW" status-change "$(ST 20 f)"
EN() { echo "{\"header\":{\"globalTxId\":\"$RUN-en-$2\"},\"body\":{\"contractNo\":\"$CNO\",$1}}"; }
call "AGENT 는 계약 변경(배서) 불가 → 403" 403 "$AGENT1" endorse "$(EN "\"insuredId\":\"$IN-E\"" a)"
call "UNDERWRITER 피보험자 변경 → 200" 200 "$UW" endorse "$(EN "\"insuredId\":\"$IN-E\"" b)"
check "  바뀐 항목 1건(INSURED_ID)" "$(jq -r '"\(.body.changes|length) \(.body.changes[0].fieldNm)"' <<<"$RESP")" "1 INSURED_ID"
call "같은 거래 UUID 로 배서 재전송 → 200 (처음 결과)" 200 "$UW" endorse "$(EN "\"insuredId\":\"$IN-E\"" b)"
check "  바뀐 항목 1건 그대로" "$(jq -r '.body.changes|length' <<<"$RESP")" "1"
call "같은 거래 UUID 로 다른 내용 배서 → 409" 409 "$UW" endorse "$(EN "\"insuredId\":\"$IN-Z\"" b)"
check "  오류코드 E-CTR-019" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-019"
call "현재 값과 같은 변경 → 400" 400 "$UW" endorse "$(EN "\"insuredId\":\"$IN-E\"" c)"
check "  오류코드 E-CTR-022" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-022"
call "OPERATOR 피보험자를 원래 값으로 되돌림 → 200" 200 "$OP" endorse "$(EN "\"insuredId\":\"$IN\"" d)"
call "배서 이력 조회 → 200" 200 "$AGENT1" endorse-history "{\"header\":{},\"body\":{\"contractNo\":\"$CNO\"}}"
check "  2건 (변경, 되돌림)" "$(jq -r '"\(.body.totalElements) \(.body.items[0].beforeVal)>\(.body.items[0].afterVal) \(.body.items[1].afterVal)"' <<<"$RESP")" "2 $IN>$IN-E $IN"
call "다른 AGENT 는 배서 이력도 조회 불가 → 404" 404 "$AGENT2" endorse-history "{\"header\":{},\"body\":{\"contractNo\":\"$CNO\"}}"
call "OPERATOR 해지(20→30) → 200" 200 "$OP" status-change "$(ST 30 g)"
TODAY=$(TZ=Asia/Seoul date +%F)
check "  해지일은 서울 기준 오늘" "$(jq -r '.body.termDate' <<<"$RESP")" "$TODAY"
check "  환급보험료는 0 이상 총보험료(850000) 이하" "$(jq -r '(.body.refundPremium >= 0 and .body.refundPremium <= 850000)' <<<"$RESP")" "true"
call "해지된 계약의 배서 → 409" 409 "$UW" endorse "$(EN "\"insuredId\":\"$IN-E\"" e)"
check "  오류코드 E-CTR-021" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-021"
call "종료된 계약 변경 → 409" 409 "$ADM" status-change "$(ST 20 h)"
call "모르는 상태코드 → 400" 400 "$ADM" status-change "$(ST 99 i)"
check "  오류코드 E-CTR-013" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-013"
call "없는 계약 → 404" 404 "$ADM" status-change '{"header":{},"body":{"contractNo":"POL-CAR-2026-999999","ctrStatCd":"20"}}'

echo "[5] 이력 확인"
call "ADMIN 조회 → 200" 200 "$ADM" inquiry "$INQ"
check "  최종 상태 30" "$(jq -r '.body.ctrStatCd' <<<"$RESP")" "30"
call "상태 이력 조회(전체) → 200" 200 "$ADM" status-history "{\"header\":{},\"body\":{\"contractNo\":\"$CNO\"}}"
check "  이력 상태 흐름" "$(jq -r '[.body.items[] | "\(.beforeCtrStatCd // "-")>\(.afterCtrStatCd)"] | join(" ")' <<<"$RESP")" "->10 10>20 20>30"
check "  이력 사번 흐름" "$(jq -r '[.body.items[].empId] | join(" ")' <<<"$RESP")" "E100 U300 O400"

echo "[5-1] 이력 페이징"
HP() { echo "{\"header\":{\"globalTxId\":\"$RUN-hp\"},\"body\":{\"contractNo\":\"$CNO\"$1}}"; }
call "이력 0페이지(size 2) → 200" 200 "$ADM" status-history "$(HP ',"page":0,"size":2')"
check "  전체 3건 / 2페이지 / 이 페이지 2건" "$(jq -r '"\(.body.totalElements)/\(.body.totalPages)/\(.body.items|length)"' <<<"$RESP")" "3/2/2"
call "이력 1페이지(size 2) → 200" 200 "$ADM" status-history "$(HP ',"page":1,"size":2')"
check "  이 페이지 1건, 마지막 이력은 20>30" "$(jq -r '"\(.body.items|length) \(.body.items[0].beforeCtrStatCd)>\(.body.items[0].afterCtrStatCd)"' <<<"$RESP")" "1 20>30"
call "범위를 벗어난 페이지 → 200, 빈 목록" 200 "$ADM" status-history "$(HP ',"page":9,"size":2')"
check "  빈 목록, 전체 건수는 유지" "$(jq -r '"\(.body.items|length)/\(.body.totalElements)"' <<<"$RESP")" "0/3"
call "page/size 생략 → 200, 20건 기준" 200 "$AGENT1" status-history "$(HP '')"
check "  size 20, 3건" "$(jq -r '"\(.body.size)/\(.body.items|length)"' <<<"$RESP")" "20/3"
call "다른 AGENT(E200) → 404" 404 "$AGENT2" status-history "$(HP '')"
call "size 101 → 400" 400 "$ADM" status-history "$(HP ',"size":101')"
check "  오류코드 E-CTR-016" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-016"

echo "[5-2] 계약 목록"
LS() { echo "{\"header\":{\"globalTxId\":\"$RUN-ls\"},\"body\":{\"policyholderId\":\"$PH\"$1}}"; }
call "ADMIN 목록(이 점검의 계약자) → 200" 200 "$ADM" list "$(LS '')"
check "  1건, 상태 30, 접수자 E100" "$(jq -r '"\(.body.totalElements) \(.body.items[0].ctrStatCd) \(.body.items[0].regEmpId)"' <<<"$RESP")" "1 30 E100"
check "  요약 응답에 담보/이력 없음" "$(jq -r '.body.items[0] | has("coverages") or has("statusHistory")' <<<"$RESP")" "false"
call "접수자 본인(AGENT E100) 목록 → 200" 200 "$AGENT1" list "$(LS '')"
check "  1건" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "다른 AGENT(E200) 목록 → 200, 0건" 200 "$AGENT2" list "$(LS '')"
check "  0건 (남의 계약은 목록에서도 안 보임)" "$(jq -r '.body.totalElements' <<<"$RESP")" "0"
call "UNDERWRITER 목록 → 200" 200 "$UW" list "$(LS '')"
check "  1건 (전체 범위)" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "상태 20 조건 → 200" 200 "$ADM" list "$(LS ',"ctrStatCd":"20"')"
check "  0건 (이미 해지됨)" "$(jq -r '.body.totalElements' <<<"$RESP")" "0"
call "상태 30 + 종목 01 조건 → 200" 200 "$ADM" list "$(LS ',"ctrStatCd":"30","insrClsCd":"01"')"
check "  1건" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "접수일 내일 이후 조건 → 200" 200 "$ADM" list "$(LS ",\"regDateFrom\":\"$(date -v+1d +%F 2>/dev/null || date -d tomorrow +%F)\"")"
check "  0건" "$(jq -r '.body.totalElements' <<<"$RESP")" "0"
call "증권번호 앞부분(POL-CAR-) 일치 → 200" 200 "$ADM" list "$(LS ',"contractNo":"POL-CAR-"')"
check "  1건" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "증권번호 와일드카드(%)는 글자 그대로 → 200" 200 "$ADM" list "$(LS ',"contractNo":"%"')"
check "  0건" "$(jq -r '.body.totalElements' <<<"$RESP")" "0"
call "청약번호 앞부분(APP-CAR-) 일치 → 200" 200 "$ADM" list "$(LS ',"applNo":"APP-CAR-"')"
check "  1건" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "피보험자 ID 일치 → 200" 200 "$ADM" list "$(LS ",\"insuredId\":\"$IN\"")"
check "  1건" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "총보험료 최소 850000(경계) → 200" 200 "$ADM" list "$(LS ',"premiumMin":850000')"
check "  1건 (경계값 포함)" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "총보험료 최소 850001 → 200" 200 "$ADM" list "$(LS ',"premiumMin":850001')"
check "  0건" "$(jq -r '.body.totalElements' <<<"$RESP")" "0"
call "보험시기 2026-10-10 하루 범위 → 200" 200 "$ADM" list "$(LS ',"startDateFrom":"2026-10-10","startDateTo":"2026-10-10"')"
check "  1건 (양 끝 포함)" "$(jq -r '.body.totalElements' <<<"$RESP")" "1"
call "보험시기 2026-10-11 이후 → 200" 200 "$ADM" list "$(LS ',"startDateFrom":"2026-10-11"')"
check "  0건" "$(jq -r '.body.totalElements' <<<"$RESP")" "0"
call "정렬 총보험료 오름차순 → 200" 200 "$ADM" list "$(LS ',"sortBy":"TOTAL_PREMIUM","sortDir":"ASC"')"
call "허용하지 않는 정렬 기준 → 400" 400 "$ADM" list "$(LS ',"sortBy":"CONTRACT_NO"')"
check "  오류코드 E-CTR-018" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-018"
call "총보험료 최소 > 최대 → 400" 400 "$ADM" list "$(LS ',"premiumMin":200,"premiumMax":100')"
check "  오류코드 E-CTR-017" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-017"
call "모르는 종목코드 → 400" 400 "$ADM" list "$(LS ',"insrClsCd":"99"')"
call "접수일 시작 > 종료 → 400" 400 "$ADM" list "$(LS ',"regDateFrom":"2026-10-10","regDateTo":"2026-10-09"')"
check "  오류코드 E-CTR-017" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-017"

echo "[5-3] 요율/계수 관리"
TODAY=$(TZ=Asia/Seoul date +%F)
LATER=$(TZ=Asia/Seoul date -v+30d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+30 days' +%F)
DAY_BEFORE_LATER=$(TZ=Asia/Seoul date -v+29d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+29 days' +%F)
PAST=$(TZ=Asia/Seoul date -v-1d +%F 2>/dev/null || TZ=Asia/Seoul date -d 'yesterday' +%F)
RV() { echo "{\"header\":{\"globalTxId\":\"$RUN-rv-$3\"},\"body\":{\"insrClsCd\":\"01\",\"cvrgCd\":\"$XCODE\",\"cvrgNm\":\"점검용 특약\",\"annualRate\":$1,\"effFrom\":\"$2\"}}"; }
XQ() { echo "{\"header\":{},\"body\":{\"insrClsCd\":\"01\",\"startDate\":\"$1\",\"endDate\":\"$2\",\"rating\":{\"driverAge\":35,\"discountGrade\":\"3\"},\"coverages\":[{\"cvrgCd\":\"$XCODE\",\"isrdAmt\":10000000}]}}"; }
call "AGENT 는 요율 목록 조회 불가 → 403" 403 "$AGENT1" admin/premium/rate-list '{"header":{},"body":{}}'
call "UNDERWRITER 는 요율 목록 조회 → 200" 200 "$UW" admin/premium/rate-list '{"header":{},"body":{"insrClsCd":"01"}}'
check "  자동차 담보 요율이 나온다 (CA001 포함)" "$(jq -r '[.body.items[].cvrgCd] | index("CA001") != null' <<<"$RESP")" "true"
call "UNDERWRITER 는 요율 개정 불가 → 403" 403 "$UW" admin/premium/rate-revise "$(RV 0.01 "$TODAY" a)"
call "ADMIN 이 새 담보 요율 등록(오늘부터 1%) → 200" 200 "$ADM" admin/premium/rate-revise "$(RV 0.01 "$TODAY" a)"
check "  CREATE, 적용 중" "$(jq -r '"\(.body.action) \(.body.current.status)"' <<<"$RESP")" "CREATE ACTIVE"
call "새 담보로 보험료 산출(1천만 × 1% × 365일) → 200" 200 "$AGENT1" premium/quote "$(XQ "$TODAY" "$(TZ=Asia/Seoul date -v+365d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+365 days' +%F)")"
check "  총보험료 100000" "$(jq -r '.body.totalPremium' <<<"$RESP")" "100000"
call "30일 뒤부터 2% 로 개정 → 200" 200 "$ADM" admin/premium/rate-revise "$(RV 0.02 "$LATER" b)"
check "  REVISE, 이전 행 종료일은 시작일 전날" "$(jq -r '"\(.body.action) \(.body.previous.effTo) \(.body.current.status)"' <<<"$RESP")" "REVISE $DAY_BEFORE_LATER FUTURE"
call "개정 전날 시작 보험은 기존 요율 → 200" 200 "$AGENT1" premium/quote "$(XQ "$DAY_BEFORE_LATER" "$(TZ=Asia/Seoul date -v+394d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+394 days' +%F)")"
check "  1% 적용 (100000)" "$(jq -r '.body.totalPremium' <<<"$RESP")" "100000"
call "개정일에 시작하는 보험은 새 요율 → 200" 200 "$AGENT1" premium/quote "$(XQ "$LATER" "$(TZ=Asia/Seoul date -v+395d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+395 days' +%F)")"
check "  2% 적용 (200000)" "$(jq -r '.body.totalPremium' <<<"$RESP")" "200000"
call "같은 거래 UUID 재전송 → 200 (처음 결과)" 200 "$ADM" admin/premium/rate-revise "$(RV 0.02 "$LATER" b)"
call "같은 거래 UUID + 다른 요율 → 409" 409 "$ADM" admin/premium/rate-revise "$(RV 0.03 "$LATER" b)"
check "  오류코드 E-CTR-019" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-019"
call "과거 시작일로 개정 → 400" 400 "$ADM" admin/premium/rate-revise "$(RV 0.03 "$PAST" c)"
check "  오류코드 E-ADM-002" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-ADM-002"
call "최근 개정과 같은 시작일로 다시 개정 → 409" 409 "$ADM" admin/premium/rate-revise "$(RV 0.03 "$LATER" d)"
check "  오류코드 E-ADM-003" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-ADM-003"
call "요율 계수 목록(자동차 연령) → 200" 200 "$OP" admin/premium/factor-list '{"header":{},"body":{"insrClsCd":"01","factorType":"DRIVER_AGE"}}'
check "  연령 구간 5개 이상" "$(jq -r '(.body.items|length) >= 5' <<<"$RESP")" "true"
call "겹치는 연령 구간을 새로 만들면 DB 가 거절 → 409" 409 "$ADM" admin/premium/factor-revise "{\"header\":{\"globalTxId\":\"$RUN-fv-a\"},\"body\":{\"insrClsCd\":\"01\",\"factorType\":\"DRIVER_AGE\",\"rangeFrom\":20,\"rangeTo\":30,\"factorNm\":\"겹침\",\"factorValue\":1.4,\"effFrom\":\"$LATER\"}}"
check "  오류코드 E-ADM-004" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-ADM-004"

echo "[5-1] 필수 계수 정책 관리"
call "정책 목록(자동차) → 200" 200 "$OP" admin/premium/policy-list '{"header":{},"body":{"insrClsCd":"01"}}'
check "  필수 계수 2종(DISCOUNT_GRADE, DRIVER_AGE)" "$(jq -r '[.body.items[] | select(.required and .status=="ACTIVE") | .factorType] | sort | join(",")' <<<"$RESP")" "DISCOUNT_GRADE,DRIVER_AGE"
PV() { echo "{\"header\":{\"globalTxId\":\"$RUN-pol-$2\"},\"body\":{\"insrClsCd\":\"01\",\"factorType\":\"DRIVER_AGE\",\"required\":$1,\"effFrom\":\"$LATER\"}}"; }
call "UNDERWRITER 는 정책 개정 불가 → 403" 403 "$UW" admin/premium/policy-revise "$(PV false a)"
call "ADMIN 이 운전자 나이를 30일 뒤부터 선택으로 개정 → 200" 200 "$ADM" admin/premium/policy-revise "$(PV false a)"
check "  이전 필수 → 이후 선택" "$(jq -r '"\(.body.previous.required) \(.body.current.required) \(.body.current.status)"' <<<"$RESP")" "true false FUTURE"
call "같은 거래 UUID 재전송 → 200" 200 "$ADM" admin/premium/policy-revise "$(PV false a)"
call "같은 거래 UUID + 다른 내용 → 409" 409 "$ADM" admin/premium/policy-revise "$(PV true a)"
check "  오류코드 E-CTR-019" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-019"
LATER_END=$(TZ=Asia/Seoul date -v+395d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+395 days' +%F)
QL=$(jq -c --arg s "$LATER" --arg e "$LATER_END" '.body.startDate = $s | .body.endDate = $e | del(.body.rating.driverAge)' <<<"$QUOTE")
call "개정일 시작 보험은 운전자 나이 없이 산출 → 200" 200 "$AGENT1" premium/quote "$QL"
check "  필수 계수는 할인할증 등급만" "$(jq -r '.body.requiredFactors | join(",")' <<<"$RESP")" "DISCOUNT_GRADE"

echo "[5-2] 감사 이력 조회"
call "AGENT 는 감사 이력 조회 불가 → 403" 403 "$AGENT1" admin/premium/audit-list '{"header":{},"body":{}}'
call "정책 개정 감사 이력 조회(대상 종류 + 사번 + 오늘) → 200" 200 "$UW" admin/premium/audit-list "{\"header\":{},\"body\":{\"targetType\":\"POLICY\",\"empId\":\"A500\",\"fromDate\":\"$TODAY\",\"toDate\":\"$TODAY\"}}"
check "  방금 개정한 거래가 맨 앞(최근순), 필수 true → false" "$(jq -r '.body.items[0] | "\(.globalTxId) \(.targetKey) \(.beforeVal) \(.afterVal)"' <<<"$RESP")" "$RUN-pol-a 01/DRIVER_AGE true false"
call "알 수 없는 대상 종류 → 400" 400 "$UW" admin/premium/audit-list '{"header":{},"body":{"targetType":"COLOR"}}'
check "  오류코드 E-ADM-001" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-ADM-001"
call "페이지 크기 0 → 400" 400 "$UW" admin/premium/audit-list '{"header":{},"body":{"size":0}}'
check "  오류코드 E-CTR-016" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-016"

echo "[5-4] 계약 갱신"
# 보험종기가 20일 뒤인 1년짜리 유효 계약을 만든다 (갱신 가능 기간 안). 보험료는 산출 API 결과를 그대로 쓴다.
RN_END=$(TZ=Asia/Seoul date -v+20d +%F 2>/dev/null || TZ=Asia/Seoul date -d '+20 days' +%F)
RN_START=$(TZ=Asia/Seoul date -v+20d -v-1y +%F 2>/dev/null || TZ=Asia/Seoul date -d '+20 days -1 year' +%F)
RQ=$(jq -c --arg s "$RN_START" --arg e "$RN_END" '.body.startDate = $s | .body.endDate = $e' <<<"$QUOTE")
call "갱신 대상 계약의 보험료 산출 → 200" 200 "$AGENT1" premium/quote "$RQ"
RN_P1=$(jq -r '.body.coverages[0].prem' <<<"$RESP"); RN_P2=$(jq -r '.body.coverages[1].prem' <<<"$RESP"); RN_TOT=$(jq -r '.body.totalPremium' <<<"$RESP")
RNAPPLY=$(jq -c --arg tx "$RUN-rn-src" --arg ph "$PH-RN" --arg s "$RN_START" --arg e "$RN_END" --argjson p1 "$RN_P1" --argjson p2 "$RN_P2" --argjson t "$RN_TOT" \
  '.header.globalTxId = $tx | .body.policyholderId = $ph | .body.startDate = $s | .body.endDate = $e
   | .body.coverages[0].prem = $p1 | .body.coverages[1].prem = $p2 | .body.totalPremium = $t' <<<"$APPLY")
call "갱신 대상 계약 청약 → 200" 200 "$AGENT1" apply "$RNAPPLY"
RNO=$(jq -r '.body.contractNo' <<<"$RESP")
RNST() { echo "{\"header\":{\"globalTxId\":\"$RUN-rn-st$2\"},\"body\":{\"contractNo\":\"$RNO\",\"ctrStatCd\":\"$1\"}}"; }
RN() { echo "{\"header\":{\"globalTxId\":\"$RUN-rn-$2\"},\"body\":{\"contractNo\":\"$1\"}}"; }
call "유효가 아닌(청약) 계약 갱신 → 409" 409 "$AGENT1" renew "$(RN "$RNO" a)"
check "  오류코드 E-CTR-023" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-023"
call "갱신 대상 계약 승인(10→20) → 200" 200 "$UW" status-change "$(RNST 20 1)"
call "UNDERWRITER 는 갱신 불가 → 403" 403 "$UW" renew "$(RN "$RNO" b)"
call "다른 AGENT 의 갱신 → 404 (남의 계약)" 404 "$AGENT2" renew "$(RN "$RNO" c)"
call "없는 계약 갱신 → 404" 404 "$AGENT1" renew "$(RN POL-NOPE d)"
call "갱신 보험료 미리보기 → 200 (저장 없음)" 200 "$AGENT1" renew-quote "$(RN "$RNO" pv)"
check "  원 계약 총보험료와 담보 2건 비교" "$(jq -r '"\(.body.previousTotalPremium) \(.body.coverages|length)"' <<<"$RESP")" "$RN_TOT 2"
RN_PV=$(jq -r '.body.totalPremium' <<<"$RESP")
call "UNDERWRITER 는 미리보기 불가 → 403" 403 "$UW" renew-quote "$(RN "$RNO" pv)"
call "다른 AGENT 의 미리보기 → 404" 404 "$AGENT2" renew-quote "$(RN "$RNO" pv)"
call "AGENT 가 유효 계약 갱신 → 200" 200 "$AGENT1" renew "$(RN "$RNO" e)"
check "  미리보기 보험료 = 실제 갱신 보험료" "$(jq -r '.body.totalPremium' <<<"$RESP")" "$RN_PV"
RENO=$(jq -r '.body.contractNo' <<<"$RESP")
check "  새 청약(10), 원 계약 연결, 보험시기 = 원 보험종기" "$(jq -r '"\(.body.ctrStatCd) \(.body.renewedFrom) \(.body.startDate)"' <<<"$RESP")" "10 $RNO $RN_END"
check "  갱신 보험종기는 1년 뒤" "$(jq -r '.body.endDate' <<<"$RESP")" "$(TZ=Asia/Seoul date -j -v+1y -f %F "$RN_END" +%F 2>/dev/null || TZ=Asia/Seoul date -d "$RN_END +1 year" +%F)"
check "  원 계약 총보험료 표시" "$(jq -r '.body.previousTotalPremium' <<<"$RESP")" "$RN_TOT"
call "같은 거래 UUID 재전송 → 200 (같은 갱신 계약)" 200 "$AGENT1" renew "$(RN "$RNO" e)"
check "  증권번호 동일" "$(jq -r '.body.contractNo' <<<"$RESP")" "$RENO"
call "같은 거래 UUID + 다른 원 계약 → 409" 409 "$AGENT1" renew "$(RN "$CNO" e)"
check "  오류코드 E-CTR-019" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-019"
call "이미 갱신한 계약을 새 거래로 갱신 → 409" 409 "$AGENT1" renew "$(RN "$RNO" f)"
check "  오류코드 E-CTR-025" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-025"
call "이미 갱신한 계약은 미리보기도 → 409" 409 "$AGENT1" renew-quote "$(RN "$RNO" pv)"
check "  오류코드 E-CTR-025" "$(jq -r '.header.responseCode' <<<"$RESP")" "E-CTR-025"
call "갱신 계약 조회에 원 계약이 나온다" 200 "$AGENT1" inquiry "{\"header\":{},\"body\":{\"contractNo\":\"$RENO\"}}"
check "  renewedFrom" "$(jq -r '.body.renewedFrom' <<<"$RESP")" "$RNO"

echo "[6] 공통 규약"
call "JSON 오류 → 400 E-CMN-002" 400 "$ADM" apply '{ "header": '
call "존재하지 않는 경로 → 404" 404 "$ADM" nothing '{}'

echo
echo "결과: 통과 $pass / 실패 $fail   (증권번호 $CNO, 거래 UUID 접두사 $RUN)"

if [ "$CLEANUP" = 1 ]; then
  docker exec insur-postgres psql -U postgres -d insur_core_db -q -c "
    delete from tb_prm_rate      where cvrg_cd = '$XCODE';
    delete from tb_prm_factor_policy where insr_cls_cd = '01' and factor_type = 'DRIVER_AGE' and eff_from > date '2000-01-01';
    update tb_prm_factor_policy set eff_to = null where insr_cls_cd = '01' and factor_type = 'DRIVER_AGE';
    delete from tb_prm_audit     where global_tx_id like '$RUN-%';
    delete from tb_ctr_endorse   where contract_no in (select contract_no from tb_ctr_lgd where global_tx_id like '$RUN-%');
    delete from tb_ctr_stat_hist where contract_no in (select contract_no from tb_ctr_lgd where global_tx_id like '$RUN-%');
    delete from tb_ctr_cvrg      where contract_no in (select contract_no from tb_ctr_lgd where global_tx_id like '$RUN-%');
    delete from tb_ctr_lgd       where global_tx_id like '$RUN-%';" \
    && echo "점검 데이터를 지웠습니다." || echo "정리 실패: 점검 데이터가 남아 있습니다 (거래 UUID $RUN-%)." >&2
else
  echo "점검 데이터가 DB 에 남아 있습니다. 지우려면 --cleanup 으로 실행하거나 거래 UUID '$RUN-%' 인 행을 삭제하세요."
fi

[ "$fail" -eq 0 ]
