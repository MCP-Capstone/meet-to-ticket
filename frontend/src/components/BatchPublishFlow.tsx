import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket } from '../api'
import { singleLineLabel } from '../displayText'
import { publishSelection } from '../batchPublish'
import { PublishBatchResult } from './PublishBatchResult'

interface Props {
  // 한 회의의 내 티켓. 발행은 회의 단위로 하고, 내 티켓을 모두 확인·거절해야 열린다.
  tickets: MyTicket[]
  busy: boolean
  dirty: boolean
  onLock: (value: boolean) => void
  onRefresh: () => Promise<boolean>
  children: ReactNode
}
const shortDate = (value: string) => {
  const date = new Date(value)
  return Number.isFinite(date.getTime()) ? `${date.getMonth() + 1}/${date.getDate()}` : null
}

/** 회의 묶음: 머리에 진행 막대와 발행 버튼을 두고, 아래에 티켓을 놓는다. */
export function BatchPublishFlow({ tickets, busy, dirty, onLock, onRefresh, children }: Props) {
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
  const first = tickets[0]
  const state = publishSelection(tickets)
  const decided = tickets.length - state.pending
  const ready = !state.reason
  const reasonId = `publish-reason-${first.projectId}-${first.meetingId}`
  const created = shortDate(first.createdAt)
  // 수정 중에는 안내 상자를 띄우지 않고(레이아웃이 밀리지 않게) 버튼만 잠근 뒤 이유는 툴팁으로 알린다.
  const disabled = busy || dirty || sending || uncertain || !ready
  return <section className="card meeting-group" aria-label={`${singleLineLabel(first.meetingTitle)} 회의`}>
    <header className="meeting-group-head">
      <div className="meeting-group-title">
        <h3>{singleLineLabel(first.meetingTitle)}</h3>
        <p className="muted">{created ? `${created} 등록 · ` : ''}내 티켓 {tickets.length}건</p>
      </div>
      <div className="meeting-group-publish">
        <span id={reasonId} className={ready ? 'meeting-group-ready' : 'muted'}>{ready ? '모두 확인했어요' : state.reason}</span>
        <span className="meeting-group-meter" aria-label={`${tickets.length}건 중 ${decided}건 확인`}><span><i style={{ width: `${tickets.length ? (decided / tickets.length) * 100 : 0}%` }} /></span>{decided}/{tickets.length}</span>
        <button type="button" className="primary" disabled={disabled} aria-describedby={reasonId} title={dirty ? '수정 중인 티켓을 저장하거나 취소하면 발행할 수 있어요' : undefined} onClick={() => { setError(null); setSelection(state.confirmed) }}>승인한 {state.confirmed.length}건 발행</button>
      </div>
    </header>
    {error && <p className="error meeting-group-alert" role="alert">{error}</p>}
    {uncertain && <p className="notice meeting-group-alert">이미 접수됐을 수 있어 재발행을 막았습니다. 목록을 새로고침하고 발행 대상 도구에 반영됐는지 확인하세요.</p>}
    {batchId && <PublishBatchResult key={batchId} batchId={batchId} onDone={done} onClose={() => setBatchId(null)} />}
    {children}
    <dialog ref={dialog} className="publish-dialog" aria-labelledby="publish-confirm-title" onCancel={(event) => { if (sending) event.preventDefault(); else setSelection(null) }}>
      <h2 id="publish-confirm-title">티켓을 발행할까요?</h2>
      {selection && <><p>{selection[0].projectName} / {singleLineLabel(selection[0].meetingTitle)}</p><p>승인한 {selection.length}건을 발행합니다. 거절한 티켓은 제외됩니다.</p><p className="muted">GitHub 티켓은 발행 직전에 중복 여부를 다시 확인합니다.</p><p className="publish-target-summary">{(['GITHUB', 'NOTION', 'SLACK'] as const).map(target => { const count = selection.filter(item => item.publishTarget === target).length; return count ? `${target === 'GITHUB' ? 'GitHub' : target === 'NOTION' ? 'Notion' : 'Slack'} ${count}건` : null }).filter(Boolean).join(' · ')}</p><ul>{selection.map((t) => <li key={t.ticketId}>{singleLineLabel(t.title, '제목 없음')}</li>)}</ul></>}
      <div className="ticket-actions"><button type="button" className="secondary" disabled={sending} onClick={() => setSelection(null)}>취소</button><button type="button" className="primary" disabled={sending || dirty} onClick={() => void publish()}>{sending ? '접수 중...' : '발행하기'}</button></div>
    </dialog>
  </section>
}
