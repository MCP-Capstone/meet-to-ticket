import { useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, ProjectMember, TicketAction, TicketEditRequest, TicketStatus } from '../api'
import { validMeetingDate } from '../meetingInput'
import { failureGuide, needsPermissionFix } from '../publishFailure'

const ticketStatusLabel: Record<TicketStatus, string> = {
  ASSIGNEE_REQUIRED: '담당 지정 필요', PENDING_REVIEW: '검토 대기', CONFIRMED: '확인', REJECTED: '거절',
  PUBLISHING: '발행 중', PUBLISHED: '발행됨', LINKED: '연결됨', UPDATED: '갱신됨', FAILED: '실패',
}
const actions: Partial<Record<TicketStatus, { action: TicketAction; label: string }[]>> = {
  PENDING_REVIEW: [{ action: 'confirm', label: '확인' }, { action: 'reject', label: '거절' }],
  CONFIRMED: [{ action: 'reopen', label: '다시 열기' }],
  REJECTED: [{ action: 'restore', label: '되돌리기' }],
  FAILED: [{ action: 'revise', label: '수정' }],
}
interface Props {
  ticket: MyTicket
  busy: boolean
  onCommit: (ticket: MyTicket, run: () => Promise<MyTicket>, transferred: boolean) => Promise<void>
  onRefresh: () => Promise<boolean>
  onDirtyChange: (id: string, dirty: boolean) => void
}

