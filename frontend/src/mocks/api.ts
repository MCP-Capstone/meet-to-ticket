import type { realApi } from '../api/real'
import { ApiRequestError } from '../api/client'
import type { ActionItem, Meeting, User } from '../api/types'
import { mockActionItems } from './data'

// 백엔드 없이 화면을 개발하기 위한 가짜 API. realApi와 같은 형태를 유지한다.

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

let nextMeetingId = 1

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

  async createMeeting(body) {
    await delay(300)
    const meeting: Meeting = {
      ...body,
      meetingId: nextMeetingId++,
      createdAt: new Date().toISOString(),
    }
    return meeting
  },

  async extractActionItems(meetingId) {
    // LLM 응답 시간을 흉내 낸다.
    await delay(1500)
    const actionItems: ActionItem[] = mockActionItems.map((item, i) => ({
      ...item,
      actionItemId: meetingId * 10 + i,
      meetingId,
    }))
    return { meetingId, actionItems }
  },
}
