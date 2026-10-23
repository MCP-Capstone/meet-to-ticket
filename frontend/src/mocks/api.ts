import type { realApi } from '../api/real'
import { ApiRequestError } from '../api/client'
import type { AnalysisTask, DirectoryUser, DispatchResult, ExecutionMode, Meeting, ProjectReindex, StartAnalysisResponse, TaskStage, User } from '../api/types'
import { listMockTickets, editMockTicket, changeMockTicket, retryMockTicket, deleteMockProjectTickets, publishMockTickets, getMockPublishResult, previewMockTicket } from './tickets'
import { mockItems } from './data'
import { countOf, makeMockDispatch } from './dispatch'
import { readMeetingFile, validateMeetingText, validMeetingDate } from '../meetingInput'
import { changeMockProjectRole, deleteMockProject, getMockProject, listMockMembers, listMockProjects, mockDirectoryUsers, saveMockProject } from './projects'
import type { AnalysisProgress, ProgressStep } from '../api/types'

// 백엔드 없이 화면을 개발하기 위한 가짜 API. realApi와 같은 형태를 유지한다.

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

let nextMeetingId = 1
const meetings = new Map<number, Meeting>()

// 실제 Agent나 GitHub는 호출하지 않는다.
const EXECUTION_MODE: ExecutionMode = 'MOCK'

// 작업별 시작 시각. 경과 시간으로 상태를 흉내 낸다.
const tasks = new Map<string, { meetingId: number; startedAt: number }>()
// 전송 결과는 대표 담당 지정이 남도록 작업마다 한 번 만들어 둔다
const dispatches = new Map<string, DispatchResult>()
const analyzedMeetings = new Set<number>()
const meetingMemberCounts = new Map<number, number>()
const reindexes = new Map<number, ProjectReindex>()

// 분석 단계별 소요 시간 (ms). 합계가 지나면 COMPLETED
const stageDurations: { step: ProgressStep; stage: TaskStage; ms: number }[] = [
  { step: 'CONTEXT', stage: 'EXTRACTING', ms: 2500 },
  { step: 'EXTRACTING', stage: 'NORMALIZING', ms: 4000 },
  { step: 'DEDUPING', stage: 'REVIEWING', ms: 3000 },
  { step: 'DISPATCHING', stage: 'REVIEWING', ms: 2500 },
]
const QUEUED_MS = 1000

// 건수는 화면 검증용 가짜 값이다. 앞 단계 세부 정보는 다음 단계에도 유지한다.
function mockProgress(meetingId: number, step: ProgressStep, ratio = 1): AnalysisProgress {
  const members = meetingMemberCounts.get(meetingId)
  const dedupeRatio = step === 'DEDUPING' ? ratio : 1
  return {
    step,
    ...(members === undefined ? {} : { context: { members, issues: null, notionDocs: null } }),
    ...(step === 'CONTEXT' ? {} : { extraction: { todos: mockItems.length, decisions: 1, openQuestions: 0 } }),
    ...(step !== 'DEDUPING' && step !== 'DISPATCHING' ? {} : {
      dedupe: { done: Math.floor(mockItems.length * dedupeRatio), total: mockItems.length, linked: dedupeRatio >= 1 ? 1 : 0 },
    }),
  }
}

// 새로고침해도 가입 정보와 로그인이 유지되는 것을 흉내 내려고 sessionStorage에 저장한다.
type MockUser = User & { password: string }

const USERS_KEY = 'mock-users'
const SESSION_KEY = 'mock-login-user'

// 바로 쓸 수 있는 데모 계정
const demoUser: MockUser = { userId: 1, loginId: 'demo1', name: '함주헌', password: 'password1', githubName: 'juhun-ham', slackEmail: 'juhun@example.com', slackUserId: null }