export function TicketCard({ ticket, busy, onCommit, onRefresh, onDirtyChange }: Props) {
  const [editing, setEditing] = useState(false)
  const [description, setDescription] = useState('')
  const [assignee, setAssignee] = useState('')
  const [dueText, setDueText] = useState('')
  const [dueDate, setDueDate] = useState('')
  const [dueTime, setDueTime] = useState('')
  const [members, setMembers] = useState<ProjectMember[]>([])
  const [membersLoading, setMembersLoading] = useState(false)
  const [membersError, setMembersError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [blocked, setBlocked] = useState(false)
  const [pending, setPending] = useState(false)
  const [checked, setChecked] = useState(false)
  const operation = useRef(false)
  const memberRequest = useRef(0)
  useEffect(() => () => { ++memberRequest.current }, [])

  const descriptionChanged = description !== (ticket.description ?? '')
  const assigneeChanged = assignee !== String(ticket.assignee?.projectMemberId ?? '')
  const dueChanged = dueText !== (ticket.due.text ?? '') || dueDate !== (ticket.due.date ?? '') || dueTime !== (ticket.due.time ?? '')
  const dirty = editing && (descriptionChanged || assigneeChanged || dueChanged)
  useEffect(() => {
    onDirtyChange(ticket.ticketId, dirty)
    return () => onDirtyChange(ticket.ticketId, false)
  }, [ticket.ticketId, dirty, onDirtyChange])

  const loadMembers = async () => {
    const version = ++memberRequest.current
    setMembersLoading(true)
    setMembersError(null)
    try {
      const result = await api.listMembers(ticket.projectId)
      if (version === memberRequest.current) setMembers(result.members.filter((member) => member.userId !== null))
    } catch (e) {
      if (version === memberRequest.current && !(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) {
        setMembersError('담당자 목록을 불러오지 못했습니다. 본문과 기한은 계속 수정할 수 있습니다.')
      }
    } finally {
      if (version === memberRequest.current) setMembersLoading(false)
    }
  }
  const startEditing = () => {
    setDescription(ticket.description ?? '')
    setAssignee(String(ticket.assignee?.projectMemberId ?? ''))
    setDueText(ticket.due.text ?? '')
    setDueDate(ticket.due.date ?? '')
    setDueTime(ticket.due.time ?? '')
    setError(null)
    setEditing(true)
    void loadMembers()
  }
  const perform = async (run: () => Promise<MyTicket>, transferred = false) => {
    if (operation.current || busy || blocked) return
    operation.current = true
    setPending(true)
    setError(null)
    try {
      await onCommit(ticket, run, transferred)
      setEditing(false)
    } catch (e) {
      if (e instanceof ApiRequestError && e.code === 'UNAUTHORIZED') return
      if (e instanceof ApiRequestError && [403, 404, 409].includes(e.status)) {
        setBlocked(true)
        setError(`${e.message} 최신 목록을 불러와 담당자와 상태를 확인해 주세요. 입력은 아직 저장되지 않았습니다.`)
      } else {
        setError(e instanceof Error ? e.message : '요청에 실패했습니다. 다시 시도해 주세요.')
      }
    } finally {
      operation.current = false
      setPending(false)
    }
  }
  const save = () => {
    const body: TicketEditRequest = {}
    if (descriptionChanged) {
      if (!description.trim() || description.length > 4000) { setError('본문은 공백만 입력할 수 없으며 4,000자 이하여야 합니다.'); return }
      body.description = description
    }
    if (assigneeChanged) {
      if (!members.some((member) => member.projectMemberId === Number(assignee))) { setError('프로젝트의 담당자를 선택해 주세요.'); return }
      body.assigneeMemberId = Number(assignee)
    }
    if (dueChanged) {
      if (dueText.length > 200 || (dueDate && !validMeetingDate(dueDate)) || (dueTime && !/^([01]\d|2[0-3]):[0-5]\d$/.test(dueTime))) {
        setError('기한 문구는 200자 이하, 날짜와 시각은 올바른 형식으로 입력해 주세요.'); return
      }
      body.due = { text: dueText.trim() || null, date: dueDate || null, time: dueTime || null }
    }
    if (!Object.keys(body).length) return
    void perform(() => api.editTicket(ticket.ticketId, body), assigneeChanged)
  }
  const refresh = async () => {
    if (dirty && !window.confirm('저장하지 않은 수정을 버리고 최신 내용을 불러올까요?')) return
    if (await onRefresh()) { setEditing(false); setBlocked(false); setError(null) }
  }

  return (
    <article className="card ticket-card" aria-labelledby={`ticket-${ticket.ticketId}`}>
      <div className="card-header">
        <div>
          <p className="muted">{ticket.projectName} · {ticket.meetingTitle}</p>
          <h3 id={`ticket-${ticket.ticketId}`}>{ticket.title}</h3>
        </div>
        <span className={`badge ticket-status ${ticket.status.toLowerCase()}`}>{ticketStatusLabel[ticket.status] ?? ticket.status}</span>
      </div>
      <p className="meta">{({ GITHUB: 'GitHub', SLACK: 'Slack', NOTION: 'Notion' })[ticket.publishTarget]} · {({ CREATE: '신규 발행', LINK: '기존 이슈 연결', UPDATE: '기존 이슈 갱신' })[ticket.processingType]}</p>
      {editing ? (
        <fieldset className="ticket-fields" disabled={busy || pending || blocked}>
          <legend className="sr-only">티켓 수정</legend>
          <label>본문<textarea aria-label="본문" rows={6} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={4000} /></label>
          <label>담당자
            <select aria-label="담당자" value={assignee} onChange={(e) => setAssignee(e.target.value)} disabled={membersLoading || Boolean(membersError)}>
              {!members.some((member) => String(member.projectMemberId) === assignee) && <option value={assignee}>{ticket.assignee?.name ?? '미지정'}</option>}
              {members.map((member) => <option key={member.projectMemberId} value={member.projectMemberId}>{member.name} ({member.githubName ?? `계정 없음 · 멤버 #${member.projectMemberId}`})</option>)}
            </select>
          </label>
          {membersLoading && <p className="muted" role="status">담당자 목록을 불러오고 있습니다.</p>}
          {membersError && <div role="alert"><p className="error">{membersError}</p><button type="button" className="link" onClick={() => void loadMembers()}>담당자 목록 다시 시도</button></div>}
          {assigneeChanged && <p className="notice">저장하면 선택한 담당자의 내 티켓으로 이동하고 내 목록에서는 사라집니다.</p>}
          <label>기한 문구<input value={dueText} onChange={(e) => setDueText(e.target.value)} maxLength={200} placeholder="예: 다음 회의 전까지" /></label>
          <div className="row">
            <label>기한 날짜<input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} /></label>
            <label>기한 시각<input type="time" value={dueTime} onChange={(e) => setDueTime(e.target.value)} /></label>
          </div>
          <p className="muted">기한을 없애려면 문구·날짜·시각을 모두 비워 저장하세요.</p>
        </fieldset>
      ) : (
        <>
          <p className="ticket-body">{ticket.description || '본문이 없습니다.'}</p>
          <dl className="meeting-info">
            <dt>담당자</dt><dd>{ticket.assignee?.name ?? '미지정'}</dd>
            <dt>기한</dt><dd>{[ticket.due.text, ticket.due.date, ticket.due.time].filter(Boolean).join(' · ') || '미정'}</dd>
          </dl>
        </>
      )}
      {ticket.status === 'FAILED' && ticket.publishFailure && (
        <div className="publish-failure" role="alert">
          <p className="error">{failureGuide(ticket.publishFailure)}</p>
          {ticket.publishFailure.outcomeUnknown && <>
            <p className="notice">GitHub에 이미 반영됐을 수 있습니다. 확인 후 다시 시도하세요.</p>
            <label className="check"><input type="checkbox" checked={checked} disabled={busy || pending} onChange={(e) => setChecked(e.target.checked)} />GitHub에서 반영 여부를 확인했습니다</label>
          </>}
          {needsPermissionFix(ticket.publishFailure) && <p className="muted">저장소 권한은 프로젝트 관리자가 프로젝트 설정에서 풀 수 있습니다.</p>}
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      {blocked && <button type="button" className="link" disabled={busy} onClick={() => void refresh()}>최신 내용 불러오기</button>}
      <div className="ticket-actions">
        {editing ? <>
          <button type="button" className="primary" disabled={busy || pending || blocked || !dirty} onClick={save}>{pending ? '저장 중...' : '저장'}</button>
          <button type="button" className="secondary" disabled={busy || pending} onClick={() => {
            if (!dirty || window.confirm('저장하지 않은 수정을 취소할까요?')) { ++memberRequest.current; setEditing(false); setError(null) }
          }}>수정 취소</button>
          <p className="muted">수정을 저장하거나 취소한 뒤 확인·거절할 수 있습니다.</p>
        </> : <>
          {ticket.status === 'PENDING_REVIEW' && <button type="button" className="secondary" disabled={busy || blocked} onClick={startEditing}>수정</button>}
          {ticket.status === 'FAILED' && <>
            <button type="button" className="primary" disabled={busy || pending || blocked || Boolean(ticket.publishFailure?.outcomeUnknown && !checked)}
              onClick={() => void perform(async () => { await api.retryTicket(ticket.ticketId); return { ...ticket, status: 'PUBLISHING', publishFailure: null } })}>{pending ? '처리 중...' : '다시 시도'}</button>
          </>}
          {(actions[ticket.status] ?? []).map(({ action, label }) => <button type="button" key={action} className={action === 'confirm' ? 'primary' : 'secondary'} disabled={busy || pending || blocked} onClick={() => void perform(() => api.changeTicketStatus(ticket.ticketId, action))}>{pending ? '처리 중...' : label}</button>)}
        </>}
      </div>
    </article>
  )
}
