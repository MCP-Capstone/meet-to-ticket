import type { realApi } from '../api/real'
import { ApiRequestError } from '../api/client'
import type { AnalysisJob, AnalysisStep, Meeting, User } from '../api/types'
import { mockResults } from './data'

// 백엔드 없이 화면을 개발하기 위한 가짜 API. realApi와 같은 형태를 유지한다.

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

let nextMeetingId = 1
let nextJobId = 1

// 작업별 시작 시각. 경과 시간으로 상태를 흉내 낸다.
const jobs = new Map<number, { meetingId: number; startedAt: number }>()

// 분석 단계별 소요 시간 (ms). 합계가 지나면 COMPLETED
const stepDurations: [AnalysisStep, number][] = [
  ['EXTRACTING', 3000],
  ['REVIEWING', 3000],
  ['PUBLISHING', 2000],
]
const PENDING_MS = 1000

// 새로고침해도 가입 정보와 로그인이 유지되는 것을 흉내 내려고 sessionStorage에 저장한다.
type MockUser = User & { password: string }

const USERS_KEY = 'mock-users'
const SESSION_KEY = 'mock-login-user'

// 바로 쓸 수 있는 데모 계정
const demoUser: MockUser = { userId: 1, loginId: 'demo', name: '데모', password: 'demo1234' }

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

const loadUsers = () => load<MockUser[]>(USERS_KEY, [demoUser])

const fail = (status: number, code: string, message: string) => new ApiRequestError(status, { code, message })

// 실제 백엔드처럼 로그인하지 않으면 401을 던진다.
const requireLogin = () => {
  if (!load<User | null>(SESSION_KEY, null)) throw fail(401, 'UNAUTHORIZED', '로그인이 필요합니다.')
}

// 머리말 "제목: / 날짜: / 참여자:" 를 읽는다. 없으면 파일 이름 / 오늘 날짜로 채운다.
function parseMeeting(fileName: string, text: string) {
  const field = (name: string) => text.match(new RegExp(`^${name}\\s*:\\s*(.+)$`, 'm'))?.[1].trim()
  return {
    title: field('제목') ?? fileName.replace(/\.txt$/i, ''),
    meetingDate: field('날짜') ?? new Date().toISOString().slice(0, 10),
    participants: (field('참여자') ?? '')
      .split(',')
      .map((p) => p.trim())
      .filter(Boolean),
  }
}

const toUser = ({ userId, loginId, name }: User): User => ({ userId, loginId, name })

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
    const created: MockUser = { ...body, userId: users.length + 1 }
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

  async uploadMeeting(projectId, file) {
    await delay(500)
    requireLogin()
    const text = await file.text()
    if (text.trim() === '') throw fail(400, 'INVALID_FILE', '빈 파일입니다.')
    const meeting: Meeting = {
      ...parseMeeting(file.name, text),
      meetingId: nextMeetingId++,
      projectId,
      fileName: file.name,
      createdAt: new Date().toISOString(),
    }
    return meeting
  },

  async startAnalysis(meetingId) {
    await delay(300)
    requireLogin()
    const jobId = nextJobId++
    jobs.set(jobId, { meetingId, startedAt: Date.now() })
    return { jobId, status: 'PENDING' }
  },

  async getAnalysis(jobId) {
    await delay(150)
    requireLogin()
    const job = jobs.get(jobId)
    if (!job) throw fail(404, 'JOB_NOT_FOUND', '분석 작업을 찾을 수 없습니다.')

    const base: AnalysisJob = {
      jobId,
      meetingId: job.meetingId,
      status: 'PENDING',
      currentStep: null,
      failureReason: null,
      startedAt: new Date(job.startedAt).toISOString(),
      finishedAt: null,
    }
    let elapsed = Date.now() - job.startedAt - PENDING_MS
    if (elapsed < 0) return base
    for (const [step, ms] of stepDurations) {
      if (elapsed < ms) return { ...base, status: 'RUNNING', currentStep: step }
      elapsed -= ms
    }
    return { ...base, status: 'COMPLETED', finishedAt: new Date().toISOString() }
  },

  async getAnalysisResults(jobId) {
    await delay(300)
    requireLogin()
    return { jobId, results: mockResults.map((r, i) => ({ ...r, resultId: jobId * 10 + i })) }
  },
}
