import { useEffect, useRef, useState } from 'react'
import { AnalysisProgress } from './components/AnalysisProgress'
import { AuthForm } from './components/AuthForm'
import { DispatchResult } from './components/DispatchResult'
import { MyTickets } from './components/MyTickets'
import { ProjectEditor } from './components/ProjectEditor'
import { ProjectList } from './components/ProjectList'
import { Spinner } from './components/Spinner'
import { Toast } from './components/Toast'
import { WorkspaceNavigation } from './components/WorkspaceNavigation'
import { UploadForm } from './components/UploadForm'
import { api, ApiRequestError, onUnauthorized } from './api'
import type { AnalysisTask, Meeting, MeetingAnalysisInput, Project, ProjectDetail, ProjectSummary, ProjectMember, TaskItemList, User } from './api'

type Step = 'upload' | 'analyzing' | 'result'


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
  // 로그인 직후에는 작업자가 가장 자주 처리하는 '내 티켓'을 바로 보여 준다.
  const [page, setPage] = useState<'workflow' | 'project-list' | 'project-editor' | 'my-tickets'>('my-tickets')
  const [projects, setProjects] = useState<ProjectSummary[]>([])
  const [projectsLoading, setProjectsLoading] = useState(true)
  const [editorProjectId, setEditorProjectId] = useState<number | null>(null)
  const [projectSavedMessage, setProjectSavedMessage] = useState<string | null>(null)
  const [projectSaving, setProjectSaving] = useState(false)
  const [projectDeleting, setProjectDeleting] = useState(false)
  const projectListRequest = useRef(0)
  const [ticketBusy, setTicketBusy] = useState(false)
  const [ticketDirty, setTicketDirty] = useState(false)
  const canLeaveTickets = () => !ticketDirty || window.confirm('저장하지 않은 티켓 수정을 버리고 이동할까요?')
  const [project, setProject] = useState<Project | null>(null)
  const selectedProject = useRef<Project | null>(null)
  const [projectSelectionNotice, setProjectSelectionNotice] = useState<string | null>(null)
  const selectProject = (next: Project | null) => {
    selectedProject.current = next
    setProject(next)
    setProjectSelectionNotice(null)
  }
  // 멤버 조회는 프로젝트 접근 확인을 겸한다. 화면에 쓰던 참여자 이름 표시는 결과 화면에서 뺐다.
  const [, setMembers] = useState<ProjectMember[]>([])
  const [projectError, setProjectError] = useState<string | null>(null)
  const [step, setStep] = useState<Step>('upload')
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [taskId, setTaskId] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const startLock = useRef(false)
  const runEpoch = useRef(0)
  const activeRunId = useRef<string | null>(null)
  const memberRequest = useRef(0)
  const [meetingNotice, setMeetingNotice] = useState<string | null>(null)
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
    const version = ++projectListRequest.current
    const load = async () => {
      try {
        const list = await api.listProjects()
        if (cancelled || version !== projectListRequest.current) return
        setProjects(list.projects)
        const first = list.projects[0]
        if (!first) {
          selectProject(null)
          setMembers([])
          return
        }
        selectProject(first)
        const memberVersion = ++memberRequest.current
        const memberList = await api.listMembers(first.projectId)
        if (cancelled || memberVersion !== memberRequest.current) return
        setMembers(memberList.members)
      } catch (e) {
        if (!cancelled && version === projectListRequest.current && !isUnauthorized(e)) setProjectError(toMessage(e))
      } finally {
        if (!cancelled && version === projectListRequest.current) setProjectsLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [user])

  const handleProjectChange = (projectId: number) => {
    const selected = projects.find((item) => item.projectId === projectId)
    if (!selected || startLock.current) return
    selectProject(selected)
    setMembers([])
    setProjectError(null)
    setError(null)
    const version = ++memberRequest.current
    api.listMembers(projectId).then((result) => {
      if (version === memberRequest.current) setMembers(result.members)
    }).catch((e: unknown) => {
      if (version === memberRequest.current && !isUnauthorized(e)) setProjectError(toMessage(e))
    })
  }

  const refreshProjects = async () => {
    const version = ++projectListRequest.current
    setProjectsLoading(true)
    setProjectError(null)
    try {
      const list = await api.listProjects()
      if (version !== projectListRequest.current) return
      setProjects(list.projects)
      // 재조회 중 바뀐 선택도 응답 시점의 최신 선택값으로 확인한다.
      const current = selectedProject.current
      const updated = list.projects.find((item) => item.projectId === current?.projectId)
      if (updated) selectProject(updated)
      else if (current) {
        ++memberRequest.current
        selectProject(null)
        setMembers([])
        setProjectSelectionNotice('선택한 프로젝트를 더 이상 사용할 수 없습니다. 업로드할 프로젝트를 다시 선택해 주세요.')
      }
    } catch (e) {
      if (version === projectListRequest.current && !isUnauthorized(e)) setProjectError(toMessage(e))
    } finally {
      if (version === projectListRequest.current) setProjectsLoading(false)
    }
  }

  const handleProjectDeleted = (projectId: number) => {
    setProjects((current) => current.filter((item) => item.projectId !== projectId))
    if (editorProjectId === projectId) setEditorProjectId(null)
    if (project?.projectId === projectId) {
      reset()
      const next = projects.find((item) => item.projectId !== projectId)
      selectProject(null)
      setMembers([])
      if (next) handleProjectChange(next.projectId)
    }
    void refreshProjects()
  }

  const handleStart = async (input: MeetingAnalysisInput) => {
    if (!project || projectsLoading || !projects.some((item) => item.projectId === project.projectId) || startLock.current) return
    const projectId = project.projectId
    const epoch = runEpoch.current
    startLock.current = true
    setError(null)
    setMeetingNotice(null)
    setStarting(true)
    try {
      const res = await api.requestMeetingAnalysis(projectId, input)
      if (epoch !== runEpoch.current) return
      activeRunId.current = res.taskId
      // 접수 완료 후 메타데이터 조회가 실패해도 새 처리 요청을 보내지 않는다.
      setMeeting({
        meetingId: res.meetingId, projectId, meetingDate: input.meetingDate,
        // 회의명은 백엔드가 분석·저장한 값만 보여 준다. 입력 파일명이나 날짜로 임의 제목을 만들지 않는다.
        title: input.title?.trim() || '',
        participantIds: [], originalFilename: input.file?.name ?? '', readyForAnalysis: true,
      })
      setTaskId(res.taskId)
      setStep('analyzing')
      api.getMeeting(res.meetingId).then((detail) => {
        if (epoch === runEpoch.current && activeRunId.current === res.taskId) setMeeting(detail)
      }).catch((e: unknown) => {
        if (epoch === runEpoch.current && activeRunId.current === res.taskId && !isUnauthorized(e)) {
          setMeetingNotice('처리는 접수됐습니다. 회의 상세 정보를 불러오지 못해 입력한 정보로 표시합니다.')
        }
      })
    } catch (e) {
      if (epoch === runEpoch.current && !isUnauthorized(e)) setError(toMessage(e))
    } finally {
      if (epoch === runEpoch.current) {
        startLock.current = false
        setStarting(false)
      }
    }
  }

  // 끝난 작업은 FAILED여도 항목별 결과가 있을 수 있어 먼저 결과를 불러온다.
  const handleFinish = async (task: AnalysisTask) => {
    if (activeRunId.current !== task.taskId) return
    try {
      const res = await api.getAnalysisItems(task.taskId)
      if (activeRunId.current !== task.taskId) return
      if (task.status === 'FAILED' && res.items.length === 0) {
        setError(task.errorMessage ?? '분석에 실패했습니다.')
        setStep('upload')
        return
      }
      setResult(res)
      setStep('result')
    } catch (e) {
      if (activeRunId.current === task.taskId && !isUnauthorized(e)) {
        setError(task.errorMessage ?? toMessage(e))
        setStep('upload')
      }
    }
  }

  const reset = () => {
    ++runEpoch.current
    ++memberRequest.current
    activeRunId.current = null
    startLock.current = false
    setStarting(false)
    setMeetingNotice(null)
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
      ++projectListRequest.current
      if (loggedIn.current) setAuthNotice('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      setLogoutError(null)
      reset()
      selectProject(null)
      setProjects([])
      setPage('my-tickets')
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
    setPage('my-tickets')
    setUser(loggedInUser)
  }

  const handleLogout = async () => {
    if (logoutPending.current || projectDeleting || ticketBusy || !canLeaveTickets()) return
    logoutPending.current = true
    setLoggingOut(true)
    setLogoutError(null)
    try {
      await api.logout()
      ++projectListRequest.current
      reset()
      selectProject(null)
      setProjects([])
      setPage('my-tickets')
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
    void refreshProjects()
    setEditorProjectId(detail.projectId)
    setProjectSavedMessage('프로젝트를 저장했습니다.')
    if (!project || project.projectId === detail.projectId) {
      selectProject(entry)
      setProjectError(null)
      // 기존 회의록 업로드의 참여자 표시는 기존 멤버 조회 API를 재사용한다.
      const version = ++memberRequest.current
      setMembers([])
      api.listMembers(detail.projectId).then((result) => {
        if (version === memberRequest.current) setMembers(result.members)
      }).catch((e: unknown) => {
        if (version === memberRequest.current && !isUnauthorized(e)) setProjectError(toMessage(e))
      })
    }
  }

  const pageTitle = page === 'my-tickets' ? '내 티켓' : page === 'workflow' ? '회의록 등록' : page === 'project-list' ? '프로젝트 관리' : '프로젝트 설정'

  return (
    <main className={`app-shell${user ? ' is-authenticated' : ''}${page === 'project-list' ? ' project-workspace' : ''}`}>
      {user && <WorkspaceNavigation page={page} name={user.name}
        disabled={{ tickets: projectSaving || projectDeleting || starting || step === 'analyzing' || ticketBusy || loggingOut, meeting: projectSaving || projectDeleting || ticketBusy || loggingOut, projects: step === 'analyzing' || starting || projectSaving || projectDeleting || ticketBusy || loggingOut }}
        logoutDisabled={projectSaving || projectDeleting || ticketBusy || starting || loggingOut} loggingOut={loggingOut} onLogout={() => void handleLogout()}
        onNavigate={next => { if (!canLeaveTickets()) return false; setPage(next); if (next === 'project-list') void refreshProjects(); return true }} />}


      {user && <div className="workspace-frame">
        <header className="workspace-topbar" aria-label="작업 공간 상단바">
          <p className="workspace-context"><span>Meet-to-Ticket</span><span aria-hidden="true">/</span><strong>{pageTitle}</strong></p>
          <div className="workspace-account">
            <span className="avatar" aria-hidden="true">{user.name.slice(0, 1)}</span>
            <strong>{user.name}</strong>
            <button type="button" className="workspace-logout" disabled={projectSaving || projectDeleting || ticketBusy || starting || loggingOut} onClick={() => void handleLogout()}>{loggingOut ? '로그아웃 중...' : '로그아웃'}</button>
          </div>
        </header>
      </div>}
      <section className="app-content">
        <header className="header">
          <div className="page-title">
            <h1>{user ? pageTitle : 'Meet-to-Ticket'}</h1>
            {user && page === 'project-list' && <span className="page-count" aria-label={`프로젝트 ${projects.length}개`}>{projects.length}</span>}
            {!user && <p className="muted">회의록에서 후속 작업을 만들고, 검토 후 발행하세요.</p>}
          </div>
          <div className="header-side">
            {user && page === 'project-list' && <button type="button" className="primary" disabled={projectsLoading || projectDeleting || loggingOut} onClick={() => { setEditorProjectId(null); setProjectSavedMessage(null); setPage('project-editor') }}>프로젝트 추가</button>}
          </div>
        </header>

      {user && logoutError && (
        <div role="alert">
          <p className="error">{logoutError}</p>
          <button type="button" className="link" onClick={handleLogout} disabled={loggingOut || projectSaving || projectDeleting || ticketBusy}>
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

      {user && page === 'my-tickets' && <MyTickets onBusyChange={setTicketBusy} onDirtyChange={setTicketDirty}
        onOpenProjectSettings={(projectId) => { setEditorProjectId(projectId); setProjectSavedMessage(null); setPage('project-editor') }} />}

      {user && page === 'project-list' && <ProjectList projects={projects} loading={projectsLoading} error={projectError}
        onReload={() => { void refreshProjects() }} onCreate={() => { setEditorProjectId(null); setProjectSavedMessage(null); setPage('project-editor') }}
        onEdit={(projectId) => { setEditorProjectId(projectId); setProjectSavedMessage(null); setPage('project-editor') }}
        onDeleted={handleProjectDeleted} onBusyChange={setProjectDeleting} />}

      {user && page === 'project-editor' && <>
        <button type="button" className="back-button" disabled={projectSaving} onClick={() => { setPage('project-list'); void refreshProjects() }}>프로젝트 목록</button>
        <Toast message={projectSavedMessage} onDone={() => setProjectSavedMessage(null)} />
        {projectError && <p className="error" role="alert">{projectError}</p>}
        <ProjectEditor key={editorProjectId ?? 'new'} projectId={editorProjectId}
          onSaved={handleProjectSaved} onSavingChange={setProjectSaving} onChange={() => setProjectSavedMessage(null)} onCancel={() => { setPage('project-list'); void refreshProjects() }} />
      </>}

      {user && page === 'workflow' && projectSelectionNotice && <p className="notice" role="status">{projectSelectionNotice}</p>}

      {user && page === 'workflow' && projectError && <p className="error">{projectError}</p>}

      {user && page === 'workflow' && (
        <>

          {error && <p className="error" role="alert">{error}</p>}
          {meetingNotice && <p className="notice" role="status">{meetingNotice}</p>}

          {/* 분석 중에도 마운트를 유지해 실패 시 입력값(날짜, 본문, 파일)을 보존한다. */}
          {(step === 'upload' || step === 'analyzing') && (
            <div hidden={step !== 'upload'}>
              <UploadForm
                key={uploadKey}
                projects={projects}
                projectId={project?.projectId ?? null}
                projectsLoading={projectsLoading}
                onProjectChange={handleProjectChange}
                starting={starting}
                onStart={handleStart}
              />
            </div>
          )}

          {step === 'analyzing' && taskId !== null && meeting && (
            <AnalysisProgress taskId={taskId} meetingTitle={meeting.title} onFinish={handleFinish} onCancel={reset} />
          )}

          {step === 'result' && meeting && result && taskId !== null && (
            <>
              {/* 기획 문서: 티켓을 담당자별로 보낸 결과 (TASK-023·024·025) */}
              <DispatchResult taskId={taskId} meeting={meeting} onUploadAnother={reset} />
            </>
          )}
        </>
      )}
      </section>
    </main>
  )
}

export default App
