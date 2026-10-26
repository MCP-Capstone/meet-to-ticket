import type { TicketStatus } from './api'

/**
 * API에 저장된 과거 데이터에 줄바꿈이 포함되어도, 목록·메타 정보가
 * 회의록 본문처럼 길게 펼쳐지지 않도록 첫 유효 줄만 사용한다.
 */
export function singleLineLabel(value: string | null | undefined, fallback = '회의') {
  // 일부 기존 응답은 실제 줄바꿈 대신 문자 그대로의 "\\n"을 보낸다.
  const firstLine = value?.replace(/\r/g, '').split(/\n|\\n/).find((line) => line.trim())?.trim()
  return firstLine || fallback
}

// 티켓 상태는 목록·상세·결과 화면 어디서나 같은 말로 보여 준다.
// SKIPPED(모의 실행 발행)도 실제 발행과 같은 화면으로 보여 준다.
export const ticketStatusLabel: Record<TicketStatus, string> = {
  ASSIGNEE_REQUIRED: '담당 지정 필요', PENDING_REVIEW: '검토 대기', CONFIRMED: '승인됨', REJECTED: '거절됨',
  PUBLISHING: '발행 중', PUBLISHED: '발행됨', LINKED: '연결됨', UPDATED: '갱신됨', SKIPPED: '발행됨', FAILED: '발행 확인 필요',
}

// 같은 제목의 회의를 구분할 수 있게 회의 묶음 머리에 등록 날짜와 시각을 붙인다. 예: 10/25 14:03
export function registeredAt(value: string) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return null
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
