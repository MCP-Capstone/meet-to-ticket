import type { MyTicket, PublishTarget } from './api/types'

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
    : null
  return { confirmed, pending, rejected: tickets.filter((ticket) => ticket.status === 'REJECTED').length, reason }
}
export const toolName: Record<PublishTarget, string> = { GITHUB: 'GitHub', NOTION: 'Notion', SLACK: 'Slack' }

// 발행 대상별로 결과 링크가 가리킬 수 있는 도메인. 그 밖의 주소는 링크로 만들지 않는다.
const allowedHost: Record<PublishTarget, (host: string) => boolean> = {
  GITHUB: (host) => host === 'github.com',
  NOTION: (host) => host === 'app.notion.com' || host === 'notion.so' || host.endsWith('.notion.so') || host.endsWith('.notion.site'),
  SLACK: (host) => host === 'slack.com' || host.endsWith('.slack.com'),
}

export function safePublishUrl(url: string | null, target: PublishTarget) {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' && !parsed.port && allowedHost[target](parsed.hostname) && !parsed.username && !parsed.password ? parsed.href : null
  } catch { return null }
}

export function safeIssueUrl(url: string | null) {
  return safePublishUrl(url, 'GITHUB')
}
