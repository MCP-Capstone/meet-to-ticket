import { useEffect, useState } from 'react'
import { api } from '../api'
import type { DispatchResult as Result, DispatchTicket, Meeting, PublishTarget, TicketStatus } from '../api'
import { IssueMarkdown } from './IssueMarkdown'
import { Spinner } from './Spinner'

// 회의록 처리가 끝난 뒤 업로더가 보는 전송 결과 (UI 플로우 6쪽, TASK-023·024·025).
// 티켓별 도구, 받은 사람, 상태와 GitHub 이슈 미리보기(읽기 전용), 담당 지정 필요 티켓의 대표 담당 지정.

interface Props {
  taskId: string
  meeting: Meeting
  onUploadAnother: () => void
}

const toolLabel: Record<PublishTarget, string> = { GITHUB: 'GitHub 이슈', SLACK: 'Slack', NOTION: 'Notion' }

const statusLabel: Record<TicketStatus, string> = {
  ASSIGNEE_REQUIRED: '담당 지정 필요',
  PENDING_REVIEW: '검토 대기',
  CONFIRMED: '확인',
  PUBLISHING: '발행 중',
  REJECTED: '거절',
  PUBLISHED: '발행됨',
  LINKED: '연결됨',
  UPDATED: '갱신됨',
  FAILED: '실패',
}

const statusText = ({ ticket }: DispatchTicket) =>
  ticket.processingType === 'LINK' && ticket.dedupe.issue && ticket.status === 'PENDING_REVIEW'
    ? `#${ticket.dedupe.issue.number}에 연결`
    : statusLabel[ticket.status]

const recipient = (t: DispatchTicket) => (t.mine ? '나' : (t.ticket.assignee?.name ?? '미지정'))

const dueText = (due: DispatchTicket['ticket']['due']) =>
  due.date ? `${due.date}${due.time ? ` ${due.time}` : ''}` : (due.text ?? '없음')

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

export function DispatchResult({ taskId, meeting, onUploadAnother }: Props) {
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api.getDispatchResult(taskId)
      .then((r) => {
        if (cancelled) return
        setResult(r)
        // 처음에는 첫 GitHub 티켓의 미리보기를 보여준다
        setSelected(r.tickets.find((t) => t.preview)?.ticket.ticketId ?? null)
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
  const preview = result.tickets.find((t) => t.ticket.ticketId === selected && t.preview)

  return (
    <section className="dispatch">
      <div className="card summary">
        <div>
          <p className="muted">{meeting.title}</p>
          <h2>티켓 {counts.total - counts.assigneeRequired}건을 담당자에게 보냈어요</h2>
          <p className="muted">
            검토 대기 {counts.pendingReview} · 기존 이슈 연결 {counts.linked} · 담당 지정 필요 {counts.assigneeRequired}
          </p>
        </div>
        <button type="button" className="link" onClick={onUploadAnother}>다른 회의록 업로드</button>
      </div>

      {waiting.map((t) => (
        <AssignNotice key={t.ticket.ticketId} item={t} result={result} onAssigned={replace} />
      ))}

      <div className="dispatch-body">
        <div className="card">
          {result.tickets.length === 0 ? (
            <p className="muted empty">회의록에서 만든 티켓이 없습니다.</p>
          ) : (
            <table className="dispatch-table">
              <thead>
                <tr><th>도구</th><th>티켓</th><th>받은 사람</th><th>상태</th></tr>
              </thead>
              <tbody>
                {result.tickets.map((t) => (
                  <tr key={t.ticket.ticketId} aria-selected={t.ticket.ticketId === selected}
                    className={t.preview ? 'selectable' : undefined}
                    onClick={() => t.preview && setSelected(t.ticket.ticketId)}>
                    <td><span className={`tool ${t.ticket.publishTarget.toLowerCase()}`}>{toolLabel[t.ticket.publishTarget]}</span></td>
                    <td>
                      {t.preview ? (
                        <button type="button" className="link" onClick={() => setSelected(t.ticket.ticketId)}>{t.ticket.title}</button>
                      ) : t.ticket.title}
                    </td>
                    <td>{recipient(t)}</td>
                    <td><span className={`badge ticket-${t.ticket.status.toLowerCase()}`}>{statusText(t)}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {preview?.preview && (
          <aside className="card issue-preview" aria-label="GitHub 이슈 미리보기">
            <p className="muted">GitHub 이슈 미리보기 · 읽기 전용 · 수정은 받은 사람이 내 티켓에서 해요</p>
            <h3>{preview.ticket.title}</h3>
            <div className="meta">
              <span>담당 {preview.ticket.assignee?.name ?? '미지정'}</span>
              <span>기한 {dueText(preview.ticket.due)}</span>
              {preview.preview.labels.length > 0 && <span>라벨 {preview.preview.labels.join(', ')}</span>}
              {preview.ticket.dedupe.issue && (
                <a href={preview.ticket.dedupe.issue.url} target="_blank" rel="noreferrer">관련 #{preview.ticket.dedupe.issue.number}</a>
              )}
            </div>
            {preview.preview.body ? <IssueMarkdown body={preview.preview.body} /> : <p className="muted">본문이 없습니다.</p>}
            {preview.ticket.dedupe.judged && preview.ticket.dedupe.reason && (
              <div className="dedupe-note">
                <h4>중복 검수</h4>
                <p>{preview.ticket.dedupe.reason}</p>
              </div>
            )}
          </aside>
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
          <label>
            대표 담당
            <select value={memberId} disabled={sending} onChange={(e) => setMemberId(e.target.value === '' ? '' : Number(e.target.value))}>
              <option value="">선택</option>
              {result.members.map((m) => <option key={m.memberId} value={m.memberId}>{m.name}</option>)}
            </select>
          </label>
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
