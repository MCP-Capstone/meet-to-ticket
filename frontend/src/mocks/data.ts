import type { ActionItem } from '../api/types'

export const sampleMeetingContent = `[주간 기획 회의]
- 로그인 화면 시안은 이번 주 금요일까지 디자인 담당이 공유하기로 함
- 회의록 업로드 API는 백엔드에서 수요일까지 구현
- AI 추출 정확도 테스트용 샘플 회의록 5개 준비 필요`

export const mockActionItems: Omit<ActionItem, 'actionItemId' | 'meetingId'>[] = [
  {
    title: '로그인 화면 시안 공유',
    description: '로그인 화면 디자인 시안을 팀에 공유한다.',
    assignee: '디자인 담당',
    dueDate: '2026-10-24',
    priority: 'MEDIUM',
    status: 'EXTRACTED',
  },
  {
    title: '회의록 업로드 API 구현',
    description: '회의록 텍스트를 저장하는 API를 구현한다.',
    assignee: '백엔드',
    dueDate: '2026-10-22',
    priority: 'HIGH',
    status: 'EXTRACTED',
  },
  {
    title: '샘플 회의록 준비',
    description: 'AI 추출 정확도 테스트용 샘플 회의록 5개를 준비한다.',
    assignee: null,
    dueDate: null,
    priority: 'LOW',
    status: 'EXTRACTED',
  },
]
