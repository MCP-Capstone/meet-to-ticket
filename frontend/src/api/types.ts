// API 명세의 데이터 형식. 백엔드(Spring) 응답과 필드 이름을 맞춘다.

export interface User {
  userId: number
  loginId: string
  name: string
  githubName: string | null
  slackEmail: string | null
  slackUserId: string | null
}

export interface SignupRequest {
  loginId: string
  password: string
  name: string
  // 선택 입력. 앞뒤 공백 제거 후 빈 값은 null로 전송한다.
  githubName: string | null
  slackEmail: string | null
}

export interface LoginRequest {
  loginId: string
  password: string
}

// MOCK: Agent 없이 고정 모의 응답 (실제 GitHub 발행 없음) / LIVE: 실제 Agent 호출
export type ExecutionMode = 'MOCK' | 'LIVE'

export interface Project {
  projectId: number
  name: string
}

// TASK-007: TASK-005·006에 전달한 프로젝트 편집 API 계약.
export type ConnectionStatus = 'CONNECTED' | 'NOT_CONNECTED' | 'ERROR'
export interface ProjectSummary extends Project {
  githubOwner: string
  githubRepo: string
  connections: { github: ConnectionStatus | null; notion: ConnectionStatus | null; slack: ConnectionStatus | null }
  memberCount: number
  lastMeetingDate: string | null
  canEdit: boolean
}
export type RepositoryPermission = 'GRANTED' | 'DENIED' | 'NO_ACCOUNT' | null

export interface DirectoryUser {
  userId: number
  name: string
  githubName: string | null
  slackEmail: string | null
  slackUserId: string | null
}

export type ProjectRole = 'ADMIN' | 'MEMBER'

export interface ProjectEditorMember extends DirectoryUser {
  admin: boolean
  repositoryPermission: RepositoryPermission
}

export interface ProjectSaveRequest {
  name: string
  description: string | null
  githubOwner: string
  githubRepo: string
  notionPageUrl: string | null
  slackChannelId: string | null
  memberUserIds: number[]
  // 키는 사용자 ID. 빈 이메일은 null로 전송해 기존 값을 지운다.
  memberSlackEmails: Record<number, string | null>
}

export interface ProjectDetail extends Project {
  // 선택 메타데이터. 서버가 제공하지 않으면 화면에서 수치를 추정하지 않는다.
  existingIssueCount?: number | null
  notionDocumentCount?: number | null
  description: string | null
  githubOwner: string
  githubRepo: string
  notionPageUrl: string | null
  slackChannelId: string | null
  members: ProjectEditorMember[]
  connections: { github: ConnectionStatus | null; notion: ConnectionStatus | null; slack: ConnectionStatus | null }
  canEdit: boolean
}

export interface ProjectList {
  projects: ProjectSummary[]
  executionMode: ExecutionMode
}

export interface ProjectMember {
  projectMemberId: number
  userId: number | null
  name: string
  githubName: string | null
}

export interface MemberList {
  projectId: number
  members: ProjectMember[]
}

// .txt 업로드 후 백엔드가 파일에서 읽어 저장한 회의
export interface Meeting {
  meetingId: number
  projectId: number
  title: string
  meetingDate: string
  // ProjectMember ID. 이름은 팀원 목록에서 찾는다.
  participantIds: number[]
  originalFilename: string
  readyForAnalysis: boolean
}

// 두 입력을 동시에 전송하지 않도록 입력 방식을 타입으로 구분한다.
export type MeetingAnalysisInput = { meetingDate: string; title?: string } & (
  | { file: File; content?: never }
  | { content: string; file?: never }
)

export type TaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED'

// RUNNING일 때 Agent가 하고 있는 일
export type TaskStage = 'EXTRACTING' | 'NORMALIZING' | 'REVIEWING' | 'PUBLISHING' | 'VERIFYING'

// 진행 화면 단계 (기획_v16 REQ-007). stage(TaskStage)와 별개다
export type ProgressStep = 'CONTEXT' | 'EXTRACTING' | 'DEDUPING' | 'DISPATCHING'