function load<T>(key: string, fallback: T): T {
  try {
    const raw = sessionStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown) {
  try {
    if (value === null) sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // 저장소를 쓸 수 없으면 새로고침 후 유지만 안 된다.
  }
}

const loadUsers = () => load<MockUser[]>(USERS_KEY, [
  demoUser,
  ...mockDirectoryUsers.filter((user) => user.userId !== 1).map((user) => ({ ...user, loginId: `member${user.userId}`, password: 'password1' })),
])
const directory = (): DirectoryUser[] => {
  const users = new Map(mockDirectoryUsers.map((user) => [user.userId, user]))
  for (const user of loadUsers()) users.set(user.userId, {
    userId: user.userId, name: user.name, githubName: user.githubName ?? users.get(user.userId)?.githubName ?? null,
    slackEmail: user.slackEmail ?? users.get(user.userId)?.slackEmail ?? null,
    slackUserId: user.slackUserId ?? users.get(user.userId)?.slackUserId ?? null,
  })
  return [...users.values()]
}

const fail = (status: number, code: string, message: string) => new ApiRequestError(status, { code, message })

// 실제 백엔드처럼 로그인하지 않으면 401을 던진다.
const requireLogin = () => {
  const user = load<User | null>(SESSION_KEY, null)
  if (!user) throw fail(401, 'UNAUTHORIZED', '로그인이 필요합니다.')
  return user
}

// 백엔드 파서와 같은 규칙: 첫 Markdown 제목, "회의 일시 / 날짜"의 ISO 날짜, "회의 참여자 / 참석자"의 이름 목록
function parseMeeting(text: string) {
  const field = (names: string) => text.match(new RegExp(`^(?:-\\s*)?(?:${names})\\s*:\\s*(.+)$`, 'm'))?.[1].trim()
  const title = field('제목') ?? text.match(/^#\s+(.+)$/m)?.[1].trim()
  const meetingDate = field('회의 일시|회의 날짜|날짜')?.match(/\d{4}-\d{2}-\d{2}/)?.[0]
  const names = (field('회의 참여자|회의 참석자|참여자|참석자') ?? '')
    .split(',')
    .map((p) => p.trim())
    .filter(Boolean)
  return { title, meetingDate, names }
}

const toUser = ({ userId, loginId, name, githubName, slackEmail, slackUserId }: User): User => ({ userId, loginId, name, githubName, slackEmail, slackUserId })

function enqueueAnalysis(meetingId: number): StartAnalysisResponse {
  if (!meetings.has(meetingId)) throw fail(404, 'MEETING_NOT_FOUND', '회의를 찾을 수 없습니다.')
  if (analyzedMeetings.has(meetingId)) throw fail(409, 'ANALYSIS_ALREADY_REQUESTED', '이미 분석을 요청한 회의입니다.')
  analyzedMeetings.add(meetingId)
  const taskId = crypto.randomUUID()
  tasks.set(taskId, { meetingId, startedAt: Date.now() })
  return { taskId, meetingId, status: 'QUEUED', executionMode: EXECUTION_MODE }
}

export const mockApi: typeof realApi = {
  async health() {
    return { status: 'MOCK' }
  },

  async signup(body) {
    await delay(300)
    const users = loadUsers()
    if (users.some((u) => u.loginId === body.loginId)) {
      throw fail(409, 'DUPLICATE_LOGIN_ID', '이미 사용 중인 아이디입니다.')
    }
    const created: MockUser = {
      ...body,
      userId: Math.max(0, ...users.map((user) => user.userId), ...mockDirectoryUsers.map((user) => user.userId)) + 1,
      slackUserId: null,
    }
    save(USERS_KEY, [...users, created])
    save(SESSION_KEY, toUser(created))
    return toUser(created)
  },

  async login(body) {
    await delay(300)
    const found = loadUsers().find((u) => u.loginId === body.loginId && u.password === body.password)
    if (!found) throw fail(401, 'LOGIN_FAILED', '아이디 또는 비밀번호가 올바르지 않습니다.')
    save(SESSION_KEY, toUser(found))
    return toUser(found)
  },

  async logout() {
    save(SESSION_KEY, null)
  },

  async me() {
    const user = load<User | null>(SESSION_KEY, null)
    if (!user) throw fail(401, 'UNAUTHORIZED', '로그인이 필요합니다.')
    return user
  },

  async listMyTickets(projectId) {
    await delay(200)
    return listMockTickets(requireLogin(), projectId)
  },
  async getTicketPreview(ticketId) {
    await delay(200)
    return previewMockTicket(requireLogin(), ticketId)
  },
  async editTicket(ticketId, body) {
    await delay(300)
    return editMockTicket(requireLogin(), ticketId, body)
  },
  async changeTicketStatus(ticketId, action) {
    await delay(300)
    return changeMockTicket(requireLogin(), ticketId, action)
  },
  async publishTickets(projectId, meetingId, ticketIds) {
    await delay(300)
    return publishMockTickets(requireLogin(), projectId, meetingId, ticketIds)
  },
  async getPublishResult(batchId) {
    await delay(200)
    return getMockPublishResult(requireLogin(), batchId)
  },
  async retryTicket(ticketId) {
    await delay(300)
    return retryMockTicket(requireLogin(), ticketId)
  },

  async listProjects() {
    await delay(200)
    requireLogin()
    return { projects: listMockProjects(requireLogin()).map((project) => ({
      ...project,
      lastMeetingDate: [...meetings.values()].filter((meeting) => meeting.projectId === project.projectId)
        .map((meeting) => meeting.meetingDate).sort().at(-1) ?? null,
    })), executionMode: EXECUTION_MODE }
  },

  async deleteProject(projectId, confirmName) {
    await delay(300)
    const duration = QUEUED_MS + stageDurations.reduce((total, { ms }) => total + ms, 0)
    const projectTasks = [...tasks.entries()].filter(([, task]) => meetings.get(task.meetingId)?.projectId === projectId)
    deleteMockProject(projectId, confirmName, requireLogin(), projectTasks.some(([, task]) => Date.now() - task.startedAt < duration))
    deleteMockProjectTickets(projectId)
    for (const [id] of projectTasks) { tasks.delete(id); dispatches.delete(id) }
    for (const [id, meeting] of meetings) if (meeting.projectId === projectId) {
      meetings.delete(id)
      meetingMemberCounts.delete(id)
      analyzedMeetings.delete(id)
    }
  },

  async getProject(projectId) {
    await delay(200)
    return getMockProject(projectId, requireLogin())
  },

  async createProject(body) {
    await delay(400)
    return saveMockProject(body, requireLogin(), directory())
  },

  async updateProject(projectId, body) {
    await delay(400)
    return saveMockProject(body, requireLogin(), directory(), projectId)
  },

  async requestProjectReindex(projectId) {
    requireLogin()
    await delay(350)
    const result: ProjectReindex = {
      projectId, status: 'SUCCEEDED', requestedAt: new Date().toISOString(), finishedAt: new Date().toISOString(),
      errorMessage: null, progressMessage: '재색인이 완료되었습니다.', indexed: 0, skipped: 0, total: 0, indexCount: 0, lastSyncedAt: new Date().toISOString(),
    }
    reindexes.set(projectId, result)
    return result
  },

  async getProjectReindex(projectId) {
    requireLogin()
    await delay(100)
    return reindexes.get(projectId) ?? {
      projectId, status: null, requestedAt: null, finishedAt: null, errorMessage: null, progressMessage: null,
      indexed: null, skipped: null, total: null, indexCount: null, lastSyncedAt: null,
    }
  },

  async changeProjectMemberRole(projectId, memberUserId, role) {
    await delay(400)
    return changeMockProjectRole(projectId, memberUserId, role, requireLogin())
  },

  async searchUsers(keyword) {
    await delay(150)
    requireLogin()
    const query = keyword.toLowerCase()
    return { users: directory().filter((user) => user.name.toLowerCase().includes(query) || user.githubName?.toLowerCase().includes(query)) }
  },

  async listMembers(projectId) {
    await delay(150)
    requireLogin()
    return { projectId, members: listMockMembers(projectId, requireLogin()) }
  },

  async requestMeetingAnalysis(projectId, input) {
    await delay(350)
    const members = listMockMembers(projectId, requireLogin())
    const invalidInput = () => fail(400, 'INVALID_MEETING_INPUT', '회의 날짜와 회의록 입력을 확인해 주세요.')
    if (!validMeetingDate(input.meetingDate)) throw invalidInput()
    const hasFile = input.file !== undefined
    const hasText = input.content !== undefined && input.content.trim() !== ''
    if (hasFile === hasText) throw invalidInput()
    let content: string
    try {
      content = input.file !== undefined ? await readMeetingFile(input.file) : input.content ?? ''
      const invalid = validateMeetingText(content)
      if (invalid) throw new Error(invalid)
    } catch (e) {
      throw fail(400, 'INVALID_MEETING_INPUT', e instanceof Error ? e.message : '회의록 입력을 확인해 주세요.')
    }
    const filename = input.file?.name ?? ''
    const heading = content.match(/^\s*#{1,2}\s+(.+)$/m)?.[1].trim()
    const title = (input.title?.trim() || heading || filename.replace(/\.[^.]+$/, '') || `${input.meetingDate} 회의`).slice(0, 255)
    const meeting: Meeting = {
      meetingId: nextMeetingId++, projectId, title, meetingDate: input.meetingDate,
      participantIds: [], originalFilename: filename, readyForAnalysis: true,
    }
    meetings.set(meeting.meetingId, meeting)
    meetingMemberCounts.set(meeting.meetingId, members.length)
    return enqueueAnalysis(meeting.meetingId)
  },

  async getMeeting(meetingId) {
    await delay(100)
    const meeting = meetings.get(meetingId)
    if (!meeting) throw fail(404, 'MEETING_NOT_FOUND', '회의를 찾을 수 없습니다.')
    listMockMembers(meeting.projectId, requireLogin())
    return meeting
  },

  async uploadMeeting(projectId, file) {
    await delay(500)
    const members = listMockMembers(projectId, requireLogin())
    const { title, meetingDate, names } = parseMeeting(await file.text())
    if (!title || !meetingDate) {
      throw fail(400, 'INVALID_MEETING_FILE', 'UTF-8 .txt 파일의 제목 / 날짜 / 참여자를 확인해 주세요.')
    }
    const ids = names.map((name) => {
      const matches = members.filter((member) => member.name === name)
      return matches.length === 1 ? matches[0].projectMemberId : undefined
    })
    if (ids.some((id) => id === undefined)) {
      throw fail(400, 'PARTICIPANT_MAPPING_FAILED', '미등록 또는 동명이인 참여자가 있어 연결할 수 없습니다.')
    }
    const meeting: Meeting = {
      meetingId: nextMeetingId++,
      projectId,
      title,
      meetingDate,
      participantIds: ids as number[],
      originalFilename: file.name,
      readyForAnalysis: true,
    }
    meetings.set(meeting.meetingId, meeting)
    meetingMemberCounts.set(meeting.meetingId, members.length)
    return meeting
  },

  async startAnalysis(meetingId) {
    await delay(300)
    requireLogin()
    return enqueueAnalysis(meetingId)
  },

  async getAnalysis(taskId) {
    await delay(150)
    requireLogin()
    const task = tasks.get(taskId)
    if (!task) throw fail(404, 'TASK_NOT_FOUND', '분석 작업을 찾을 수 없습니다.')

    const base: AnalysisTask = {
      taskId,
      meetingId: task.meetingId,
      status: 'QUEUED',
      stage: null,
      executionMode: EXECUTION_MODE,
      createdAt: new Date(task.startedAt).toISOString(),
      finishedAt: null,
      errorMessage: null,
      progress: null,
    }
    let elapsed = Date.now() - task.startedAt - QUEUED_MS
    if (elapsed < 0) return base
    for (const { step, stage, ms } of stageDurations) {
      if (elapsed < ms) return { ...base, status: 'RUNNING', stage, progress: mockProgress(task.meetingId, step, elapsed / ms) }
      elapsed -= ms
    }
    return { ...base, status: 'COMPLETED', stage: 'VERIFYING', finishedAt: new Date().toISOString(), progress: mockProgress(task.meetingId, 'DISPATCHING') }
  },

  async getAnalysisItems(taskId) {
    await delay(300)
    requireLogin()
    const items = mockItems.map((item) => ({
      ...item, itemId: crypto.randomUUID(), reviewStatus: 'PENDING' as const, publishStatus: 'DRAFT' as const,
      githubIssueNumber: null, githubIssueUrl: null, errorMessage: null,
    }))
    const count = (key: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'SKIPPED' | 'DRAFT') => items.filter((i) => i.publishStatus === key).length
    return {
      taskId,
      status: 'COMPLETED',
      executionMode: EXECUTION_MODE,
      items,
      summary: {
        total: items.length,
        approved: 0,
        rejected: 0,
        succeeded: count('SUCCEEDED'),
        failed: count('FAILED'),
        unknown: count('UNKNOWN'),
        skipped: count('SKIPPED'),
        drafts: count('DRAFT'),
      },
    }
  },

  async getDispatchResult(taskId) {
    await delay(200)
    const user = requireLogin()
    if (!tasks.has(taskId)) throw fail(404, 'TASK_NOT_FOUND', '분석 작업을 찾을 수 없습니다.')
    if (!dispatches.has(taskId)) dispatches.set(taskId, makeMockDispatch(taskId, user.name))
    return dispatches.get(taskId)!
  },

  async assignTicket(ticketId, memberId) {
    await delay(200)
    requireLogin()
    for (const result of dispatches.values()) {
      const target = result.tickets.find((t) => t.ticket.ticketId === ticketId)
      if (!target) continue
      if (target.ticket.status !== 'ASSIGNEE_REQUIRED') throw fail(409, 'TICKET_STATUS_CONFLICT', '현재 티켓 상태에서는 할 수 없는 작업입니다.')
      const m = result.members.find((x) => x.memberId === memberId)
      if (!m) throw fail(404, 'PROJECT_MEMBER_NOT_FOUND', '프로젝트 멤버가 아닌 사용자입니다.')
      target.ticket = { ...target.ticket, status: 'PENDING_REVIEW', assignee: { projectMemberId: m.memberId, name: m.name } }
      result.counts = countOf(result.tickets)
      return target
    }
    throw fail(404, 'TICKET_NOT_FOUND', '티켓을 찾을 수 없습니다.')
  },
}
