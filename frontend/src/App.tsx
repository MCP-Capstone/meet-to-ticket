import { useEffect, useState } from 'react'
import { ActionItemList } from './components/ActionItemList'
import { AuthForm } from './components/AuthForm'
import { ExtractingView } from './components/ExtractingView'
import { MeetingForm } from './components/MeetingForm'
import { Stepper } from './components/Stepper'
import { api, useMock } from './api'
import type { ActionItem, CreateMeetingRequest, Meeting, User } from './api'

type Step = 'input' | 'extracting' | 'result'

const stepIndex = { input: 0, extracting: 1, result: 2 } as const

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

function App() {
  // undefined: 로그인 상태 확인 중, null: 로그인 안 함
  const [user, setUser] = useState<User | null | undefined>(undefined)
  const [step, setStep] = useState<Step>('input')
  const [meetingTitle, setMeetingTitle] = useState('')
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [actionItems, setActionItems] = useState<ActionItem[]>([])
  const [error, setError] = useState<string | null>(null)

  // 새로고침해도 세션이 남아 있으면 로그인 상태를 이어간다.
  useEffect(() => {
    api
      .me()
      .then(setUser)
      .catch(() => setUser(null))
  }, [])

  const handleExtract = async (body: CreateMeetingRequest) => {
    setError(null)
    setMeetingTitle(body.title)
    setStep('extracting')
    try {
      const created = await api.createMeeting(body)
      const res = await api.extractActionItems(created.meetingId)
      setMeeting(created)
      setActionItems(res.actionItems)
      setStep('result')
    } catch (e) {
      setError(toMessage(e))
      setStep('input')
    }
  }

  const reset = () => {
    setMeeting(null)
    setActionItems([])
    setError(null)
    setStep('input')
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
          <p className="muted">회의록에서 할 일을 찾아 티켓으로 정리합니다.</p>
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

      {user === null && <AuthForm onLogin={setUser} />}

      {user && (
        <>
          <Stepper current={stepIndex[step]} />

          {error && <p className="error">{error}</p>}

          {/* 입력 폼은 추출 중에도 내용을 유지하도록 숨기기만 한다. */}
          <div hidden={step !== 'input'}>
            <MeetingForm onSubmit={handleExtract} />
          </div>

          {step === 'extracting' && <ExtractingView meetingTitle={meetingTitle} />}

          {step === 'result' && meeting && (
            <>
              <ActionItemList meeting={meeting} actionItems={actionItems} />
              <button className="link" onClick={reset}>
                새 회의록 입력
              </button>
            </>
          )}
        </>
      )}
    </main>
  )
}

export default App
