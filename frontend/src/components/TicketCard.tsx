import { useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, ProjectMember, TicketAction, TicketEditRequest, TicketStatus } from '../api'
import { validMeetingDate } from '../meetingInput'
import { canRetryComment, canRetryFailure, failureGuide, needsOutcomeCheck, needsPermissionFix } from '../publishFailure'
import { safePublishUrl, toolName } from '../batchPublish'
import { singleLineLabel } from '../displayText'
import { TicketDedupeDetails } from './TicketDedupeDetails'
import { TicketEvidence } from './TicketEvidence'

const ticketStatusLabel: Record<TicketStatus, string> = {
  ASSIGNEE_REQUIRED: '담당 지정 필요', PENDING_REVIEW: '검토 대기', CONFIRMED: '확인', REJECTED: '거절',
  PUBLISHING: '발행 중', PUBLISHED: '발행됨', LINKED: '연결됨', UPDATED: '갱신됨', SKIPPED: '발행 생략', FAILED: '실패',
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
  onOpenProjectSettings: (projectId: number) => void
}

export function TicketCard({ ticket, busy, onCommit, onRefresh, onDirtyChange, onOpenProjectSettings }: Props) {
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState('')
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
  // 재시도나 확인 결과로 실패 내용이 바뀌면 지난 확인 체크와 링크를 다시 쓰지 않게 비운다.
  // 입력값은 저장 당시의 failureKey와 함께 두고, 키가 다르면 빈 값으로 본다.
  const failureKey = `${ticket.status}:${ticket.publishFailure?.errorCode ?? ''}:${ticket.publishFailure?.resolution ?? ''}`
  const [outcomeInput, setOutcomeInput] = useState({ key: failureKey, checked: false, url: '' })
  const current = outcomeInput.key === failureKey ? outcomeInput : { key: failureKey, checked: false, url: '' }
  const checked = current.checked
  const outcomeUrl = current.url
  const setChecked = (value: boolean) => setOutcomeInput({ ...current, checked: value })
  const setOutcomeUrl = (value: string) => setOutcomeInput({ ...current, url: value })
  // 다시 시도나 확인 결과를 보낼 때 비운다. 같은 오류 코드로 다시 실패해도 지난 체크가 되살아나지 않는다.
  const clearOutcomeInput = () => setOutcomeInput({ key: '', checked: false, url: '' })
  const checkUrl = ticket.publishFailure ? safePublishUrl(ticket.publishFailure.checkUrl, ticket.publishTarget) : null
  const resultUrl = safePublishUrl(ticket.githubIssueUrl ?? null, ticket.publishTarget)
  const typedOutcomeUrl = outcomeUrl.trim()
  // 서버로는 정리된 링크(기본 포트 제거 등)를 보낸다. 서버도 2048자를 넘는 링크는 받지 않는다.
  const normalizedOutcomeUrl = typedOutcomeUrl === '' ? null : safePublishUrl(typedOutcomeUrl, ticket.publishTarget)
  const outcomeUrlInvalid = typedOutcomeUrl !== '' && (!normalizedOutcomeUrl || normalizedOutcomeUrl.length > 2048)
  const operation = useRef(false)
  const memberRequest = useRef(0)
  useEffect(() => () => { ++memberRequest.current }, [])

  const titleChanged = title !== ticket.title
  const descriptionChanged = description !== (ticket.description ?? '')
  const assigneeChanged = assignee !== String(ticket.assignee?.projectMemberId ?? '')
  const dueChanged = dueText !== (ticket.due.text ?? '') || dueDate !== (ticket.due.date ?? '') || dueTime !== (ticket.due.time ?? '')
  const dirty = editing && (titleChanged || descriptionChanged || assigneeChanged || dueChanged)
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
    setTitle(ticket.title)
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
      // 기존 이슈가 그사이 닫히는 등 카드의 중복 판단 정보가 오래된 경우도 다시 불러와야 풀린다.
      if (e instanceof ApiRequestError && ([403, 404, 409].includes(e.status) || ['EXISTING_ISSUE_REQUIRED', 'PROCESSING_TYPE_TARGET_MISMATCH'].includes(e.code))) {
        setBlocked(true)
        setError(`${e.message} 최신 목록을 불러와 담당자, 상태, 기존 이슈를 확인해 주세요. 입력은 아직 저장되지 않았습니다.`)
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
    if (titleChanged) {
      if (!title.trim() || title.length > 255) { setError('제목은 공백만 입력할 수 없으며 255자 이하여야 합니다.'); return }
      body.title = title
    }
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
          <p className="muted">{ticket.projectName} · {singleLineLabel(ticket.meetingTitle)}</p>
          <h3 id={`ticket-${ticket.ticketId}`}>{singleLineLabel(ticket.title, '제목 없음')}</h3>
        </div>
        <span className={`badge ticket-status ${ticket.status.toLowerCase()}`}>{ticketStatusLabel[ticket.status] ?? ticket.status}</span>
      </div>
      <p className="meta">{({ GITHUB: 'GitHub', SLACK: 'Slack', NOTION: 'Notion' })[ticket.publishTarget]} · {({ CREATE: '신규 발행', LINK: '기존 이슈 연결', UPDATE: '기존 이슈 갱신' })[ticket.processingType]}</p>
      {resultUrl && <a href={resultUrl} target="_blank" rel="noreferrer">{toolName[ticket.publishTarget]}에서 발행 결과 보기 ↗</a>}
      <TicketDedupeDetails ticket={ticket} />
      {editing ? (
        <fieldset className="ticket-fields" disabled={busy || pending || blocked}>
          <legend className="sr-only">티켓 수정</legend>
          <label>제목<input aria-label="제목" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} /></label>
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
          <TicketEvidence ticketId={ticket.ticketId} />
          <dl className="meeting-info">
            <dt>담당자</dt><dd>{ticket.assignee?.name ?? '미지정'}</dd>
            <dt>기한</dt><dd>{[ticket.due.text, ticket.due.date, ticket.due.time].filter(Boolean).join(' · ') || '미정'}</dd>
          </dl>
        </>
      )}
      {(ticket.status === 'FAILED' || ticket.status === 'UPDATED') && ticket.publishFailure && (
        <div className="publish-failure" role="alert">
          <p className="error">{failureGuide(ticket.publishFailure)}</p>
          {ticket.status === 'UPDATED' && !canRetryComment(ticket) && <p className="notice">이슈 본문 갱신은 끝났습니다. 다시 보내면 알림 댓글이 중복될 수 있어 자동으로 보내지 않습니다. GitHub에서 알림 댓글이 있는지 확인하고, 없으면 직접 남겨 주세요.</p>}
          {ticket.status === 'FAILED' && !ticket.publishFailure.retryable && !needsOutcomeCheck(ticket.publishFailure) && <p className="notice">이 오류는 바로 재시도할 수 없습니다. 오류 원인이나 프로젝트 설정을 해결한 뒤 ‘수정’으로 돌아가 내용을 확인하고 다시 승인해 주세요.</p>}
          {needsOutcomeCheck(ticket.publishFailure) ? <>
            <p className="notice">{toolName[ticket.publishTarget]}에 이미 반영됐을 수 있는데, 반영 여부를 자동으로 확인할 수 없어 다시 시도하면 중복될 수 있습니다. {toolName[ticket.publishTarget]}에서 확인한 결과를 알려 주세요. 반영되지 않았다면 다시 시도할 수 있게 됩니다.</p>
            {checkUrl && <a href={checkUrl} target="_blank" rel="noreferrer">{toolName[ticket.publishTarget]}에서 확인하기</a>}
            <label>반영된 링크 (이미 반영됐을 때, 선택)
              <input type="url" value={outcomeUrl} placeholder="https://" aria-invalid={outcomeUrlInvalid} disabled={busy || pending || blocked} onChange={(e) => setOutcomeUrl(e.target.value)} />
            </label>
            {outcomeUrlInvalid && <p className="error">{toolName[ticket.publishTarget]} 링크(https)만 넣을 수 있습니다.</p>}
            <div className="ticket-actions">
              <button type="button" className="secondary" disabled={busy || pending || blocked || outcomeUrlInvalid} onClick={() => {
                if (window.confirm(`${toolName[ticket.publishTarget]}에 이미 반영된 것을 확인했나요? 발행됨으로 끝내며 되돌릴 수 없습니다.`))
                  void perform(async () => { const saved = await api.resolveTicketOutcome(ticket.ticketId, { applied: true, url: normalizedOutcomeUrl }); clearOutcomeInput(); return saved })
              }}>이미 반영됨</button>
              <button type="button" className="secondary" disabled={busy || pending || blocked} onClick={() => {
                if (window.confirm(`${toolName[ticket.publishTarget]}에 반영되지 않은 것을 확인했나요? 다시 시도하면 새로 보내므로, 이미 반영된 경우 중복됩니다.`))
                  void perform(async () => { const saved = await api.resolveTicketOutcome(ticket.ticketId, { applied: false }); clearOutcomeInput(); return saved })
              }}>반영되지 않음</button>
            </div>
            <p className="muted">내용을 고치려면 먼저 확인 결과를 남겨 주세요. 반영되지 않음을 고르면 수정할 수 있습니다.</p>
          </> : ticket.publishFailure.outcomeUnknown && <>
            <p className="notice">{toolName[ticket.publishTarget]}에 이미 반영됐을 수 있습니다. 확인 후 다시 시도하세요. 다시 시도하면 이미 만든 이슈가 있는지 먼저 확인합니다.</p>
            {checkUrl && <a href={checkUrl} target="_blank" rel="noreferrer">{toolName[ticket.publishTarget]}에서 확인하기</a>}
            <label className="check"><input type="checkbox" checked={checked} disabled={busy || pending} onChange={(e) => setChecked(e.target.checked)} />{toolName[ticket.publishTarget]}에서 반영 여부를 확인했습니다</label>
          </>}
          {needsPermissionFix(ticket.publishFailure) && <>
            <p className="muted">저장소 권한은 프로젝트 관리자가 프로젝트 설정에서 확인해야 합니다. 현재 권한을 확인한 뒤 설정으로 이동합니다.</p>
            <button type="button" className="secondary" disabled={busy || pending} onClick={() => onOpenProjectSettings(ticket.projectId)}>프로젝트 설정 확인</button>
          </>}
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
          {ticket.status === 'PENDING_REVIEW' && <>
            <button type="button" className="secondary" disabled={busy || blocked} onClick={startEditing}>수정</button>
          </>}
          {ticket.status === 'FAILED' && !(ticket.publishFailure && needsOutcomeCheck(ticket.publishFailure)) && <>
            <button type="button" className="primary" disabled={busy || pending || blocked || !canRetryFailure(ticket.publishFailure) || Boolean(ticket.publishFailure?.outcomeUnknown && !checked)}
              onClick={() => void perform(async () => { await api.retryTicket(ticket.ticketId); clearOutcomeInput(); return { ...ticket, status: 'PUBLISHING', publishFailure: null } })}>{pending ? '처리 중...' : '다시 시도'}</button>
          </>}
          {canRetryComment(ticket) && <button type="button" className="primary" disabled={busy || pending || blocked}
            onClick={() => void perform(async () => { await api.retryTicketComment(ticket.ticketId); return { ...ticket, status: 'PUBLISHING', publishFailure: null } })}>{pending ? '처리 중...' : '알림 댓글 다시 보내기'}</button>}
          {(actions[ticket.status] ?? []).filter(({ action }) => !(action === 'revise' && ticket.publishFailure && needsOutcomeCheck(ticket.publishFailure))).map(({ action, label }) => <button type="button" key={action} className={action === 'confirm' ? 'primary' : 'secondary'} disabled={busy || pending || blocked} onClick={() => void perform(() => api.changeTicketStatus(ticket.ticketId, action))}>{pending ? '처리 중...' : label}</button>)}
        </>}
      </div>
    </article>
  )
}
