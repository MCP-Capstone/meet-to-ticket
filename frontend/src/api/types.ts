// API 명세의 데이터 형식. 백엔드(Spring) 응답과 필드 이름을 맞춘다.

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

// MOCK: Agent 없이 고정 모의 응답 (실제 GitHub 발행 없음) / LIVE: 실제 Agent 호출
export type ExecutionMode = 'MOCK' | 'LIVE'

export interface Project {
  projectId: number
  name: string
}

export interface ProjectList {
  projects: Project[]
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

export type TaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED'

// RUNNING일 때 Agent가 하고 있는 일
export type TaskStage = 'EXTRACTING' | 'NORMALIZING' | 'REVIEWING' | 'PUBLISHING' | 'VERIFYING'

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
}

export interface StartAnalysisResponse {
  taskId: string
  meetingId: number
  status: TaskStatus
  executionMode: ExecutionMode
}

export type ReviewStatus = 'APPROVED' | 'REJECTED'

// SUCCEEDED: 이슈 생성 / FAILED: 생성 실패 / UNKNOWN: 생성 여부 확인 필요
// SKIPPED: 모의 실행이라 발행 생략 / BLOCKED: 검수 반려로 발행 제외
export type PublishStatus = 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'SKIPPED' | 'BLOCKED'

// Agent가 정리한 업무 내용. 화면에서 쓰는 필드만 적는다.
export interface TaskItemContent {
  background?: string
  todo?: string[]
  acceptanceCriteria?: string[]
  assignees?: { projectMemberId: number | null; mentionedName: string; githubName: string | null }[]
  due?: { date: string | null; time: string | null; qualifier: string | null; originalText: string | null }
  review?: { correctionCount?: number; reason?: string }
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
}

export interface TaskItemList {
  taskId: string
  status: TaskStatus
  executionMode: ExecutionMode
  items: TaskItem[]
  summary: TaskSummary
}

export interface ApiError {
  code: string
  message: string
}
