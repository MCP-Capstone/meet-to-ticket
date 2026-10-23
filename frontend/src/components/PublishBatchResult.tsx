import { useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { PublishBatchResult as BatchResult } from '../api'
import { safeIssueUrl } from '../batchPublish'
import { failureGuide } from '../publishFailure'
import { Spinner } from './Spinner'

const labels = { PUBLISHED: '발행됨', LINKED: '연결됨', UPDATED: '갱신됨', FAILED: '실패', PUBLISHING: '발행 중' }
interface Props { batchId: string; onDone: () => void; onClose: () => void }
export function PublishBatchResult({ batchId, onDone, onClose }: Props) {
  const [result, setResult] = useState<BatchResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const notified = useRef<string | null>(null)
  useEffect(() => {
    let cancelled = false
    let timer: number | undefined
    async function poll() {
      setError(null)
      try {
        const next = await api.getPublishResult(batchId)
        if (cancelled) return
        setResult(next)
        if (next.status === 'RUNNING') timer = window.setTimeout(() => void poll(), 2000)
        else if (notified.current !== batchId) { notified.current = batchId; onDone() }
      } catch (e) {
        if (!cancelled && !(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) setError(e instanceof Error ? e.message : '발행 결과를 조회하지 못했습니다.')
      }
    }
    void poll()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [batchId, attempt, onDone])
  const running = !result || result.status === 'RUNNING'
  return <section className="card publish-result" aria-label="발행 결과">
    <div className="card-header"><h2>발행 결과</h2><button type="button" className="secondary" onClick={onClose}>결과 닫기</button></div>
    <p className="muted">발행 접수 완료 · 묶음 ID: {batchId}</p>
    {error ? <div role="alert"><p className="error">{error}</p><p>발행을 다시 요청하지 않고 결과만 조회합니다.</p><button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>결과 조회 다시 시도</button></div>
      : <p role="status">{running ? <><Spinner size="sm" label="발행 결과 확인 중" /> 발행 결과를 확인하고 있습니다.</> : result.status === 'SUCCEEDED' ? '모든 티켓 처리가 완료됐습니다.' : result.status === 'PARTIAL_FAILED' ? '일부 티켓 처리에 실패했습니다.' : '티켓 처리에 실패했습니다.'}</p>}
    {result && <>
      {result.executionMode === 'MOCK' && <p className="notice">모의 실행 결과입니다. 실제 GitHub 이슈는 생성하지 않습니다.</p>}
      <dl className="ticket-counts"><div><dt>전체</dt><dd>{result.summary.total}</dd></div><div><dt>발행</dt><dd>{result.summary.published}</dd></div><div><dt>연결</dt><dd>{result.summary.linked}</dd></div><div><dt>갱신</dt><dd>{result.summary.updated}</dd></div><div><dt>실패</dt><dd>{result.summary.failed}</dd></div><div><dt>처리 중</dt><dd>{result.summary.publishing}</dd></div></dl>
      <ul className="publish-entries">{result.tickets.map((ticket) => {
        const url = safeIssueUrl(ticket.githubIssueUrl)
        return <li key={ticket.ticketId}><strong>{ticket.title}</strong><p>{labels[ticket.status as keyof typeof labels] ?? ticket.status}</p>
          {url && <a href={url} target="_blank" rel="noreferrer">GitHub 이슈 {ticket.githubIssueNumber ? `#${ticket.githubIssueNumber}` : '열기'}</a>}
          {(ticket.status === 'FAILED' || ticket.errorCode || ticket.errorMessage) && <p className="error">{failureGuide(ticket)}</p>}
          {ticket.outcomeUnknown && <p className="notice">GitHub에 반영됐을 수 있습니다. 다시 시도하기 전에 확인하세요.</p>}
        </li>
      })}</ul>
    </>}
  </section>
}
