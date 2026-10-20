import { useState } from 'react'
import { ActionItemList } from './components/ActionItemList'
import { MeetingForm } from './components/MeetingForm'
import { api, ApiRequestError, useMock } from './api'
import type { ActionItem, CreateMeetingRequest, Ticket } from './api'

type Step = 'input' | 'extracting' | 'review' | 'creating'

const toMessage = (e: unknown) =>
  e instanceof ApiRequestError || e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.'

function App() {
  const [step, setStep] = useState<Step>('input')
  const [actionItems, setActionItems] = useState<ActionItem[]>([])
  const [tickets, setTickets] = useState<Ticket[]>([])
  const [error, setError] = useState<string | null>(null)

  const handleExtract = async (body: CreateMeetingRequest) => {
    setError(null)
    setStep('extracting')
    try {
      const meeting = await api.createMeeting(body)
      const res = await api.extractActionItems(meeting.meetingId)
      setActionItems(res.actionItems)
      setTickets([])
      setStep('review')
    } catch (e) {
      setError(toMessage(e))
      setStep('input')
    }
  }

  const handleCreateTickets = async () => {
    setError(null)
    setStep('creating')
    try {
      const res = await api.createTickets({
        actionItemIds: actionItems.map((item) => item.actionItemId),
        target: 'GITHUB',
      })
      setTickets(res.tickets)
    } catch (e) {
      setError(toMessage(e))
    } finally {
      setStep('review')
    }
  }

  const reset = () => {
    setActionItems([])
    setTickets([])
    setError(null)
    setStep('input')
  }

  return (
    <main className="container">
      <header className="header">
        <h1>meet-to-ticket</h1>
        {useMock && <span className="badge mock">MOCK</span>}
      </header>

      {error && <p className="error">{error}</p>}

      {step === 'input' || step === 'extracting' ? (
        <>
          <MeetingForm disabled={step === 'extracting'} onSubmit={handleExtract} />
          {step === 'extracting' && <p className="muted center">할 일을 추출하고 있습니다...</p>}
        </>
      ) : (
        <>
          <ActionItemList
            actionItems={actionItems}
            tickets={tickets}
            creating={step === 'creating'}
            onCreateTickets={handleCreateTickets}
          />
          <button className="link" onClick={reset} disabled={step === 'creating'}>
            새 회의록 입력
          </button>
        </>
      )}
    </main>
  )
}

export default App
