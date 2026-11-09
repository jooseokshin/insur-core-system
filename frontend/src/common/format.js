// 화면 표시용 형식 변환. 백엔드는 날짜를 'YYYY-MM-DD', 일시를 'YYYY-MM-DDTHH:mm:ss(.SSS)' (업무 시간대)로 보낸다.

const won = new Intl.NumberFormat('ko-KR');

/** 금액: 1,234,567 */
export function money(value) {
  if (value === null || value === undefined || value === '') return '-';
  return won.format(Number(value));
}

/** 일시: 2026-10-31 20:51 */
export function dateTime(value) {
  if (!value) return '-';
  return String(value).replace('T', ' ').slice(0, 16);
}

/** 날짜: 2026-10-31 */
export function date(value) {
  return value ? String(value).slice(0, 10) : '-';
}

/** 업무 시간대(Asia/Seoul) 기준 오늘에 days 를 더한 날짜 문자열 */
export function seoulDate(days = 0) {
  const now = new Date(Date.now() + days * 86400000);
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(now);
}

/** 날짜 문자열에 연·일을 더한다 (보험기간 기본값용) */
export function addYears(dateText, years) {
  const [y, m, d] = dateText.split('-').map(Number);
  const result = new Date(Date.UTC(y + years, m - 1, d));
  return result.toISOString().slice(0, 10);
}

/** 두 날짜 사이 일수 (종기 당일 제외) */
export function daysBetween(from, to) {
  if (!from || !to) return null;
  const [a, b] = [from, to].map((t) => {
    const [y, m, d] = t.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
  });
  return Math.round((b - a) / 86400000);
}

/** 입력칸의 금액 문자열(쉼표 허용)을 숫자로. 비었으면 null */
export function parseAmount(text) {
  if (text === null || text === undefined) return null;
  const cleaned = String(text).replace(/[,\s]/g, '');
  if (cleaned === '') return null;
  return /^\d+$/.test(cleaned) ? Number(cleaned) : NaN;
}

/** 날짜 문자열에 일수를 더한다 (단기 보험 기본 종료일용) */
export function addDays(dateText, days) {
  const [y, m, d] = dateText.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + days));
  return t.toISOString().slice(0, 10);
}
