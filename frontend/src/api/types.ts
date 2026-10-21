// API 명세의 데이터 형식. 백엔드와 필드 이름을 맞춘다.
// 회의 / 분석 / 결과 필드는 임시다. 확정 스키마(#25)와 명세(#26)가 나오면 이름을 맞춘다.

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

// .txt 업로드 후 백엔드가 파일에서 읽어 저장한 회의
export interface Meeting {
  meetingId: number
  projectId: number
  fileName: string
  title: string
  meetingDate: string
  participants: string[]
  createdAt: string
}

export type AnalysisStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

// RUNNING일 때 지금 하고 있는 일
export type AnalysisStep = 'EXTRACTING' | 'REVIEWING' | 'PUBLISHING'

export interface AnalysisJob {
  jobId: number
  meetingId: number
  status: AnalysisStatus
  currentStep: AnalysisStep | null
  // FAILED일 때 사유 (Agent 응답 없음 등)
  failureReason: string | null
  startedAt: string
  finishedAt: string | null
}

export interface StartAnalysisResponse {
  jobId: number
  status: AnalysisStatus
}

export type ReviewStatus = 'APPROVED' | 'REJECTED'

// PUBLISHED: 이슈 생성 / FAILED: 생성 실패 / EXCLUDED: 검수 반려로 발행 제외
export type PublishStatus = 'PUBLISHED' | 'FAILED' | 'EXCLUDED'

export interface TicketResult {
  resultId: number
  title: string
  description: string
  assignee: string | null
  dueDate: string | null
  reviewStatus: ReviewStatus
  publishStatus: PublishStatus
  // 발행 제외 / 실패 사유
  reason: string | null
  issueNumber: number | null
  issueUrl: string | null
}

export interface AnalysisResults {
  jobId: number
  results: TicketResult[]
}

export interface ApiError {
  code: string
  message: string
}
