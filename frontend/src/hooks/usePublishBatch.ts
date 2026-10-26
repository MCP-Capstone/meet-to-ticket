import { useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { PublishBatchResult } from '../api'
import { batchStartedAt, formatElapsed, slowPublishNotice } from '../batchPublish'
import type { NotificationItem } from '../components/Notification'

/**
 * 접수한 발행 묶음의 결과를 끝날 때까지 2초마다 조회한다. 화면에 따로 목록을 그리지 않고,
 * 발행 버튼에 진행 상태를 보여 주고 끝나면 onDone으로 알린다. 조회만 다시 하고 발행은 다시 보내지 않는다.
 */
// started: 발행을 접수한 시각. 버튼 이벤트에서 기록해 넘긴다(렌더 중에 시계를 읽지 않는다).
export function usePublishBatch(batch: { id: string; started: number } | null, onDone: (result: PublishBatchResult) => void) {
  const batchId = batch?.id ?? null
  const [result, setResult] = useState<PublishBatchResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const done = useRef(onDone)
  useEffect(() => { done.current = onDone })
  useEffect(() => {
    if (!batchId) return
    let cancelled = false
    let timer: number | undefined
    async function poll() {
      setError(null)
      try {
        const next = await api.getPublishResult(batchId!)
        if (cancelled) return
        setResult(next)
        if (next.status === 'RUNNING') timer = window.setTimeout(() => void poll(), 2000)
        else done.current(next)
      } catch (e) {
        if (!cancelled && !(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) setError(e instanceof Error ? e.message : '발행 결과를 조회하지 못했습니다.')
      }
    }
    void poll()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [batchId, attempt])
  const running = Boolean(batchId) && !error && (!result || result.status === 'RUNNING')
  // 결과를 기다린 시간. 묶음 생성 시각부터 세고, 오래 걸리면 화면을 닫아도 된다고 안내한다 (#302)
  const openedAt = batch?.started ?? 0
  const [now, setNow] = useState(openedAt)
  // 새 묶음을 받으면 지난 묶음의 결과와 시간을 지운다. 그대로 두면 새 발행이 바로 끝난 것처럼 보인다.
  const [tracked, setTracked] = useState(batchId)
  if (tracked !== batchId) {
    setTracked(batchId)
    setNow(openedAt)
    setResult(null)
    setError(null)
  }
  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [running])
  const elapsed = (now - batchStartedAt(result?.createdAt, openedAt)) / 1000
  return {
    running,
    elapsedText: formatElapsed(Math.max(0, elapsed)),
    slowNotice: running ? slowPublishNotice(elapsed) : null,
    error,
    retry: () => setAttempt((n) => n + 1),
  }
}

// 끝난 결과를 알림 카드 내용으로 바꾼다. 예: 발행을 마쳤어요 / 주간 회의 · 발행 4
// 모의 실행으로 발행한 티켓(skipped)도 실제 발행과 같게 센다.
export function publishNotice(result: PublishBatchResult, meetingTitle: string): Omit<NotificationItem, 'id' | 'action'> {
  const { summary } = result
  const published = summary.published + summary.linked + summary.updated + summary.skipped
  const parts = [['발행', published], ['확인 필요', summary.failed]] as const
  const counts = parts.filter(([, n]) => n > 0).map(([label, n]) => `${label} ${n}`).join(' · ')
  const title = result.status === 'FAILED' ? '발행하지 못했어요'
    : result.status === 'PARTIAL_FAILED' ? '일부 티켓을 발행하지 못했어요'
      : '발행을 마쳤어요'
  const tone = result.status === 'FAILED' ? 'error' : result.status === 'PARTIAL_FAILED' ? 'warning' : 'success'
  const lines = [[meetingTitle, counts].filter(Boolean).join(' · ')]
  if (summary.failed > 0) lines.push('진행 중 탭에서 확인이 필요한 티켓을 봐 주세요.')
  return { tone, title, lines: lines.filter(Boolean) }
}
