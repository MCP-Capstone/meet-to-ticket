import { useEffect, useState } from 'react'
import type { AnalysisJob, AnalysisStep } from '../api'
import { useAnalysisPolling } from '../hooks/useAnalysisPolling'
import { Spinner } from './Spinner'

interface Props {
  jobId: number
  meetingTitle: string
  onFinish: (job: AnalysisJob) => void
  onCancel: () => void
}

const steps: { step: AnalysisStep; label: string; doing: string }[] = [
  { step: 'EXTRACTING', label: '할 일 추출', doing: '회의록에서 할 일을 추출하고 있습니다' },
  { step: 'REVIEWING', label: '검수', doing: '검수 Agent가 근거 / 담당자 / 기한을 검수하고 있습니다' },
  { step: 'PUBLISHING', label: 'GitHub 이슈 발행', doing: '검수를 통과한 항목을 GitHub 이슈로 발행하고 있습니다' },
]

function useElapsed(startedAt: string | undefined) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  if (!startedAt) return '0:00'
  const sec = Math.max(0, Math.floor((now - new Date(startedAt).getTime()) / 1000))
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
}

export function AnalysisProgress({ jobId, meetingTitle, onFinish, onCancel }: Props) {
  const { job, error } = useAnalysisPolling(jobId, onFinish)
  const elapsed = useElapsed(job?.startedAt)

  const currentIndex = job?.currentStep ? steps.findIndex((s) => s.step === job.currentStep) : -1
  const heading =
    currentIndex >= 0 ? steps[currentIndex].doing : job?.status === 'COMPLETED' ? '결과를 불러오고 있습니다' : '분석을 준비하고 있습니다'

  if (error) {
    return (
      <section className="card progress">
        <p className="error">{error}</p>
        <button className="link" onClick={onCancel}>
          처음으로
        </button>
      </section>
    )
  }

  return (
    <section className="card progress" aria-live="polite">
      <Spinner label="분석 중" />
      <div>
        <h2>{heading}</h2>
        <p className="muted">
          <strong>{meetingTitle}</strong> / 경과 {elapsed}
        </p>
      </div>

      <ol className="progress-steps">
        {steps.map(({ step, label }, i) => {
          const done = job?.status === 'COMPLETED' || (currentIndex >= 0 && i < currentIndex)
          const active = i === currentIndex
          return (
            <li key={step} className={done ? 'done' : active ? 'active' : ''}>
              <span className="dot">{done ? '✓' : active ? <Spinner size="sm" label={label} /> : i + 1}</span>
              {label}
            </li>
          )
        })}
      </ol>

      <p className="muted hint">회의록 길이에 따라 1~2분 걸릴 수 있습니다. 진행 상황은 2초마다 갱신됩니다.</p>
    </section>
  )
}
