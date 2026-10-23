import { useEffect, useState } from 'react'
import type { AnalysisTask } from '../api'
import { useAnalysisPolling } from '../hooks/useAnalysisPolling'
import { progressDetails, progressHeading, progressIndex, progressStateLabels, progressStates, progressSteps } from '../analysisProgress'
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

  const states = progressStates(task)
  const details = progressDetails(task?.progress)
  const missingProgress = task?.status === 'RUNNING' && progressIndex(task) < 0

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

  return (
    <section className="card progress">
      <Spinner label="처리 상태 확인 중" />
      <div>
        <h2 aria-live="polite">{progressHeading(task)}</h2>
        <p className="muted">
          <strong>{meetingTitle}</strong> / 경과 {elapsed}
        </p>
      </div>

      {missingProgress && <p className="muted" role="status">상세 진행 단계를 확인할 수 없어 작업 상태를 계속 확인하고 있습니다.</p>}

      <ol className="progress-steps" aria-label="회의록 처리 단계">
        {progressSteps.map(({ step, label }, i) => {
          const state = states[i]
          return (
            <li key={step} className={state} aria-current={state === 'active' ? 'step' : undefined}>
              <span className="dot" aria-hidden="true">{state === 'done' ? '✓' : state === 'stopped' ? '!' : i + 1}</span>
              <span className="progress-step-label">{label}</span>
              <span className="progress-step-state">{progressStateLabels[state]}</span>
            </li>
          )
        })}
      </ol>

      {details.length > 0 && <div className="progress-details" aria-label="처리 세부 정보">
        {details.map(({ title, values }) => <section key={title}>
          <h3>{title}</h3>
          <dl>{values.map(({ label, value }) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
        </section>)}
      </div>}
      <p className="muted hint">진행 상황은 2초마다 갱신됩니다. 초안 생성 완료는 티켓 발행 완료를 의미하지 않습니다.</p>
    </section>
  )
}
