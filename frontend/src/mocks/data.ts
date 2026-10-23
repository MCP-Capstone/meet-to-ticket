import type { ProjectMember, TaskItem } from '../api/types'

// 백엔드 demo 시드와 같은 프로젝트 / 팀원
export const mockProject = { projectId: 1, name: '기업용 문서 협업 서비스 - 관리자 사용자 목록 CSV 내보내기' }

export const mockMembers: ProjectMember[] = [
  { projectMemberId: 1, userId: null, name: '강민규', githubName: 'mingyukang5420' },
  { projectMemberId: 2, userId: null, name: '박종민', githubName: 'jongmiiin' },
  { projectMemberId: 3, userId: null, name: '임나윤', githubName: 'jadoolim' },
  { projectMemberId: 4, userId: null, name: '최윤경', githubName: '0unk0' },
  { projectMemberId: 5, userId: 1, name: '함주헌', githubName: 'juhun-ham' },
  { projectMemberId: 6, userId: null, name: '허민규', githubName: 'minkyu-huh' },
]

// 샘플 .txt 회의록. 형식은 backend/examples/meetings/*.txt (데이터 담당 작성)와 같다.
export const sampleMeetingFileName = '30min_1_summary.txt'

export const sampleMeetingText = `# 회의 요약 - 빈 결과 CSV 생성 오류 수정

- 회의 ID: 30min_1
- 회의 일시: 2026-10-22 10:00~10:30
- 시간대: Asia/Seoul
- 회의 참여자: 강민규, 박종민, 임나윤, 최윤경, 함주헌, 허민규

## [구간 1] 새로 발견한 오류
사용자 필터 결과가 0건일 때 실제 CSV 생성 API가 라이브러리 예외로 500을 반환하고 파일 URL을 제공하지 못한다. 허민규가 재현했다. 빈 검색 결과는 정상 다운로드 대상이다.

## [구간 2] 수정 합의와 완료 조건
CSV-08로 박종민이 23일 17시까지 수정한다. 0건일 때 헤더만 있는 파일을 생성하고 성공 상태 / 건수 0 / 파일 URL을 반환한다. 재현 로그와 0건 / 1건 회귀 결과가 완료 증빙이다.

## [구간 3] 검증 자료와 발행
합성 계정을 사용하고 고객 개인정보를 사용하지 않는다. 임나윤은 실제 다운로드 확인에 협조하며 화면 신규 기능 요청은 없다.`

const issueUrl = (n: number) => `https://github.com/MCP-Capstone/mcp-capstone/issues/${n}`

// 화면 확인용 가짜 결과. 실제 Agent / GitHub 결과가 아니다.
export const mockItems: Omit<TaskItem, 'itemId'>[] = [
  {
    workKey: 'CSV-08',
    title: '빈 결과 CSV 생성 오류 수정',
    contentJson: {
      background: '필터 결과가 0건이면 CSV 생성 API가 500을 반환한다.',
      todo: ['0건일 때 헤더만 있는 CSV 생성', '성공 상태 / 건수 0 / 파일 URL 반환'],
      acceptanceCriteria: ['재현 로그 첨부', '0건 / 1건 회귀 테스트 통과'],
      assignees: [{ projectMemberId: 2, mentionedName: '박종민', githubName: 'jongmiiin' }],
      due: { date: '2026-10-23', time: '17:00', qualifier: null, originalText: '23일 17시까지' },
      review: { correctionCount: 0, reason: '근거 / 담당자 / 기한이 회의록과 일치합니다.' },
    },
    action: 'CREATE',
    reason: null,
    reviewStatus: 'APPROVED',
    publishStatus: 'SUCCEEDED',
    githubIssueNumber: 101,
    githubIssueUrl: issueUrl(101),
    errorMessage: null,
  },
  {
    workKey: null,
    title: '빈 결과 다운로드 실제 확인',
    contentJson: {
      background: '수정 후 실제 다운로드 동작을 확인한다.',
      assignees: [{ projectMemberId: 3, mentionedName: '임나윤', githubName: 'jadoolim' }],
      due: { date: null, time: null, qualifier: null, originalText: null },
      review: { correctionCount: 1, reason: '협조 업무로 CSV-08에 포함되어 별도 발행하지 않습니다.' },
    },
    action: 'CREATE',
    reason: '협조 업무로 CSV-08에 포함되어 별도 발행하지 않습니다.',
    reviewStatus: 'REJECTED',
    publishStatus: 'BLOCKED',
    githubIssueNumber: null,
    githubIssueUrl: null,
    errorMessage: null,
  },
  {
    workKey: 'CSV-09',
    title: '사용자 목록 CSV 내보내기 권한 점검',
    contentJson: {
      background: '관리자만 CSV를 내보낼 수 있는지 점검한다.',
      assignees: [{ projectMemberId: 1, mentionedName: '강민규', githubName: 'mingyukang5420' }],
      due: { date: null, time: null, qualifier: null, originalText: null },
      review: { correctionCount: 0, reason: '기존 이슈와 같은 작업입니다.' },
      dedupe: {
        relation: 'SAME',
        issue_number: 12,
        issue_state: 'open',
        issue_title: '사용자 목록 CSV 내보내기 권한 점검',
        reason: '기존 이슈 #12와 같은 작업이라 새로 만들지 않음',
        linked_existing: true,
      },
    },
    action: 'CREATE',
    reason: '기존 이슈 #12와 같은 작업이라 새로 만들지 않음',
    reviewStatus: 'APPROVED',
    publishStatus: 'SUCCEEDED',
    githubIssueNumber: 12,
    githubIssueUrl: issueUrl(12),
    errorMessage: null,
  },
  {
    // 기획_v16: 회의록 처리는 발행하지 않고 담당자 확인 대기 초안으로 끝난다
    workKey: 'CSV-10',
    title: '빈 결과 CSV 회귀 테스트 추가',
    contentJson: {
      description: '0건 / 1건 결과에 대한 회귀 테스트를 추가한다.',
      assignees: { names: ['허민규'], github: ['minkyu-huh'], unresolved: [] },
      due: { date: '2026-10-24', time: null, text: '모레' },
      review: { revisions: 2, notes: ["[4] '전체 회귀 테스트': 원문에 없는 범위 → 0건 / 1건으로 좁힌다"] },
      warnings: ['조건부 처리로 표기되어 확인 필요 (조건부 기존 이슈 갱신 (CSV-08))', '자동 검수 지적 1개가 남아 확인 필요'],
    },
    action: 'CREATE',
    reason: null,
    reviewStatus: 'PENDING',
    publishStatus: 'DRAFT',
    githubIssueNumber: null,
    githubIssueUrl: null,
    errorMessage: null,
  },
]
