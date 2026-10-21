import { useEffect, useState } from 'react'
import type { AnalysisTask, TaskStage } from '../api'
import { useAnalysisPolling } from '../hooks/useAnalysisPolling'
import { Spinner } from './Spinner'

interface Props {
  taskId: string
  meetingTitle: string
  onFinish: (task: AnalysisTask) => void
  onCancel: () => void
}

const stages: { stage: TaskStage; label: string; doing: string }[] = [
  { stage: 'EXTRACTING', label: '할 일 추출', doing: '회의록에서 할 일을 추출하고 있습니다' },
  { stage: 'NORMALIZING', label: '담당자 / 기한 정리', doing: '담당자와 기한을 팀원 / 회의 날짜 기준으로 정리하고 있습니다' },
  { stage: 'REVIEWING', label: '검수', doing: '검수 Agent가 근거 / 담당자 / 기한을 검수하고 있습니다' },
  { stage: 'PUBLISHING', label: 'GitHub 이슈 발행', doing: '검수를 통과한 항목을 GitHub 이슈로 발행하고 있습니다' },
  { stage: 'VERIFYING', label: '발행 결과 확인', doing: '생성된 이슈를 확인하고 있습니다' },
]

const isFinished = (task: AnalysisTask | null) =>
  task?.status === 'COMPLETED' || task?.status === 'PARTIALLY_FAILED' || task?.status === 'FAILED'

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

export function AnalysisProgress({ taskId, meetingTitle, onFinish, onCancel }: Props) {
  const { task, error } = useAnalysisPolling(taskId, onFinish)
  const elapsed = useElapsed(task?.createdAt)

  const finished = isFinished(task)
  const currentIndex = task?.stage ? stages.findIndex((s) => s.stage === task.stage) : -1
  const heading = finished
    ? '결과를 불러오고 있습니다'
    : task?.status === 'RUNNING' && currentIndex >= 0
      ? stages[currentIndex].doing
      : '분석 순서를 기다리고 있습니다'

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
        {stages.map(({ stage, label }, i) => {
          const done = finished || (currentIndex >= 0 && i < currentIndex)
          const active = !finished && i === currentIndex
          return (
            <li key={stage} className={done ? 'done' : active ? 'active' : ''}>
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
