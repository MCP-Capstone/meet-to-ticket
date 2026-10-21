import type { TicketResult } from '../api/types'

// 샘플 .txt 회의록. 머리말(제목 / 날짜 / 참여자) 형식은 데이터 담당이 확정하면 맞춘다.
export const sampleMeetingFileName = '2026-10-20_주간기획회의.txt'

export const sampleMeetingText = `제목: 주간 기획 회의
날짜: 2026-10-20
참여자: 김PM, 이백엔드, 박AI

1. 회의록 업로드 기능
- 이백엔드: 회의록 저장 API는 수요일까지 구현하겠다.
- 김PM: 업로드 화면은 이번 주 안에 시안 공유하겠다.

2. AI 추출 품질
- 박AI: 추출 정확도를 보려면 샘플 회의록이 더 필요하다.
- 샘플 회의록 준비 담당은 다음 회의에서 정하기로 함.

3. 기타
- GitHub 이슈 생성 연동은 금요일까지 테스트 저장소에서 확인하기로 함. (담당: 박AI)`

const issueUrl = (n: number) => `https://github.com/MCP-Capstone/mcp-capstone/issues/${n}`

export const mockResults: Omit<TicketResult, 'resultId'>[] = [
  {
    title: '회의록 저장 API 구현',
    description: '업로드한 회의록과 회의 정보를 저장하는 API를 구현한다.',
    assignee: '이백엔드',
    dueDate: '2026-10-22',
    reviewStatus: 'APPROVED',
    publishStatus: 'PUBLISHED',
    reason: null,
    issueNumber: 101,
    issueUrl: issueUrl(101),
  },
  {
    title: '업로드 화면 시안 공유',
    description: '회의록 업로드 화면 시안을 만들어 팀에 공유한다.',
    assignee: '김PM',
    dueDate: '2026-10-24',
    reviewStatus: 'APPROVED',
    publishStatus: 'PUBLISHED',
    reason: null,
    issueNumber: 102,
    issueUrl: issueUrl(102),
  },
  {
    title: 'GitHub 이슈 생성 연동 테스트',
    description: '테스트 저장소에서 GitHub 이슈 생성이 동작하는지 확인한다.',
    assignee: '박AI',
    dueDate: '2026-10-24',
    reviewStatus: 'APPROVED',
    publishStatus: 'PUBLISHED',
    reason: null,
    issueNumber: 103,
    issueUrl: issueUrl(103),
  },
  {
    title: '샘플 회의록 준비',
    description: '추출 품질 확인용 샘플 회의록을 준비한다.',
    assignee: null,
    dueDate: null,
    reviewStatus: 'REJECTED',
    publishStatus: 'EXCLUDED',
    reason: '담당자가 정해지지 않아 발행에서 제외했습니다. (다음 회의에서 결정)',
    issueNumber: null,
    issueUrl: null,
  },
]
