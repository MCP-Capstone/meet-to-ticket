import { request } from './client'
import type {
  CreateMeetingRequest,
  CreateTicketsRequest,
  CreateTicketsResponse,
  ExtractResponse,
  Meeting,
} from './types'

export const realApi = {
  health: () => request<{ status: string }>('/health'),

  createMeeting: (body: CreateMeetingRequest) =>
    request<Meeting>('/meetings', { method: 'POST', body: JSON.stringify(body) }),

  extractActionItems: (meetingId: number) =>
    request<ExtractResponse>(`/meetings/${meetingId}/extract`, { method: 'POST' }),

  createTickets: (body: CreateTicketsRequest) =>
    request<CreateTicketsResponse>('/tickets', { method: 'POST', body: JSON.stringify(body) }),
}
