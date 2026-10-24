/**
 * API에 저장된 과거 데이터에 줄바꿈이 포함되어도, 목록·메타 정보가
 * 회의록 본문처럼 길게 펼쳐지지 않도록 첫 유효 줄만 사용한다.
 */
export function singleLineLabel(value: string | null | undefined, fallback = '회의') {
  // 일부 기존 응답은 실제 줄바꿈 대신 문자 그대로의 "\\n"을 보낸다.
  const firstLine = value?.replace(/\r/g, '').split(/\n|\\n/).find((line) => line.trim())?.trim()
  return firstLine || fallback
}
