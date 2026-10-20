// API 명세의 데이터 형식. 백엔드와 필드 이름을 맞춘다.

export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'
export type ActionItemStatus = 'EXTRACTED' | 'EDITED' | 'TICKET_CREATED'
export type TicketTarget = 'GITHUB'
export type TicketStatus = 'CREATED' | 'FAILED'

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

export interface Ticket {
  ticketId: number
  actionItemId: number
  target: TicketTarget
  externalUrl: string | null
  status: TicketStatus
  createdAt: string
}

export interface CreateTicketsRequest {
  actionItemIds: number[]
  target: TicketTarget
}

export interface CreateTicketsResponse {
  tickets: Ticket[]
}

export interface ApiError {
  code: string
  message: string
}
