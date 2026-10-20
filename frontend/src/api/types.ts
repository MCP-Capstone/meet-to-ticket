// API 명세의 데이터 형식. 백엔드와 필드 이름을 맞춘다.

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'
export type ActionItemStatus = 'EXTRACTED'

export interface Meeting {
  meetingId: number
  title: string
  meetingDate: string
  participants: string[]
  content: string
  createdAt: string
}

export interface CreateMeetingRequest {
  title: string
  meetingDate: string
  participants: string[]
  content: string
}

export interface ActionItem {
  actionItemId: number
  meetingId: number
  title: string
  description: string
  assignee: string | null
  dueDate: string | null
  priority: Priority
  status: ActionItemStatus
}

export interface ExtractResponse {
  meetingId: number
  actionItems: ActionItem[]
}

export interface User {
  userId: number
  loginId: string
  name: string
}

export interface SignupRequest {
  loginId: string
  password: string
  name: string
}

export interface LoginRequest {
  loginId: string
  password: string
}

export interface ApiError {
  code: string
  message: string
}