// 진행 화면용 세부 정보. 앞 단계 값은 다음 단계에도 남고, 아직 지나지 않은 단계의 값은 없다
export interface AnalysisProgress {
  step: ProgressStep
  // issues, notionDocs는 기존 이슈 색인이나 Notion이 꺼져 있거나 불러오지 못하면 null.
  // 맥락을 불러오기 전에 먼저 null로 한 번 오고, 불러온 뒤 값이 채워져 다시 온다
  context?: { members: number; issues: number | null; notionDocs: number | null }
  extraction?: { todos: number; decisions: number; openQuestions: number }
  // 중복 판단이 꺼져 있으면 오지 않는다
  dedupe?: { done: number; total: number; linked: number }
}

export interface AnalysisTask {
  taskId: string
  meetingId: number
  status: TaskStatus
  stage: TaskStage | null
  executionMode: ExecutionMode
  createdAt: string
  finishedAt: string | null
  // FAILED일 때 사유
  errorMessage: string | null
  // 진행 세부 정보. 아직 없으면 null
  progress: AnalysisProgress | null
}

export interface StartAnalysisResponse {
  taskId: string
  meetingId: number
  status: TaskStatus
  executionMode: ExecutionMode
}

// PENDING: 발행 전 초안 (담당자 확인 대기)
export type ReviewStatus = 'APPROVED' | 'REJECTED' | 'PENDING'

// SUCCEEDED: 이슈 생성 / FAILED: 생성 실패 / UNKNOWN: 생성 여부 확인 필요
// SKIPPED: 모의 실행이라 발행 생략 / BLOCKED: 검수 반려로 발행 제외
// DRAFT: 발행 전 초안. 담당자가 확인한 뒤 일괄 발행한다
export type PublishStatus = 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'SKIPPED' | 'BLOCKED' | 'DRAFT'

// Agent가 정리한 업무 내용. 화면에서 쓰는 필드만 적는다.
// 백엔드 모의 실행과 실제 Agent(#52)가 쓰는 키가 달라 둘 다 받는다. (읽는 쪽: ResultList)
export interface TaskItemContent {
  // 모의 실행: background / 실제 Agent: description
  background?: string
  description?: string
  todo?: string[]
  acceptanceCriteria?: string[]
  // 모의 실행: 배열 / 실제 Agent: { names, github, unresolved }
  assignees?:
    | { projectMemberId: number | null; mentionedName: string; githubName: string | null }[]
    | { names?: string[]; github?: string[]; unresolved?: string[] }
  // 모의 실행: originalText / 실제 Agent: text
  due?: { date?: string | null; time?: string | null; qualifier?: string | null; originalText?: string | null; text?: string | null }
  // 모의 실행: correctionCount, reason / 실제 Agent: revisions, notes(남은 검수 지적), fallback(기본 초안으로 대체됨)
  review?: { correctionCount?: number; revisions?: number; reason?: string; notes?: string[]; fallback?: boolean }
  // 코드가 붙인 확인 메모 (발행 보류·조건부 표기, 남은 검수 지적 수, 원문과 다른 기한 등)
  warnings?: string[]
  // 중복 판정. linked_existing이 true면 새 이슈가 아니라 기존 GitHub 이슈에 연결한 항목
  dedupe?: {
    relation?: 'SAME' | 'CHANGE' | 'RELATED' | 'UNRELATED' | null
    issue_number?: number | null
    issue_state?: 'open' | 'closed' | null
    issue_title?: string | null
    reason?: string | null
    linked_existing?: boolean
  }
}

export interface TaskItem {
  itemId: string
  workKey: string | null
  title: string
  contentJson: TaskItemContent | null
  action: string
  reason: string | null
  reviewStatus: ReviewStatus
  publishStatus: PublishStatus
  githubIssueNumber: number | null
  githubIssueUrl: string | null
  // 발행 실패 / 확인 필요 사유
  errorMessage: string | null
}

export interface TaskSummary {
  total: number
  approved: number
  rejected: number
  succeeded: number
  failed: number
  unknown: number
  skipped: number
  // 발행 전 초안 수
  drafts: number
}

export interface TaskItemList {
  taskId: string
  status: TaskStatus
  executionMode: ExecutionMode
  items: TaskItem[]
  summary: TaskSummary
}

