import { useEffect, useState } from 'react'
import { api, ApiRequestError } from '../api'

/**
 * 티켓 초안의 근거는 평소에는 접어 두고, 검토자가 필요할 때만 원문 인용을 확인한다.
 * 발행 포맷 미리보기와 달리 이 컴포넌트는 근거만 읽는다.
 */
export function TicketEvidence({ ticketId }: { ticketId: string }) {
  const [open, setOpen] = useState(false)
  const [quotes, setQuotes] = useState<string[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open || quotes !== null || error) return
    let cancelled = false
    void api.getTicketPreview(ticketId)
      .then((preview) => {
        if (!cancelled) setQuotes(preview.backlog.evidenceQuotes)
      })
      .catch((e: unknown) => {
        if (!cancelled && !(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) {
          setError(e instanceof Error ? e.message : '회의록 근거를 불러오지 못했습니다.')
        }
      })
    return () => { cancelled = true }
  }, [error, open, quotes, ticketId])

  return <details className="ticket-evidence" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary>회의록 근거</summary>
    {!open ? null : error ? <p className="error">{error}</p>
      : quotes === null ? <p className="muted">근거를 불러오는 중입니다.</p>
        : quotes.length ? <ul>{quotes.map((quote, index) => <li key={index + ':' + quote}>{quote}</li>)}</ul>
          : <p className="muted">이 티켓에 저장된 회의록 인용이 없습니다.</p>}
  </details>
}
