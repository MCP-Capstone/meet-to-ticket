import { useEffect, useState } from 'react'
import type { AnalysisTask } from '../api'
import { useAnalysisPolling } from '../hooks/useAnalysisPolling'
import { progressHeading, progressStates, progressSteps } from '../analysisProgress'
import { Spinner } from './Spinner'

interface Props {
  taskId: string
  meetingTitle: string
  onFinish: (task: AnalysisTask) => void
  onCancel: () => void
}

function useElapsed(startedAt: string | undefined) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  if (!startedAt) return '0:00'
  const start = new Date(startedAt).getTime()
  if (!Number.isFinite(start)) return '0:00'
  const sec = Math.max(0, Math.floor((now - start) / 1000))
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
}

export function AnalysisProgress({ taskId, meetingTitle, onFinish, onCancel }: Props) {
  const { task, error } = useAnalysisPolling(taskId, onFinish)
  const elapsed = useElapsed(task?.createdAt)

  if (error) {
    return (
      <section className="card progress">
        <p className="error" role="alert">{error}</p>
        <p className="muted">상태 조회가 중단되었습니다. 접수된 작업의 실행 여부는 이 화면만으로 확인할 수 없습니다.</p>
        <button className="link" onClick={onCancel}>
          처음으로
        </button>
      </section>
    )
  }

  // 단계 이름 목록 대신 지금 하는 일 한 문장과 몇 번째 단계인지만 보여 준다.
  const states = progressStates(task)
  const active = states.indexOf('active')
  return (
    <section className="card progress">
      <Spinner label="티켓 초안 만드는 중" />
      <h2>티켓 초안을 만들고 있어요</h2>
      <p className="progress-status" aria-live="polite">{progressHeading(task)}</p>
      <div className="progress-bar" role="progressbar" aria-label="처리 단계" aria-valuemin={0} aria-valuemax={progressSteps.length} aria-valuenow={active < 0 ? states.filter((state) => state === 'done').length : active + 1}>
        {states.map((state, i) => <span key={progressSteps[i].step} className={state} />)}
      </div>
      <p className="muted">{meetingTitle} · 경과 {elapsed}</p>
    </section>
  )
}
