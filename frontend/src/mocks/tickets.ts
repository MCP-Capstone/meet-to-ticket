import type { MyTicket, MyTicketList, TicketAction, TicketEditRequest, User } from '../api/types'
import { ApiRequestError } from '../api/client'
import { validMeetingDate } from '../meetingInput'
import { listMockMembers, listMockProjects } from './projects'

type StoredTicket = MyTicket & { assigneeUserId: number }
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
  const seed: StoredTicket[] = statuses.map((status, index) => ({
    ticketId: `21000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, title: titles[index], status,
    publishTarget: index === 3 ? 'NOTION' : 'GITHUB', processingType: 'CREATE', publishFailure: failures[index] ?? null,
    projectId: project.projectId, projectName: project.name, meetingId: 2101, meetingTitle: 'CSV 내보내기 점검 회의',
    taskId: '21000000-0000-4000-8000-000000000100', createdAt: '2026-10-23T10:00:00+09:00',
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
const view = ({ assigneeUserId: _owner, ...ticket }: StoredTicket): MyTicket => structuredClone(ticket)
export function listMockTickets(user: User, projectId?: number): MyTicketList {
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
