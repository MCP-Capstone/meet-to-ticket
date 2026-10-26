import { useEffect, useState } from 'react'
import { api } from '../api'
import type { DispatchResult as Result, DispatchTicket, Meeting, PublishTarget } from '../api'
import { ticketStatusLabel } from '../displayText'
import { IssueMarkdown } from './IssueMarkdown'
import { Spinner } from './Spinner'

// 회의록 처리가 끝난 뒤 업로더가 보는 전송 결과 (UI 플로우 6쪽, TASK-023·024·025).
// 티켓 목록의 행을 펼치면 이슈 초안(회의록 근거 포함)을 읽기 전용으로 보여 준다. 담당 지정 필요 티켓은 대표 담당을 지정한다.

interface Props {
  taskId: string
  meeting: Meeting
  onUploadAnother: () => void
}

const toolLabel: Record<PublishTarget, string> = { GITHUB: 'GitHub 이슈', SLACK: 'Slack', NOTION: 'Notion' }


const statusText = ({ ticket }: DispatchTicket) =>
  ticket.processingType === 'LINK' && ticket.dedupe.issue && ticket.status === 'PENDING_REVIEW'
    ? `#${ticket.dedupe.issue.number}에 연결`
    : ticketStatusLabel[ticket.status]

const recipient = (t: DispatchTicket) => (t.mine ? '나' : (t.ticket.assignee?.name ?? '미지정'))

const dueText = (due: DispatchTicket['ticket']['due']) =>
  due.date ? `${due.date}${due.time ? ` ${due.time}` : ''}` : (due.text ?? '없음')

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

export function DispatchResult({ taskId, meeting, onUploadAnother }: Props) {
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Set<string>>(new Set())

  useEffect(() => {
    let cancelled = false
    api.getDispatchResult(taskId)
      .then((r) => {
        if (cancelled) return
        setResult(r)
        // 처음에는 첫 티켓만 펼쳐 둔다
        setOpen(new Set(r.tickets.slice(0, 1).map((t) => t.ticket.ticketId)))
      })
      .catch((e: unknown) => { if (!cancelled) setError(toMessage(e)) })
    return () => { cancelled = true }
  }, [taskId])

  if (error) return <p className="error" role="alert">{error}</p>
  if (!result) return <Spinner label="전송 결과를 불러오는 중" />

  const replace = (updated: DispatchTicket) => {
    setResult((r) => {
      if (!r) return r
      const tickets = r.tickets.map((t) => (t.ticket.ticketId === updated.ticket.ticketId ? updated : t))
      const counts = {
        total: tickets.length,
        pendingReview: tickets.filter((t) => t.ticket.processingType !== 'LINK' && t.ticket.status === 'PENDING_REVIEW').length,
        linked: tickets.filter((t) => t.ticket.processingType === 'LINK').length,
        assigneeRequired: tickets.filter((t) => t.ticket.status === 'ASSIGNEE_REQUIRED').length,
      }
      return { ...r, tickets, counts }
    })
  }

  const { counts } = result
  const waiting = result.tickets.filter((t) => t.ticket.status === 'ASSIGNEE_REQUIRED')
  const toggle = (id: string) => setOpen((current) => {
    const next = new Set(current)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })

  return (
    <section className="dispatch">
      <div className="card summary">
        <div>
          <p className="muted dispatch-meeting">{meeting.title}</p>
          <h2>티켓 {counts.total - counts.assigneeRequired}건을 담당자에게 보냈어요</h2>
          {/* 0건인 상태는 빼고, 할 일이 남은 상태부터 상태 배지 톤으로 보여 준다. */}
          <div className="dispatch-counts">
            {counts.assigneeRequired > 0 && <span className="badge ticket-assignee_required">담당 지정 필요 {counts.assigneeRequired}</span>}
            {counts.pendingReview > 0 && <span className="badge ticket-pending_review">검토 대기 {counts.pendingReview}</span>}
            {counts.linked > 0 && <span className="badge ticket-linked">기존 이슈 연결 {counts.linked}</span>}
          </div>
        </div>
        <button type="button" className="link" onClick={onUploadAnother}>다른 회의록 업로드</button>
      </div>

      {waiting.map((t) => (
        <AssignNotice key={t.ticket.ticketId} item={t} result={result} onAssigned={replace} />
      ))}

      <div className="card dispatch-list">
        {result.tickets.length === 0 ? (
          <p className="muted empty">회의록에서 만든 티켓이 없습니다.</p>
        ) : (
          <ul>
            {result.tickets.map((t) => {
              const id = t.ticket.ticketId
              const expanded = open.has(id)
              // 미리보기가 없으면 저장된 본문을 그린다. Slack 공지는 Slack 서식으로 그린다.
              const body = t.preview?.body ?? t.ticket.description
              const flavor = t.ticket.publishTarget === 'SLACK' ? 'slack' : 'markdown'
              return (
                <li key={id} className={`dispatch-item${expanded ? ' open' : ''}`}>
                  <button type="button" className="dispatch-item-header" aria-expanded={expanded} aria-controls={`dispatch-${id}`} onClick={() => toggle(id)}>
                    <span className="dispatch-chevron" aria-hidden="true">›</span>
                    <span className={`tool ${t.ticket.publishTarget.toLowerCase()}`}>{toolLabel[t.ticket.publishTarget]}</span>
                    <strong>{t.ticket.title}</strong>
                    <span className="dispatch-recipient">{recipient(t)}</span>
                    <span className={`badge ticket-${t.ticket.status.toLowerCase()}`}>{statusText(t)}</span>
                  </button>
                  {expanded && (
                    <div id={`dispatch-${id}`} className="dispatch-item-body">
                      <div className="meta">
                        <span>담당 {t.ticket.assignee?.name ?? '미지정'}</span>
                        <span>기한 {dueText(t.ticket.due)}</span>
                        {t.preview && t.preview.labels.length > 0 && <span>라벨 {t.preview.labels.join(', ')}</span>}
                      </div>
                      {body ? <IssueMarkdown body={body} flavor={flavor} /> : <p className="muted">본문이 없습니다.</p>}
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </section>
  )
}

// 담당자가 여러 명이거나 정해지지 않아 아직 보내지 못한 티켓 (TASK-025)
function AssignNotice({ item, result, onAssigned }: { item: DispatchTicket; result: Result; onAssigned: (t: DispatchTicket) => void }) {
  const [memberId, setMemberId] = useState<number | ''>('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const send = async () => {
    if (memberId === '') return
    setSending(true)
    setError(null)
    try {
      onAssigned(await api.assignTicket(item.ticket.ticketId, memberId))
    } catch (e) {
      setError(toMessage(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="card assign-notice">
      <p>{item.ticket.title}은(는) 담당자가 여러 명이거나 정해지지 않아 아직 보내지 못했어요.</p>
      {result.canAssign ? (
        <div className="assign-row">
          <select aria-label="대표 담당" value={memberId} disabled={sending} onChange={(e) => setMemberId(e.target.value === '' ? '' : Number(e.target.value))}>
            <option value="" disabled>대표 담당 선택</option>
            {result.members.map((m) => <option key={m.memberId} value={m.memberId}>{m.name}</option>)}
          </select>
          <button type="button" className="primary" disabled={memberId === '' || sending} onClick={send}>
            {sending ? '보내는 중…' : '지정하고 보내기'}
          </button>
        </div>
      ) : (
        <p className="muted">회의록을 올린 사람이 대표 담당을 지정할 수 있어요.</p>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  )
}