// ---------------------------------------------------------------- 전송 결과 (TASK-023, 024, 025)
// 회의록 처리가 끝난 뒤 업로더가 보는 화면. GET /analysis-tasks/{taskId}/tickets

// 기획 4.2 티켓 상태
export type TicketStatus =
  | 'ASSIGNEE_REQUIRED' | 'PENDING_REVIEW' | 'CONFIRMED' | 'PUBLISHING' | 'REJECTED' | 'PUBLISHED' | 'LINKED' | 'UPDATED' | 'FAILED'
export type ProcessingType = 'CREATE' | 'LINK' | 'UPDATE'
export type PublishTarget = 'GITHUB' | 'SLACK' | 'NOTION'

// 내 티켓 조회(GET /tickets/me)와 같은 형식
export interface Ticket {
  ticketId: string
  title: string
  status: TicketStatus
  processingType: ProcessingType
  publishTarget: PublishTarget
  dedupe: {
    judged: boolean
    relation: 'SAME' | 'CHANGE' | 'RELATED' | 'UNRELATED' | null
    issue: { number: number; title: string | null; state: 'open' | 'closed' | null; url: string } | null
    // 신규 발행인데 같은 작업이나 관련 이슈가 있으면 true ("겹칠 수 있음")
    overlap: boolean
    reason: string | null
  }
  meetingTitle: string
  description: string | null
  due: { text: string | null; date: string | null; time: string | null }
  // 받은 사람. 담당 지정 필요면 null
  assignee: { projectMemberId: number; name: string } | null
}

export interface DispatchTicket {
  ticket: Ticket
  // 받은 사람이 로그인 사용자인지 ("나"로 표시)
  mine: boolean
  // GitHub 티켓만 있다. 읽기 전용
  preview: { body: string | null; labels: string[]; evidenceQuotes: string[] } | null
}

export interface DispatchResult {
  taskId: string
  counts: { total: number; pendingReview: number; linked: number; assigneeRequired: number }
  tickets: DispatchTicket[]
  // 회의록을 올린 사람이면 true. 대표 담당을 지정할 수 있다
  canAssign: boolean
  members: { memberId: number; name: string }[]
}

export interface ApiError {
  code: string
  message: string
}

// TASK-026·028·029 서버 계약. 티켓 상태(TicketStatus)는 분석 결과의 reviewStatus와 구분한다.
export type TicketAction = 'confirm' | 'reject' | 'reopen' | 'restore' | 'revise'
export interface TicketDue { text: string | null; date: string | null; time: string | null }
// 발행에 실패한 티켓의 사유. outcomeUnknown이면 GitHub에 이미 반영됐을 수 있다.
export interface PublishFailure {
  errorCode: string | null
  errorMessage: string | null
  failureCategory: 'REPOSITORY_PERMISSION' | 'OTHER' | null
  retryable: boolean
  outcomeUnknown: boolean
}
export interface PublishStarted { batchId: string; count: number }
export interface MyTicket {
  ticketId: string
  title: string
  status: TicketStatus
  processingType: 'CREATE' | 'LINK' | 'UPDATE'
  publishTarget: 'GITHUB' | 'SLACK' | 'NOTION'
  projectId: number
  projectName: string
  meetingId: number
  meetingTitle: string
  taskId: string
  createdAt: string
  description: string | null
  due: TicketDue
  assignee: { projectMemberId: number; name: string } | null
  publishFailure: PublishFailure | null
}
export interface MyTicketList {
  counts: { pendingReview: number; confirmed: number; rejected: number; failed: number }
  tickets: MyTicket[]
}
export interface TicketEditRequest {
  description?: string
  assigneeMemberId?: number
  due?: TicketDue
}

// TASK-033·047: 발행 접수와 결과 조회는 별도 요청이다.
export interface PublishResultEntry extends PublishFailure {
  ticketId: string
  title: string
  status: TicketStatus
  processingType: ProcessingType
  projectId: number
  projectName: string
  githubIssueNumber: number | null
  githubIssueUrl: string | null
}
export interface PublishBatchResult {
  batchId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'PARTIAL_FAILED'
  executionMode: ExecutionMode
  createdAt: string
  finishedAt: string | null
  summary: { total: number; published: number; linked: number; updated: number; failed: number; publishing: number }
  tickets: PublishResultEntry[]
}
