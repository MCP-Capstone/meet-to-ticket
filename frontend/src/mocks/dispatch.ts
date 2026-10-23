import type { DispatchResult, DispatchTicket, Ticket } from '../api/types'
import { mockMembers } from './data'

// 전송 결과 화면 확인용 가짜 데이터 (UI 플로우 6쪽). 실제 Agent / 백엔드 결과가 아니다.

const ticket = (t: Partial<Ticket> & Pick<Ticket, 'title' | 'publishTarget'>): Ticket => ({
  ticketId: crypto.randomUUID(),
  status: 'PENDING_REVIEW',
  processingType: 'CREATE',
  dedupe: { judged: false, relation: null, issue: null, overlap: false, reason: null },
  meetingTitle: 'CSV 내보내기 · 진척 점검',
  description: null,
  due: { text: null, date: null, time: null },
  assignee: null,
  ...t,
})

const member = (name: string) => {
  // 가입으로 만든 모의 사용자처럼 팀원 목록에 없는 이름도 받는 사람으로 보여준다
  const m = mockMembers.find((x) => x.name === name)
  return { projectMemberId: m?.projectMemberId ?? 0, name }
}

const body = `## 작업 내용
기존 비동기 report job을 재사용해 관리자 화면의 필터 결과 전체를 CSV로 내려받게 한다.

## 상세 작업
- [ ] 현재 검색·상태 필터와 정렬 반영
- [ ] 필드: 이름, 이메일, 부서, 계정 상태, 가입일, 마지막 로그인 일자
- [ ] 같은 조직·요청자 조합은 진행 중 작업 1개만 허용

## 완료 조건
- 5만 건을 넘으면 거부되고 안내 문구가 보인다

## 근거
> 최대 내보내기 건수는 5만 건이며 초과하면 고객지원 문의 문구를 표시한다

> 같은 조직과 요청자 조합에는 진행 중 작업을 하나만 허용한다

## 담당
- 회의록 담당자: 박종민
- GitHub: @jongmiiin

## 기한
2026-10-20 (원문: 20일까지)

<details>
<summary>회의 배경</summary>

관리자 사용자 목록 화면에서 CSV 내보내기를 제공하기로 했다.

</details>
`

export function makeMockDispatch(taskId: string, uploaderName: string): DispatchResult {
  const tickets: DispatchTicket[] = [
    {
      ticket: ticket({ title: '비동기 CSV 내보내기 API와 생성기 구현', publishTarget: 'GITHUB', assignee: member('박종민'),
        due: { text: '20일까지', date: '2026-10-20', time: null },
        dedupe: { judged: true, relation: 'RELATED', overlap: true, reason: '#38 report job 정리와 관련 있지만 같은 작업은 아니라서 새 이슈로 만들었어요.',
          issue: { number: 38, title: 'report job 정리', state: 'open', url: 'https://github.com/MCP-Capstone/mcp-capstone/issues/38' } } }),
      mine: false,
      preview: { body, labels: ['backlog'], evidenceQuotes: [] },
    },
    {
      ticket: ticket({ title: '사용자 내보내기 권한과 감사 로그', publishTarget: 'GITHUB', processingType: 'LINK', assignee: member('강민규'),
        dedupe: { judged: true, relation: 'SAME', overlap: false, reason: '기존 이슈 #41과 같은 작업이에요.',
          issue: { number: 41, title: '내보내기 권한 정리', state: 'open', url: 'https://github.com/MCP-Capstone/mcp-capstone/issues/41' } } }),
      mine: false,
      preview: { body: '## 작업 내용\n관리자만 내보내기를 할 수 있게 하고 감사 로그를 남긴다.\n', labels: [], evidenceQuotes: [] },
    },
    {
      ticket: ticket({ title: 'CSV 내보내기 통합 QA', publishTarget: 'GITHUB', status: 'ASSIGNEE_REQUIRED' }),
      mine: false,
      preview: { body: '## 작업 내용\n내보내기 전체 흐름을 함께 점검한다.\n', labels: [], evidenceQuotes: [] },
    },
    {
      ticket: ticket({ title: '회의 결과 공지: CSV 내보내기 · 진척 점검', publishTarget: 'SLACK', assignee: member(uploaderName) }),
      mine: true,
      preview: null,
    },
    {
      ticket: ticket({ title: '고객사 사용자 목록 CSV 내보내기 조사', publishTarget: 'NOTION', assignee: member(uploaderName) }),
      mine: true,
      preview: null,
    },
  ]
  return {
    taskId,
    counts: countOf(tickets),
    tickets,
    canAssign: true,
    members: mockMembers.map((m) => ({ memberId: m.projectMemberId, name: m.name })),
  }
}

export function countOf(tickets: DispatchTicket[]): DispatchResult['counts'] {
  return {
    total: tickets.length,
    pendingReview: tickets.filter((t) => t.ticket.processingType !== 'LINK' && t.ticket.status === 'PENDING_REVIEW').length,
    linked: tickets.filter((t) => t.ticket.processingType === 'LINK').length,
    assigneeRequired: tickets.filter((t) => t.ticket.status === 'ASSIGNEE_REQUIRED').length,
  }
}
