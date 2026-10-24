import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket } from '../api'
import { meetingGroups, publishSelection } from '../batchPublish'
import { PublishBatchResult } from './PublishBatchResult'

interface Props {
  tickets: MyTicket[]
  busy: boolean
  dirty: boolean
  onLock: (value: boolean) => void
  onRefresh: () => Promise<boolean>
}
export function BatchPublishFlow({ tickets, busy, dirty, onLock, onRefresh }: Props) {
  const [selection, setSelection] = useState<MyTicket[] | null>(null)
  const [batchId, setBatchId] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const lock = useRef(false)
  const mounted = useRef(true)
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => { if (selection) dialog.current?.showModal(); else dialog.current?.close() }, [selection])
  const done = useCallback(() => { void onRefresh() }, [onRefresh])
  async function publish() {
    if (!selection || lock.current || dirty || uncertain) return
    lock.current = true
    setSending(true)
    onLock(true)
    setError(null)
    try {
      const first = selection[0]
      const result = await api.publishTickets(first.projectId, first.meetingId, selection.map((t) => t.ticketId))
      if (!mounted.current) return
      setBatchId(result.batchId)
      setSelection(null)
      // 접수 성공 이후에는 목록 조회가 실패해도 POST를 다시 보내지 않는다.
      await onRefresh()
    } catch (e) {
      if (!mounted.current) return
      if (!(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) {
        setError(e instanceof Error ? e.message : '발행 접수 여부를 확인하지 못했습니다.')
        // 네트워크·서버 오류는 이미 접수됐을 수 있어 자동/즉시 재전송하지 않는다.
        if (!(e instanceof ApiRequestError) || e.status >= 500 && e.code !== 'AGENT_ACCEPTANCE_FAILED') setUncertain(true)
        setSelection(null)
        await onRefresh()
      }
    } finally {
      lock.current = false
      if (mounted.current) { setSending(false); onLock(false) }
    }
  }
  return <>
    <section className="card" aria-label="회의별 일괄 발행"><h2>회의별 발행</h2><p className="muted">이 회의의 내 티켓을 모두 확인 또는 거절한 뒤, 확인한 티켓만 발행합니다. 다른 담당자의 검토를 기다릴 필요는 없습니다.</p>
      {dirty && <p className="notice">수정 중인 티켓을 저장하거나 취소한 뒤 발행하세요.</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {uncertain && <p className="notice">이미 접수됐을 수 있어 재발행을 막았습니다. 목록을 새로고침하고 발행 대상 도구에 반영됐는지 확인하세요.</p>}
      <ul className="publish-groups">{meetingGroups(tickets).map((group) => {
        const state = publishSelection(group.tickets)
        return <li key={group.key}><div><strong>{group.first.meetingTitle}</strong><p className="muted">{group.first.projectName} · 회의 #{group.first.meetingId} · 검토 대기 {state.pending}건 · 확인 {state.confirmed.length}건 · 거절 {state.rejected}건</p>
          {state.reason && <p className="muted" id={`publish-reason-${group.key}`}>{state.reason}</p>}</div>
          <button type="button" className="primary" disabled={busy || dirty || sending || uncertain || Boolean(state.reason)} aria-describedby={state.reason ? `publish-reason-${group.key}` : undefined} onClick={() => { setError(null); setSelection(state.confirmed) }}>확인한 {state.confirmed.length}건 발행</button>
        </li>
      })}</ul>
    </section>
    <dialog ref={dialog} className="publish-dialog" aria-labelledby="publish-confirm-title" onCancel={(event) => { if (sending) event.preventDefault(); else setSelection(null) }}>
      <h2 id="publish-confirm-title">티켓을 발행할까요?</h2>
      {selection && <><p>{selection[0].projectName} / {selection[0].meetingTitle}</p><p>확인한 {selection.length}건을 발행합니다. 거절한 티켓은 제외됩니다.</p><ul>{selection.map((t) => <li key={t.ticketId}>{t.title}</li>)}</ul></>}
      <div className="ticket-actions"><button type="button" className="secondary" disabled={sending} onClick={() => setSelection(null)}>취소</button><button type="button" className="primary" disabled={sending || dirty} onClick={() => void publish()}>{sending ? '접수 중...' : '발행하기'}</button></div>
    </dialog>
    {batchId && <PublishBatchResult key={batchId} batchId={batchId} onDone={done} onClose={() => setBatchId(null)} />}
  </>
}
