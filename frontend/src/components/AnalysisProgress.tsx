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

// 진행 중이면 서버가 보낸 숫자로 지금 하는 일을 한 문장으로 말한다. 숫자가 아직 없거나 대기·종료면 기본 문구를 쓴다.
const count = (value: number | null | undefined) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null
function statusText(task: AnalysisTask | null) {
  const p = task?.progress
  if (task?.status !== 'RUNNING' || !p) return progressHeading(task)
  if (p.step === 'CONTEXT') {
    const parts = [count(p.context?.issues) !== null && `기존 이슈 ${count(p.context?.issues)}개`, count(p.context?.members) !== null && `멤버 ${count(p.context?.members)}명`, count(p.context?.notionDocs) !== null && `Notion 문서 ${count(p.context?.notionDocs)}개`].filter(Boolean)
    return parts.length ? `프로젝트 맥락을 불러왔어요 · ${parts.join(', ')}` : '프로젝트 맥락을 불러오고 있어요'
  }
  if (p.step === 'EXTRACTING') return count(p.extraction?.todos) !== null ? `회의록에서 할 일 ${count(p.extraction?.todos)}건을 찾았어요` : '회의록에서 할 일을 찾고 있어요'
  if (p.step === 'DEDUPING') {
    const done = count(p.dedupe?.done), total = count(p.dedupe?.total), linked = count(p.dedupe?.linked)
    return total ? `기존 이슈와 비교하고 있어요 · ${done ?? 0}/${total}${linked ? ` · 연결 ${linked}건` : ''}` : '기존 이슈와 비교하고 있어요'
  }
  if (p.step === 'DISPATCHING') return count(p.extraction?.todos) !== null ? `티켓 ${count(p.extraction?.todos)}건을 담당자에게 보내고 있어요` : '담당자에게 보내고 있어요'
  return progressHeading(task)
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
      <p className="progress-status" aria-live="polite">{statusText(task)}</p>
      <div className="progress-bar" role="progressbar" aria-label="처리 단계" aria-valuemin={0} aria-valuemax={progressSteps.length} aria-valuenow={active < 0 ? states.filter((state) => state === 'done').length : active + 1}>
        {states.map((state, i) => <span key={progressSteps[i].step} className={state} />)}
      </div>
      <p className="muted">{meetingTitle} · 경과 {elapsed}</p>
      <p className="muted progress-note">창을 닫아도 계속 처리되고, 끝나면 담당자의 내 티켓으로 들어가요.</p>
    </section>
  )
}
