import { request } from './client'
import type {
  CreateMeetingRequest,
  ExtractResponse,
  LoginRequest,
  Meeting,
  SignupRequest,
  User,
} from './types'

export const realApi = {
  health: () => request<{ status: string }>('/health'),

  signup: (body: SignupRequest) =>
    request<User>('/auth/signup', { method: 'POST', body: JSON.stringify(body) }),

  login: (body: LoginRequest) =>
    request<User>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),

  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  // 로그인하지 않았으면 401 UNAUTHORIZED
  me: () => request<User>('/auth/me'),

  createMeeting: (body: CreateMeetingRequest) =>
    request<Meeting>('/meetings', { method: 'POST', body: JSON.stringify(body) }),

  extractActionItems: (meetingId: number) =>
    request<ExtractResponse>(`/meetings/${meetingId}/extract`, { method: 'POST' }),
}
