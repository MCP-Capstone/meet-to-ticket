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
  const reason = pending ? `검토 대기 ${pending}건 남음`
    : tickets.some((ticket) => ticket.status === 'PUBLISHING') ? '발행 중인 티켓이 있어요'
    : !confirmed.length ? '승인한 티켓이 없어요'
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

// 발행 결과를 이만큼 기다리면 오래 걸린다고 안내한다 (#302).
export const SLOW_PUBLISH_SECONDS = 120

// 묶음 생성 시각(서버)을 쓰고, 없거나 해석할 수 없으면 화면을 연 시각을 쓴다.
export function batchStartedAt(createdAt: string | null | undefined, fallback: number) {
  const parsed = createdAt ? Date.parse(createdAt) : NaN
  return Number.isFinite(parsed) ? parsed : fallback
}

export function formatElapsed(seconds: number) {
  const sec = Math.max(0, Math.floor(seconds))
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
}

// "약 16분"은 Backend 결과 대기 기한(PUBLISH_RESULT_TIMEOUT 기본 15분) + 기한 정리 주기(1분)다. 기본값을 바꾸면 함께 고친다.
export function slowPublishNotice(elapsedSeconds: number) {
  return elapsedSeconds >= SLOW_PUBLISH_SECONDS
    ? '평소보다 오래 걸리고 있습니다. 이 화면을 닫아도 발행은 계속되며, 늦어도 약 16분 뒤에는 결과가 정리됩니다. 결과는 내 티켓에서도 확인할 수 있습니다.'
    : null
}
