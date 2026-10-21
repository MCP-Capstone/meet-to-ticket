import { request } from './client'
import type {
  AnalysisTask,
  LoginRequest,
  Meeting,
  MemberList,
  ProjectList,
  SignupRequest,
  StartAnalysisResponse,
  TaskItemList,
  User,
} from './types'

export const realApi = {
  health: () => request<{ status: string }>('/health'),

  signup: (body: SignupRequest) =>
    request<User>('/auth/signup', { method: 'POST', body: JSON.stringify(body) }),

  login: (body: LoginRequest) =>
    request<User>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),

  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  // 로그인하지 않았으면 401 UNAUTHORIZED
  me: () => request<User>('/auth/me'),

  // 내 프로젝트 목록과 백엔드 실행 모드(MOCK / LIVE)
  listProjects: () => request<ProjectList>('/projects'),

  // 참여자 ID를 이름으로 보여줄 때 쓴다.
  listMembers: (projectId: number) => request<MemberList>(`/projects/${projectId}/members`),

  // .txt 회의록 업로드. 백엔드가 제목 / 날짜 / 참여자를 읽어 저장한다.
  uploadMeeting: (projectId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<Meeting>(`/projects/${projectId}/meetings`, { method: 'POST', body: form })
  },

  // 분석을 시작하고 바로 작업 ID를 받는다. (202)
  startAnalysis: (meetingId: number) =>
    request<StartAnalysisResponse>(`/meetings/${meetingId}/analysis-tasks`, { method: 'POST' }),

  // 처리 중 화면이 반복 호출한다.
  getAnalysis: (taskId: string) => request<AnalysisTask>(`/analysis-tasks/${taskId}`),

  // 작업이 끝난 뒤에만 호출한다. 끝나기 전에는 409 TASK_NOT_FINISHED
  getAnalysisItems: (taskId: string) => request<TaskItemList>(`/analysis-tasks/${taskId}/items`),
}
