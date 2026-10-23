import type { MyTicket } from './api/types'

export function meetingGroups(tickets: MyTicket[]) {
  const groups = new Map<string, MyTicket[]>()
  for (const ticket of tickets) {
    const key = `${ticket.projectId}:${ticket.meetingId}`
    const group = groups.get(key) ?? []
    group.push(ticket)
    groups.set(key, group)
  }
  return [...groups.entries()].map(([key, tickets]) => ({ key, tickets, first: tickets[0] }))
}
export function publishSelection(tickets: MyTicket[]) {
  const confirmed = tickets.filter((ticket) => ticket.status === 'CONFIRMED')
  const pending = tickets.filter((ticket) => ticket.status === 'PENDING_REVIEW' || ticket.status === 'ASSIGNEE_REQUIRED').length
  const reason = pending ? `아직 검토하지 않은 티켓이 ${pending}건 있습니다.`
    : tickets.some((ticket) => ticket.status === 'PUBLISHING') ? '이미 발행 중인 티켓이 있습니다.'
    : !confirmed.length ? '발행할 확인된 티켓이 없습니다.'
    : confirmed.some((ticket) => ticket.publishTarget !== 'GITHUB') ? '현재 일괄 발행은 GitHub 티켓만 지원합니다. 다른 도구 대상 티켓이 포함되어 있습니다.'
    : null
  return { confirmed, pending, rejected: tickets.filter((ticket) => ticket.status === 'REJECTED').length, reason }
}
export function safeIssueUrl(url: string | null) {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' && parsed.hostname === 'github.com' && !parsed.username && !parsed.password ? parsed.href : null
  } catch { return null }
}
