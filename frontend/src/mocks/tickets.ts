import type { MyTicket, MyTicketList, TicketAction, TicketEditRequest, TicketPublicationRequest, TicketDedupe, TicketPublicationPreview, User } from '../api/types'
import { ApiRequestError } from '../api/client'
import type { PublishBatchResult } from '../api/types'
import { publishSelection } from '../batchPublish'
import { canUseExistingIssue } from '../ticketPublication'
import { validMeetingDate } from '../meetingInput'
import { listMockMembers, listMockProjects } from './projects'

type StoredTicket = MyTicket & { assigneeUserId: number; backlog?: Omit<TicketPublicationPreview['backlog'], 'description'> }
const KEY = 'mock-my-tickets-v2'
const fail = (status: number, code: string, message: string) => new ApiRequestError(status, { code, message })
function read(user: User): StoredTicket[] {
  const raw = sessionStorage.getItem(KEY)
  if (raw) return JSON.parse(raw) as StoredTicket[]
  const project = listMockProjects(user).find((item) => item.projectId === 1)
  if (!project) return []
  const members = listMockMembers(project.projectId, user)
  const owner = members.find((member) => member.userId === 1)
  if (!owner) return []
  const statuses: MyTicket['status'][] = ['PENDING_REVIEW', 'PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'FAILED', 'FAILED']
  const titles = ['CSV 빈 결과 오류 수정', '다운로드 완료 안내 개선', '파일명 날짜 형식 정리', '내보내기 도움말 작성', '내보내기 권한 오류 처리', '대용량 CSV 분할 다운로드']
  const failures: Record<number, MyTicket['publishFailure']> = {
    4: { errorCode: 'GITHUB_FORBIDDEN', errorMessage: 'GitHub 저장소 권한이 없습니다.', failureCategory: 'REPOSITORY_PERMISSION', retryable: true, outcomeUnknown: false },
    5: { errorCode: 'RESULT_UNKNOWN', errorMessage: '요청은 보냈지만 결과를 받지 못했습니다.', failureCategory: 'OTHER', retryable: true, outcomeUnknown: true },
  }
  // TASK-032: 화면 확인용 예시. 실제 GitHub 이슈를 조회하지 않는다.
  const dedupeBase: TicketDedupe = { judged: true, skipped: null, relation: 'UNRELATED', issue: null, overlap: false, reason: '기존 업무와 관계없는 새로운 업무입니다.', before: null, after: null, changedByRecheck: false }
  const dedupes: (TicketDedupe | undefined)[] = [
    { ...dedupeBase, relation: 'RELATED', issue: { number: 12, title: '예시: CSV 다운로드', state: 'closed', url: 'https://github.com/example/meet-to-ticket/issues/12' }, overlap: true, reason: '같은 CSV 화면이지만 빈 결과 오류에 대한 추가 작업입니다.' },
    { ...dedupeBase, relation: 'CHANGE', issue: { number: 24, title: '예시: 다운로드 안내', state: 'open', url: 'https://github.com/example/meet-to-ticket/issues/24' }, reason: '기존 안내 문구와 접근성 조건을 보완합니다.', before: '다운로드 시작 안내만 표시한다.', after: '다운로드 완료 안내를 표시한다.\n스크린 리더에도 완료 상태를 전달한다.' },
    { ...dedupeBase, relation: 'SAME', issue: { number: 36, title: '예시: 파일명 규칙', state: 'open', url: 'https://github.com/example/meet-to-ticket/issues/36' }, reason: '기존 이슈와 같은 업무입니다.', changedByRecheck: true },
    { ...dedupeBase, judged: false, relation: null, reason: null, skipped: 'timeout' },
    dedupeBase,
    undefined,
  ]
  const seed: StoredTicket[] = statuses.map((status, index) => ({
    ticketId: `21000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, title: titles[index], status,
    publishTarget: index === 3 ? 'NOTION' : 'GITHUB', processingType: index === 1 ? 'UPDATE' : index === 2 ? 'LINK' : 'CREATE', dedupe: dedupes[index], publishFailure: failures[index] ?? null,
    projectId: project.projectId, projectName: project.name, meetingId: 2101, meetingTitle: 'CSV 내보내기 점검 회의',
    taskId: '21000000-0000-4000-8000-000000000100', createdAt: '2026-10-23T10:00:00+09:00',
    backlog: { tasks: [`${titles[index]}에 필요한 상세 작업을 구현한다.`], acceptanceCriteria: ['정상 및 오류 상황을 검증한다.'], evidenceQuotes: ['회의에서 해당 작업을 진행하기로 했다.'], openQuestions: index === 0 ? ['빈 결과 안내 문구를 확인한다.'] : [] },
    description: `${titles[index]}에 필요한 작업 내용을 확인하고 수정해 주세요.`,
    due: { text: index === 0 ? '다음 회의 전까지' : null, date: index === 0 ? '2026-10-30' : null, time: null },
    assignee: { projectMemberId: owner.projectMemberId, name: owner.name }, assigneeUserId: 1,
  }))
  write(seed)
  return seed
}
const write = (tickets: StoredTicket[]) => sessionStorage.setItem(KEY, JSON.stringify(tickets))
export function deleteMockProjectTickets(projectId: number) {
  const raw = sessionStorage.getItem(KEY)
  if (raw) write((JSON.parse(raw) as StoredTicket[]).filter((ticket) => ticket.projectId !== projectId))
}
const view = ({ assigneeUserId: _owner, backlog: _backlog, ...ticket }: StoredTicket): MyTicket => structuredClone(ticket)
export function listMockTickets(user: User, projectId?: number): MyTicketList {
  settleMockBatches(user)
  const tickets = read(user).filter((ticket) => ticket.assigneeUserId === user.userId && (projectId === undefined || ticket.projectId === projectId))
  return {
    counts: { pendingReview: tickets.filter((t) => t.status === 'PENDING_REVIEW').length, confirmed: tickets.filter((t) => t.status === 'CONFIRMED').length, rejected: tickets.filter((t) => t.status === 'REJECTED').length, failed: tickets.filter((t) => t.status === 'FAILED').length },
    tickets: tickets.map(view),
  }
}
function owned(user: User, ticketId: string) {
  const tickets = read(user)
  const ticket = tickets.find((t) => t.ticketId === ticketId)
  if (!ticket) throw fail(404, 'TICKET_NOT_FOUND', '티켓을 찾을 수 없습니다.')
  if (ticket.assigneeUserId !== user.userId) throw fail(403, 'TICKET_ASSIGNEE_REQUIRED', '티켓 담당자만 변경할 수 있습니다.')
  return { tickets, ticket }
}
export function editMockTicket(user: User, ticketId: string, body: TicketEditRequest) {
  const { tickets, ticket } = owned(user, ticketId)
  if (ticket.status !== 'PENDING_REVIEW') throw fail(409, 'TICKET_STATUS_CONFLICT', '검토 대기에서만 수정할 수 있습니다.')
  if (!Object.keys(body).length || (body.description !== undefined && (!body.description.trim() || body.description.length > 4000))) throw fail(400, 'VALIDATION_FAILED', '본문과 수정 항목을 확인해 주세요.')
  if (body.due && ((body.due.text?.length ?? 0) > 200 || (body.due.date && !validMeetingDate(body.due.date)) || (body.due.time && !/^([01]\d|2[0-3]):[0-5]\d$/.test(body.due.time)))) throw fail(400, 'VALIDATION_FAILED', '기한 형식을 확인해 주세요.')
  if (body.assigneeMemberId !== undefined) {
    const member = listMockMembers(ticket.projectId, user).find((m) => m.projectMemberId === body.assigneeMemberId && m.userId !== null)
    if (!member) throw fail(404, 'PROJECT_MEMBER_NOT_FOUND', '프로젝트 멤버를 찾을 수 없습니다.')
    ticket.assignee = { projectMemberId: member.projectMemberId, name: member.name }
    ticket.assigneeUserId = member.userId!
  }
  if (body.description !== undefined) ticket.description = body.description.trim()
  if (body.due) ticket.due = { ...body.due, text: body.due.text?.trim() || null }
  write(tickets)
  return view(ticket)
}
export function changeMockTicketPublication(user: User, ticketId: string, body: TicketPublicationRequest) {
  const { tickets, ticket } = owned(user, ticketId)
  if (ticket.status !== 'PENDING_REVIEW') throw fail(409, 'TICKET_STATUS_CONFLICT', '검토 대기에서만 수정할 수 있습니다.')
  if (!['GITHUB', 'NOTION', 'SLACK'].includes(body.publishTarget) || !['CREATE', 'LINK', 'UPDATE'].includes(body.processingType)) throw fail(400, 'VALIDATION_FAILED', '발행 대상과 처리 유형을 확인해 주세요.')
  if (body.publishTarget !== 'GITHUB' && body.processingType !== 'CREATE') throw fail(400, 'PROCESSING_TYPE_TARGET_MISMATCH', 'Notion/Slack은 신규 생성만 가능합니다.')
  if (body.processingType !== 'CREATE' && !canUseExistingIssue(ticket)) throw fail(400, 'EXISTING_ISSUE_REQUIRED', '열린 기존 이슈가 필요합니다.')
  ticket.publishTarget = body.publishTarget
  ticket.processingType = body.processingType
  if (ticket.dedupe) ticket.dedupe.overlap = Boolean(ticket.dedupe.judged && ticket.dedupe.issue && ticket.dedupe.relation !== 'UNRELATED' && body.processingType === 'CREATE')
  write(tickets)
  return view(ticket)
}

export function changeMockTicket(user: User, ticketId: string, action: TicketAction) {
  const { tickets, ticket } = owned(user, ticketId)
  const rules = { confirm: ['PENDING_REVIEW', 'CONFIRMED'], reject: ['PENDING_REVIEW', 'REJECTED'], reopen: ['CONFIRMED', 'PENDING_REVIEW'], restore: ['REJECTED', 'PENDING_REVIEW'], revise: ['FAILED', 'PENDING_REVIEW'] } as const
  const [from, to] = rules[action]
  if (ticket.status !== from) throw fail(409, 'TICKET_STATUS_CONFLICT', '현재 상태에서 할 수 없는 작업입니다.')
  ticket.status = to
  if (action === 'revise') ticket.publishFailure = null
  write(tickets)
  return view(ticket)
}
export function retryMockTicket(user: User, ticketId: string) {
  const { tickets, ticket } = owned(user, ticketId)
  if (ticket.status !== 'FAILED') throw fail(409, 'TICKET_STATUS_CONFLICT', '실패한 티켓만 다시 시도할 수 있습니다.')
  ticket.status = 'PUBLISHED'
  ticket.publishFailure = null
  write(tickets)
  return { batchId: crypto.randomUUID(), count: 1 }
}

const BATCH_KEY = 'mock-publish-batches-v1'
type MockBatch = { userId: number; result: PublishBatchResult }
const readBatches = (): MockBatch[] => JSON.parse(sessionStorage.getItem(BATCH_KEY) ?? '[]')
export function publishMockTickets(user: User, projectId: number, meetingId: number, ticketIds: string[]) {
  settleMockBatches(user)
  const tickets = read(user)
  const group = tickets.filter((t) => t.assigneeUserId === user.userId && t.projectId === projectId && t.meetingId === meetingId)
  const selection = publishSelection(group)
  if (selection.reason) throw fail(409, 'TICKET_STATUS_CONFLICT', selection.reason)
  const ids = new Set(ticketIds)
  if (ids.size !== selection.confirmed.length || selection.confirmed.some((t) => !ids.has(t.ticketId))) throw fail(409, 'PUBLISH_SELECTION_CHANGED', '발행 대상이 변경됐습니다. 목록을 다시 확인하세요.')
  const batchId = crypto.randomUUID()
  const result: PublishBatchResult = {
    batchId, status: 'RUNNING', executionMode: 'MOCK', createdAt: new Date().toISOString(), finishedAt: null,
    summary: { total: ids.size, published: 0, linked: 0, updated: 0, failed: 0, publishing: ids.size },
    tickets: selection.confirmed.map((ticket) => ({ ticketId: ticket.ticketId, title: ticket.title, status: 'PUBLISHING', processingType: ticket.processingType, projectId, projectName: ticket.projectName, githubIssueNumber: null, githubIssueUrl: null, errorCode: null, errorMessage: null, failureCategory: null, retryable: false, outcomeUnknown: false, publishTarget: ticket.publishTarget })),
  }
  const batches = readBatches()
  batches.push({ userId: user.userId, result })
  sessionStorage.setItem(BATCH_KEY, JSON.stringify(batches))
  for (const ticket of group) if (ids.has(ticket.ticketId)) ticket.status = 'PUBLISHING'
  write(tickets)
  return { batchId, count: ids.size }
}
// 패널 조회 여부와 관계없이, 목록 조회에서도 경과한 발행을 완료한다.
function settleMockBatches(user: User) {
  const batches = readBatches()
  const ready = batches.filter((batch) => batch.userId === user.userId
    && batch.result.status === 'RUNNING' && Date.now() - Date.parse(batch.result.createdAt) > 1800)
  if (!ready.length) return
  const tickets = read(user)
  for (const { result } of ready) {
    result.status = 'SUCCEEDED'
    result.finishedAt = new Date().toISOString()
    for (const entry of result.tickets) {
      entry.status = entry.processingType === 'LINK' ? 'LINKED' : entry.processingType === 'UPDATE' ? 'UPDATED' : 'PUBLISHED'
      const ticket = tickets.find((t) => t.ticketId === entry.ticketId && t.assigneeUserId === user.userId)
      if (ticket?.status === 'PUBLISHING') { ticket.status = entry.status; ticket.publishFailure = null }
    }
    result.summary.publishing = 0
    result.summary.published = result.tickets.filter((t) => t.status === 'PUBLISHED').length
    result.summary.linked = result.tickets.filter((t) => t.status === 'LINKED').length
    result.summary.updated = result.tickets.filter((t) => t.status === 'UPDATED').length
  }
  write(tickets)
  sessionStorage.setItem(BATCH_KEY, JSON.stringify(batches))
}
export function getMockPublishResult(user: User, batchId: string) {
  settleMockBatches(user)
  const batch = readBatches().find((b) => b.userId === user.userId && b.result.batchId === batchId)
  if (!batch) throw fail(404, 'PUBLISH_BATCH_NOT_FOUND', '발행 묶음을 찾을 수 없습니다.')
  return structuredClone(batch.result)
}


// 읽기 전용 mock. 이전 세션의 티켓에 상세 정보가 없으면 빈 배열로 반환한다.
export function previewMockTicket(user: User, ticketId: string): TicketPublicationPreview {
  const { ticket } = owned(user, ticketId)
  const backlog = structuredClone({ description: ticket.description, tasks: ticket.backlog?.tasks ?? [], acceptanceCriteria: ticket.backlog?.acceptanceCriteria ?? [], evidenceQuotes: ticket.backlog?.evidenceQuotes ?? [], openQuestions: ticket.backlog?.openQuestions ?? [] })
  const lines = ['## 작업 내용', ticket.description ?? '']
  for (const [title, values] of [['상세 작업', backlog.tasks], ['완료 조건', backlog.acceptanceCriteria], ['근거', backlog.evidenceQuotes]] as const) if (values.length) lines.push(`## ${title}`, ...values.map((v) => `- ${v}`))
  lines.push('## 담당', ticket.assignee?.name ?? '미정', '## 기한', [ticket.due.text, ticket.due.date, ticket.due.time].filter(Boolean).join(' · ') || '미정')
  return { ticketId, title: ticket.title, status: ticket.status, publishTarget: ticket.publishTarget, executionMode: 'MOCK', backlog,
    publication: ticket.publishTarget === 'GITHUB' ? { kind: ticket.processingType, body: lines.join('\n\n'), secondaryBody: null, labels: [], githubAssignees: [], targetIssueNumber: ticket.processingType === 'CREATE' ? null : ticket.dedupe?.issue?.number ?? null } : null }
}
