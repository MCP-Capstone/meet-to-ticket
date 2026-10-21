import { useEffect, useRef, useState } from 'react'
import { AnalysisProgress } from './components/AnalysisProgress'
import { AuthForm } from './components/AuthForm'
import { ResultList } from './components/ResultList'
import { Spinner } from './components/Spinner'
import { Stepper } from './components/Stepper'
import { UploadForm } from './components/UploadForm'
import { api, ApiRequestError, onUnauthorized, useMock } from './api'
import type { AnalysisJob, Meeting, TicketResult, User } from './api'

type Step = 'upload' | 'analyzing' | 'result'

const stepIndex = { upload: 0, analyzing: 1, result: 2 } as const

// 프로젝트 선택 화면을 만들기 전까지 시드 데이터의 데모 프로젝트를 쓴다.
const DEMO_PROJECT_ID = 1

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

const isUnauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'

function App() {
  // undefined: 로그인 상태 확인 중, null: 로그인 안 함
  const [user, setUser] = useState<User | null | undefined>(undefined)
  const [authNotice, setAuthNotice] = useState<string | null>(null)
  // 401 처리기 안에서 최신 로그인 상태를 보려고 ref에도 둔다.
  const loggedIn = useRef(false)
  const [step, setStep] = useState<Step>('upload')
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [jobId, setJobId] = useState<number | null>(null)
  const [starting, setStarting] = useState(false)
  const [results, setResults] = useState<TicketResult[]>([])
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

  const handleStart = async (target: Meeting) => {
    setError(null)
    setStarting(true)
    try {
      const res = await api.startAnalysis(target.meetingId)
      setMeeting(target)
      setJobId(res.jobId)
      setStep('analyzing')
    } catch (e) {
      if (!isUnauthorized(e)) setError(toMessage(e))
    } finally {
      setStarting(false)
    }
  }

  const handleFinish = async (job: AnalysisJob) => {
    if (job.status === 'FAILED') {
      setError(job.failureReason ?? '분석에 실패했습니다.')
      setStep('upload')
      return
    }
    try {
      const res = await api.getAnalysisResults(job.jobId)
      setResults(res.results)
      setStep('result')
    } catch (e) {
      if (!isUnauthorized(e)) {
        setError(toMessage(e))
        setStep('upload')
      }
    }
  }

  const reset = () => {
    setMeeting(null)
    setJobId(null)
    setResults([])
    setError(null)
    setStep('upload')
    setUploadKey((k) => k + 1)
  }

  // 세션이 만료되면 작업 중이던 화면을 지우고 로그인 화면으로 보낸다.
  useEffect(() => {
    onUnauthorized(() => {
      if (loggedIn.current) setAuthNotice('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      reset()
      setUser(null)
    })
    return () => onUnauthorized(null)
  }, [])

  const handleLogin = (loggedInUser: User) => {
    setAuthNotice(null)
    setUser(loggedInUser)
  }

  const handleLogout = async () => {
    await api.logout().catch(() => undefined)
    reset()
    setUser(null)
  }

  return (
    <main className="container">
      <header className="header">
        <div>
          <h1>meet-to-ticket</h1>
          <p className="muted">회의록에서 할 일을 찾아 GitHub 이슈로 발행합니다.</p>
        </div>
        <div className="header-side">
          {useMock && <span className="badge mock">MOCK</span>}
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

      {user && (
        <>
          <Stepper current={stepIndex[step]} />

          {error && <p className="error">{error}</p>}

          {step === 'upload' && (
            <UploadForm key={uploadKey} projectId={DEMO_PROJECT_ID} starting={starting} onStart={handleStart} />
          )}

          {step === 'analyzing' && jobId !== null && meeting && (
            <AnalysisProgress jobId={jobId} meetingTitle={meeting.title} onFinish={handleFinish} onCancel={reset} />
          )}

          {step === 'result' && meeting && (
            <>
              <ResultList meeting={meeting} results={results} />
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
