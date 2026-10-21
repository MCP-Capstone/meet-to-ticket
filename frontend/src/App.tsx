import { useEffect, useRef, useState } from 'react'
import { AnalysisProgress } from './components/AnalysisProgress'
import { AuthForm } from './components/AuthForm'
import { ResultList } from './components/ResultList'
import { Spinner } from './components/Spinner'
import { Stepper } from './components/Stepper'
import { UploadForm } from './components/UploadForm'
import { api, ApiRequestError, onUnauthorized, useMock } from './api'
import type { AnalysisTask, ExecutionMode, Meeting, Project, ProjectMember, TaskItemList, User } from './api'

type Step = 'upload' | 'analyzing' | 'result'

const stepIndex = { upload: 0, analyzing: 1, result: 2 } as const

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

const isUnauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'

function App() {
  // undefined: 로그인 상태 확인 중, null: 로그인 안 함
  const [user, setUser] = useState<User | null | undefined>(undefined)
  const [authNotice, setAuthNotice] = useState<string | null>(null)
  // 401 처리기 안에서 최신 로그인 상태를 보려고 ref에도 둔다.
  const loggedIn = useRef(false)
  // 프로젝트 선택 화면 전까지는 첫 번째 프로젝트를 쓴다.
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
    api
      .me()
      .then(setUser)
      .catch(() => setUser(null))
  }, [])

  // 로그인하면 프로젝트와 팀원을 불러온다.
  useEffect(() => {
    if (!user) return
    let cancelled = false
    const load = async () => {
      try {
        const list = await api.listProjects()
        if (cancelled) return
        setExecutionMode(list.executionMode)
        const first = list.projects[0]
        if (!first) {
          setProjectError('참여 중인 프로젝트가 없습니다. 관리자에게 프로젝트 등록을 요청해 주세요.')
          return
        }
        const memberList = await api.listMembers(first.projectId)
        if (cancelled) return
        setProject(first)
        setMembers(memberList.members)
      } catch (e) {
        if (!cancelled && !isUnauthorized(e)) setProjectError(toMessage(e))
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
      reset()
      setProject(null)
      setMembers([])
      setUser(null)
    })
    return () => onUnauthorized(null)
  }, [])

  const handleLogin = (loggedInUser: User) => {
    setAuthNotice(null)
    setProjectError(null)
    setUser(loggedInUser)
  }

  const handleLogout = async () => {
    await api.logout().catch(() => undefined)
    reset()
    setProject(null)
    setMembers([])
    setUser(null)
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
              <button className="link" onClick={handleLogout}>
                로그아웃
              </button>
            </>
          )}
        </div>
      </header>

      {user === undefined && (
        <p className="loading">
          <Spinner size="sm" label="로그인 확인 중" /> 로그인 상태를 확인하고 있습니다...
        </p>
      )}

      {user === null && <AuthForm onLogin={handleLogin} notice={authNotice} />}

      {user && projectError && <p className="error">{projectError}</p>}

      {user && !project && !projectError && (
        <p className="loading">
          <Spinner size="sm" label="프로젝트 불러오는 중" /> 프로젝트를 불러오고 있습니다...
        </p>
      )}

      {user && project && (
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
