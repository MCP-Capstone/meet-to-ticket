import type { realApi } from '../api/real'
import type { ActionItem, Meeting } from '../api/types'
import { mockActionItems } from './data'

// 백엔드 없이 화면을 개발하기 위한 가짜 API. realApi와 같은 형태를 유지한다.

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

let nextMeetingId = 1

export const mockApi: typeof realApi = {
  async health() {
    return { status: 'MOCK' }
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
