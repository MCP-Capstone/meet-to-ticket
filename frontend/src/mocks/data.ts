import type { ActionItem } from '../api/types'

export const sampleMeetingContent = `[주간 기획 회의] 2026-10-20 (월)
참석: 김PM, 이백엔드, 박AI

1. 회의록 업로드 기능
- 이백엔드: 회의록 저장 API는 수요일까지 구현하겠다.
- 김PM: 입력 화면은 이번 주 안에 시안 공유하겠다.

2. AI 추출 품질
- 박AI: 추출 정확도를 보려면 샘플 회의록이 더 필요하다.
- 샘플 회의록 5개는 누가 준비할지 다음 회의에서 정하기로 함.

3. 기타
- GitHub 이슈 생성 연동은 금요일까지 테스트 저장소에서 확인하기로 함. (담당: 박AI)`

export const mockActionItems: Omit<ActionItem, 'actionItemId' | 'meetingId'>[] = [
  {
    title: '회의록 저장 API 구현',
    description: '회의록 텍스트와 회의 정보를 저장하는 API를 구현한다.',
    assignee: '이백엔드',
    dueDate: '2026-10-22',
    priority: 'HIGH',
    status: 'EXTRACTED',
  },
  {
    title: '회의록 입력 화면 시안 공유',
    description: '회의록 입력 화면 시안을 만들어 팀에 공유한다.',
    assignee: '김PM',
    dueDate: '2026-10-24',
    priority: 'MEDIUM',
    status: 'EXTRACTED',
  },
  {
    title: 'GitHub 이슈 생성 연동 테스트',
    description: '테스트 저장소에서 GitHub 이슈 생성이 동작하는지 확인한다.',
    assignee: '박AI',
    dueDate: '2026-10-24',
    priority: 'MEDIUM',
    status: 'EXTRACTED',
  },
  {
    title: '샘플 회의록 5개 준비',
    description: 'AI 추출 정확도 확인용 샘플 회의록 5개를 준비한다. 담당자는 다음 회의에서 정한다.',
    assignee: null,
    dueDate: null,
    priority: 'LOW',
    status: 'EXTRACTED',
  },
]
