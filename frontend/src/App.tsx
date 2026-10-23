import { useEffect, useRef, useState } from 'react'
import { AnalysisProgress } from './components/AnalysisProgress'
import { AuthForm } from './components/AuthForm'
import { ResultList } from './components/ResultList'
import { ProjectEditor } from './components/ProjectEditor'
import { Spinner } from './components/Spinner'
import { Stepper } from './components/Stepper'
import { UploadForm } from './components/UploadForm'
import { api, ApiRequestError, onUnauthorized, useMock } from './api'
import type { AnalysisTask, ExecutionMode, Meeting, Project, ProjectDetail, ProjectMember, TaskItemList, User } from './api'

type Step = 'upload' | 'analyzing' | 'result'

const stepIndex = { upload: 0, analyzing: 1, result: 2 } as const

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

const isUnauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'

function App() {
  // undefined: 로그인 상태 확인 중, null: 로그인 안 함
  const [user, setUser] = useState<User | null | undefined>(undefined)
  const [authNotice, setAuthNotice] = useState<string | null>(null)
  const [authCheckError, setAuthCheckError] = useState<string | null>(null)
  const [authCheckAttempt, setAuthCheckAttempt] = useState(0)
  const [logoutError, setLogoutError] = useState<string | null>(null)
  const [loggingOut, setLoggingOut] = useState(false)
  const logoutPending = useRef(false)
  // 401 처리기 안에서 최신 로그인 상태를 보려고 ref에도 둔다.
  const loggedIn = useRef(false)
  const [page, setPage] = useState<'workflow' | 'project-editor'>('workflow')
  const [projects, setProjects] = useState<Project[]>([])
  const [projectsLoading, setProjectsLoading] = useState(true)
  const [editorProjectId, setEditorProjectId] = useState<number | null>(null)
  const [projectSavedMessage, setProjectSavedMessage] = useState<string | null>(null)
  const [projectSaving, setProjectSaving] = useState(false)
  // 업로드 프로젝트 선택은 TASK-010에서 확장한다.
  const [project, setProject] = useState<Project | null>(null)
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [executionMode, setExecutionMode] = useState<ExecutionMode | null>(null)
  const [projectError, setProjectError] = useState<string | null>(null)
  const [step, setStep] = useState<Step>('upload')
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [taskId, setTaskId] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const [result, setResult] = useState<TaskItemList | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 새 회의록을 올릴 때 업로드 화면을 처음 상태로 되돌리기 위한 키
  const [uploadKey, setUploadKey] = useState(0)

  useEffect(() => {
    loggedIn.current = Boolean(user)
  }, [user])

  // 새로고침해도 세션이 남아 있으면 로그인 상태를 이어간다.
  useEffect(() => {
    let cancelled = false
    api
      .me()
      .then((currentUser) => {
        if (!cancelled) setUser(currentUser)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        if (e instanceof ApiRequestError && e.status === 401) {
          setUser(null)
        } else {
          setAuthCheckError('로그인 상태를 확인하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.')
        }
      })
    return () => { cancelled = true }
  }, [authCheckAttempt])

  // 로그인하면 프로젝트와 팀원을 불러온다.
  useEffect(() => {
    if (!user) return
    let cancelled = false
    const load = async () => {
      try {
        const list = await api.listProjects()
        if (cancelled) return
        setExecutionMode(list.executionMode)
        setProjects(list.projects)
        const first = list.projects[0]
        if (!first) {
          setProjectError('참여 중인 프로젝트가 없습니다. 프로젝트 설정에서 새 프로젝트를 등록할 수 있습니다.')
          return
        }
        const memberList = await api.listMembers(first.projectId)
        if (cancelled) return
        setProject(first)
        setMembers(memberList.members)
      } catch (e) {
        if (!cancelled && !isUnauthorized(e)) setProjectError(toMessage(e))
      } finally {
        if (!cancelled) setProjectsLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [user])

  const toNames = (ids: number[]) => ids.map((id) => members.find((m) => m.projectMemberId === id)?.name ?? `#${id}`)

  const handleStart = async (target: Meeting) => {
    setError(null)
    setStarting(true)
    try {
      const res = await api.startAnalysis(target.meetingId)
      setMeeting(target)
      setTaskId(res.taskId)
      setStep('analyzing')
    } catch (e) {
      if (!isUnauthorized(e)) setError(toMessage(e))
    } finally {
      setStarting(false)
    }
  }

  // 끝난 작업은 FAILED여도 항목별 결과가 있을 수 있어 먼저 결과를 불러온다.
  const handleFinish = async (task: AnalysisTask) => {
    try {
      const res = await api.getAnalysisItems(task.taskId)
      if (task.status === 'FAILED' && res.items.length === 0) {
        setError(task.errorMessage ?? '분석에 실패했습니다.')
        setStep('upload')
        return
      }
      setResult(res)
      setStep('result')
    } catch (e) {
      if (!isUnauthorized(e)) {
        setError(task.errorMessage ?? toMessage(e))
        setStep('upload')
      }
    }
  }

  const reset = () => {
    setMeeting(null)
    setTaskId(null)
    setResult(null)
    setError(null)
    setStep('upload')
    setUploadKey((k) => k + 1)
  }

  // 세션이 만료되면 작업 중이던 화면을 지우고 로그인 화면으로 보낸다.
  useEffect(() => {
    onUnauthorized(() => {
      if (loggedIn.current) setAuthNotice('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      setLogoutError(null)
      reset()
      setProject(null)
      setProjects([])
      setPage('workflow')
      setProjectSavedMessage(null)
      setMembers([])
      setUser(null)
    })
    return () => onUnauthorized(null)
  }, [])

  const handleLogin = (loggedInUser: User) => {
    setAuthNotice(null)
    setProjectError(null)
    setProjectsLoading(true)
    setUser(loggedInUser)
  }

  const handleLogout = async () => {
    if (logoutPending.current) return
    logoutPending.current = true
    setLoggingOut(true)
    setLogoutError(null)
    try {
      await api.logout()
      reset()
      setProject(null)
      setProjects([])
      setPage('workflow')
      setProjectSavedMessage(null)
      setMembers([])
      setUser(null)
    } catch (e) {
      // 세션 만료는 기존 공통 처리기가 로그인 화면으로 전환한다.
      if (!isUnauthorized(e)) {
        setLogoutError('로그아웃하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.')
      }
    } finally {
      logoutPending.current = false
      setLoggingOut(false)
    }
  }

  const handleProjectSaved = (detail: ProjectDetail) => {
    const entry = { projectId: detail.projectId, name: detail.name }
    setProjects((current) => current.some((item) => item.projectId === detail.projectId)
      ? current.map((item) => item.projectId === detail.projectId ? entry : item) : [...current, entry])
    setEditorProjectId(detail.projectId)
    setProjectSavedMessage('프로젝트를 저장했습니다.')
    if (!project || project.projectId === detail.projectId) {
      setProject(entry)
      setProjectError(null)
      // 기존 회의록 업로드의 참여자 표시는 기존 멤버 조회 API를 재사용한다.
      api.listMembers(detail.projectId).then((result) => setMembers(result.members)).catch((e: unknown) => {
        if (!isUnauthorized(e)) setProjectError(toMessage(e))
      })
    }
  }

  return (
    <main className="container">
      <header className="header">
        <div>
          <h1>meet-to-ticket</h1>
          <p className="muted">{project ? project.name : '회의록에서 할 일을 찾아 GitHub 이슈로 발행합니다.'}</p>
        </div>
        <div className="header-side">
          {useMock && <span className="badge mock">MOCK</span>}
          {executionMode === 'MOCK' && user && (
            <span className="badge mock" title="백엔드가 Agent 없이 모의 응답으로 동작 중입니다.">
              모의 실행
            </span>
          )}
          {user && (
            <>
              <span className="user-name">{user.name}님</span>
              <button className="link" onClick={handleLogout} disabled={projectSaving || loggingOut}>
                {loggingOut ? '로그아웃 중...' : '로그아웃'}
              </button>
            </>
          )}
        </div>
      </header>

      {user && logoutError && (
        <div role="alert">
          <p className="error">{logoutError}</p>
          <button type="button" className="link" onClick={handleLogout} disabled={loggingOut || projectSaving}>
            로그아웃 다시 시도
          </button>
        </div>
      )}

      {user === undefined && authCheckError && (
        <div role="alert">
          <p className="error">{authCheckError}</p>
          <button type="button" className="primary" onClick={() => {
            setAuthCheckError(null)
            setAuthCheckAttempt((attempt) => attempt + 1)
          }}>다시 시도</button>
        </div>
      )}

      {user === undefined && !authCheckError && (
        <p className="loading">
          <Spinner size="sm" label="로그인 확인 중" /> 로그인 상태를 확인하고 있습니다...
        </p>
      )}

      {user === null && <AuthForm onLogin={handleLogin} notice={authNotice} />}

      {user && (
        <nav className="workspace-nav" aria-label="서비스 메뉴">
          <button type="button" aria-current={page === 'workflow' ? 'page' : undefined} disabled={projectSaving} onClick={() => setPage('workflow')}>회의록 업로드</button>
          <button type="button" aria-current={page === 'project-editor' ? 'page' : undefined} disabled={projectsLoading || step === 'analyzing' || starting || projectSaving} onClick={() => {
            setEditorProjectId(project?.projectId ?? projects[0]?.projectId ?? null)
            setProjectSavedMessage(null)
            setPage('project-editor')
          }}>프로젝트 설정</button>
        </nav>
      )}

      {user && page === 'project-editor' && <>
        <div className="project-toolbar">
          <label>편집할 프로젝트
            <select value={editorProjectId ?? ''} disabled={projectSaving} onChange={(e) => {
              setEditorProjectId(e.target.value === '' ? null : Number(e.target.value))
              setProjectSavedMessage(null)
            }}>
              <option value="">새 프로젝트 등록</option>
              {projects.map((item) => <option key={item.projectId} value={item.projectId}>{item.name}</option>)}
            </select>
          </label>
        </div>
        {projectSavedMessage && <p className="project-success" role="status">{projectSavedMessage}</p>}
        <ProjectEditor key={editorProjectId ?? 'new'} projectId={editorProjectId}
          onSaved={handleProjectSaved} onSavingChange={setProjectSaving} onChange={() => setProjectSavedMessage(null)} onCancel={() => setPage('workflow')} />
      </>}

      {user && page === 'workflow' && projectError && <p className="error">{projectError}</p>}

      {user && page === 'workflow' && !project && !projectError && (
        <p className="loading">
          <Spinner size="sm" label="프로젝트 불러오는 중" /> 프로젝트를 불러오고 있습니다...
        </p>
      )}

      {user && page === 'workflow' && project && (
        <>
          <Stepper current={stepIndex[step]} />

          {error && <p className="error">{error}</p>}

          {step === 'upload' && (
            <UploadForm
              key={uploadKey}
              projectId={project.projectId}
              toNames={toNames}
              starting={starting}
              onStart={handleStart}
            />
          )}

          {step === 'analyzing' && taskId !== null && meeting && (
            <AnalysisProgress taskId={taskId} meetingTitle={meeting.title} onFinish={handleFinish} onCancel={reset} />
          )}

          {step === 'result' && meeting && result && (
            <>
              <ResultList meeting={meeting} participants={toNames(meeting.participantIds)} result={result} />
              <button className="link" onClick={reset}>
                새 회의록 업로드
              </button>
            </>
          )}
        </>
      )}
    </main>
  )
}

export default App
