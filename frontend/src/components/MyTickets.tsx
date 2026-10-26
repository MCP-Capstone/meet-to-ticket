import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, MyTicketList, PublishBatchResult } from '../api'
import { Spinner } from './Spinner'
import { Toast } from './Toast'
import { TicketCard } from './TicketCard'
import { BatchPublishFlow } from './BatchPublishFlow'
import { NotificationStack } from './Notification'
import type { NotificationItem } from './Notification'
import { publishNotice } from '../hooks/usePublishBatch'
import { registeredAt, singleLineLabel, ticketStatusLabel } from '../displayText'
import { safePublishUrl } from '../batchPublish'

interface Props {
  onBusyChange: (busy: boolean) => void
  onDirtyChange: (dirty: boolean) => void
  onOpenProjectSettings: (projectId: number) => void
}
const unauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'
const countKey = (status: MyTicket['status']) => status === 'PENDING_REVIEW' ? 'pendingReview' : status === 'CONFIRMED' ? 'confirmed' : status === 'REJECTED' ? 'rejected' : status === 'FAILED' ? 'failed' : null
type TicketView = 'active' | 'done'
const targetLabel: Record<MyTicket['publishTarget'], string> = { GITHUB: 'GitHub 이슈', NOTION: 'Notion', SLACK: 'Slack' }
const linkLabel: Record<MyTicket['publishTarget'], string> = { GITHUB: '이슈', NOTION: '페이지', SLACK: '메시지' }

// 사용자가 직접 처리해야 하는 티켓. 진행 중 회의에서 펼쳐 둔다.
const needsAction = (ticket: MyTicket) => ticket.status === 'PENDING_REVIEW' || ticket.status === 'ASSIGNEE_REQUIRED' || ticket.status === 'FAILED'
  || (ticket.status === 'UPDATED' && Boolean(ticket.publishFailure))
// 아직 발행하지 않았거나 발행 문제를 확인해야 하는 회의는 진행 중, 나머지는 발행 완료로 본다.
const activeMeeting = (tickets: MyTicket[]) => tickets.some((ticket) => needsAction(ticket) || ticket.status === 'CONFIRMED' || ticket.status === 'PUBLISHING')
const meetingKey = (ticket: MyTicket) => `${ticket.projectId}:${ticket.meetingId}`
const latest = (tickets: MyTicket[]) => tickets.reduce((max, ticket) => ticket.createdAt > max ? ticket.createdAt : max, '')

function groupMeetings(tickets: MyTicket[]) {
  const groups = new Map<string, MyTicket[]>()
  for (const ticket of tickets) groups.set(meetingKey(ticket), [...(groups.get(meetingKey(ticket)) ?? []), ticket])
  return [...groups.entries()].map(([key, items]) => ({ key, tickets: items, active: activeMeeting(items) }))
    // 확인·거절할 때마다 회의 순서가 바뀌지 않게 최근 등록한 회의부터 고정된 순서로 둔다.
    .sort((x, y) => latest(y.tickets).localeCompare(latest(x.tickets)))
}
function projectsOf(tickets: MyTicket[]) {
  const projects = new Map<number, { projectId: number; name: string; todo: number }>()
  for (const ticket of tickets) {
    const project = projects.get(ticket.projectId) ?? { projectId: ticket.projectId, name: ticket.projectName, todo: 0 }
    if (needsAction(ticket)) project.todo += 1
    projects.set(ticket.projectId, project)
  }
  return [...projects.values()]
}
// 발행 완료 회의 요약은 사용자가 구분할 필요 없는 발행됨·연결됨·갱신됨을 '발행' 하나로 묶는다.
const summaryKinds = [
  { label: '발행', tone: 'published', match: (t: MyTicket) => t.status === 'PUBLISHED' || t.status === 'LINKED' || t.status === 'UPDATED' },
  { label: '생략', tone: 'skipped', match: (t: MyTicket) => t.status === 'SKIPPED' },
  { label: '거절', tone: 'rejected', match: (t: MyTicket) => t.status === 'REJECTED' },
]
const statusSummary = (tickets: MyTicket[]) => summaryKinds.map((kind) => ({ ...kind, count: tickets.filter(kind.match).length })).filter((kind) => kind.count > 0)

export function MyTickets({ onBusyChange, onDirtyChange, onOpenProjectSettings }: Props) {
  const [data, setData] = useState<MyTicketList | null>(null)
  const [projectId, setProjectId] = useState<number | null>(null)
  const [view, setView] = useState<TicketView>('active')
  // 처리할 티켓은 기본으로 펼치고, 사용자가 접은 것만 기억한다. 끝낸 티켓은 기본으로 접고, 펼친 것만 기억한다.
  const [openTickets, setOpenTickets] = useState<Set<string>>(new Set())
  const [closedTickets, setClosedTickets] = useState<Set<string>>(new Set())
  const [openMeetings, setOpenMeetings] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(true)
  const [mutating, setMutating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [notifications, setNotifications] = useState<NotificationItem[]>([])
  const notificationId = useRef(0)
  // 발행 결과를 기다리는 회의. 목록 새로고침이 먼저 끝나도 결과가 올 때까지 진행 중에 두어 결과 조회가 끊기지 않게 한다.
  const [awaiting, setAwaiting] = useState<Set<string>>(new Set())
  const [settingsFeedback, setSettingsFeedback] = useState<{ message: string; error: boolean; pending?: boolean } | null>(null)
  const settingsMessage = useRef<HTMLParagraphElement>(null)
  useEffect(() => {
    if (settingsFeedback && !settingsFeedback.pending) {
      settingsMessage.current?.focus({ preventScroll: true })
      settingsMessage.current?.scrollIntoView({ block: 'nearest' })
    }
  }, [settingsFeedback])
  const lifecycle = useRef({ mounted: true, version: 0 })
  const mutationLock = useRef(false)
  const dirtyIds = useRef(new Set<string>())
  const [hasDirty, setHasDirty] = useState(false)
  const dirtyChange = useCallback((id: string, dirty: boolean) => {
    if (dirty) dirtyIds.current.add(id)
    else dirtyIds.current.delete(id)
    setHasDirty(dirtyIds.current.size > 0)
    onDirtyChange(dirtyIds.current.size > 0)
  }, [onDirtyChange])
  useEffect(() => {
    const life = lifecycle.current
    life.mounted = true
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (dirtyIds.current.size) { event.preventDefault(); event.returnValue = '' }
    }
    window.addEventListener('beforeunload', beforeUnload)
    return () => {
      life.mounted = false
      ++life.version
      onBusyChange(false)
      onDirtyChange(false)
      window.removeEventListener('beforeunload', beforeUnload)
    }
  }, [onBusyChange, onDirtyChange])

  const load = useCallback(async () => {
    const version = ++lifecycle.current.version
    setLoading(true)
    setError(null)
    try {
      // 내 티켓은 프로젝트를 먼저 고르는 화면이 아니라, 현재 사용자에게 할당된 일을 처리하는 인박스다.
      // 프로젝트·회의는 목록 내부의 그룹으로 보여 주고, 서버에는 전체 목록만 요청한다.
      const result = await api.listMyTickets()
      if (!lifecycle.current.mounted || version !== lifecycle.current.version) return false
      setData(result)
      return true
    } catch (e) {
      if (lifecycle.current.mounted && version === lifecycle.current.version && !unauthorized(e)) {
        setError(e instanceof Error ? e.message : '내 티켓을 불러오지 못했습니다.')
      }
      return false
    } finally {
      if (lifecycle.current.mounted && version === lifecycle.current.version) setLoading(false)
    }
  }, [])
  useEffect(() => {
    let cancelled = false
    const life = lifecycle.current
    // StrictMode 재마운트 때 취소된 조회는 시작하지 않는다.
    void Promise.resolve().then(() => { if (!cancelled) void load() })
    return () => { cancelled = true; ++life.version }
  }, [load])

  const commit = async (previous: MyTicket, run: () => Promise<MyTicket>, transferred: boolean) => {
    if (mutationLock.current) throw new Error('다른 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.')
    mutationLock.current = true
    setMutating(true)
    onBusyChange(true)
    setNotice(null)
    setSettingsFeedback(null)
    try {
      const updated = await run()
      if (!lifecycle.current.mounted) return
      // 승인·거절하면 그 티켓은 한 줄로 접히고, 같은 회의의 다음 검토 티켓이 펼쳐진 채 남는다.
      if (!transferred && (updated.status === 'CONFIRMED' || updated.status === 'REJECTED')) setOpenTickets((current) => { const next = new Set(current); next.delete(updated.ticketId); return next })
      // 승인·거절을 취소해 다시 검토하게 되면 접어 둔 기록을 지워 펼친다.
      if (!transferred && needsAction(updated)) setClosedTickets((current) => { const next = new Set(current); next.delete(updated.ticketId); return next })
      // 저장 성공을 먼저 반영한다. 후속 조회 실패 때문에 변경 요청을 재전송하지 않는다.
      setData((current) => {
        if (!current) return current
        const counts = { ...current.counts }
        const oldKey = countKey(previous.status)
        const newKey = countKey(updated.status)
        if (oldKey) counts[oldKey] = Math.max(0, counts[oldKey] - 1)
        if (!transferred && newKey) counts[newKey] += 1
        return { counts, tickets: current.tickets.flatMap((ticket) => ticket.ticketId !== updated.ticketId ? [ticket] : transferred ? [] : [updated]) }
      })
      // 승인·거절·수정은 목록에서 바로 바뀌어 보이므로 따로 알리지 않는다. 티켓이 사라지거나 되돌아갈 때만 알린다.
      setNotice(transferred ? '담당자를 변경했습니다. 해당 티켓은 새 담당자의 내 티켓으로 이동했습니다.' : updated.status === 'PENDING_REVIEW' && previous.status !== 'PENDING_REVIEW' ? '검토 대기로 되돌렸어요.' : null)
      const refreshed = await load()
      if (!refreshed && lifecycle.current.mounted) setNotice('티켓 변경은 저장됐습니다. 최신 목록 조회에 실패했으니 목록만 다시 불러와 주세요.')
    } finally {
      mutationLock.current = false
      if (lifecycle.current.mounted) { setMutating(false); onBusyChange(false) }
    }
  }
  // 발행 중인 티켓이 있으면 결과가 올 때까지 목록을 다시 불러온다.
  const publishing = Boolean(data?.tickets.some((ticket) => ticket.status === 'PUBLISHING'))
  useEffect(() => {
    if (!publishing || mutating) return
    const timer = window.setTimeout(() => { if (!dirtyIds.current.size) void load() }, 3000)
    return () => window.clearTimeout(timer)
  }, [publishing, mutating, data, load])
  const canDiscard = () => !dirtyIds.current.size || window.confirm('저장하지 않은 수정을 버리고 목록을 변경할까요?')
  const current = data
  const manualRefresh = async () => {
    if (canDiscard()) await load()
  }
  const toggle = (setter: typeof setOpenTickets, key: string) => setter((current) => { const next = new Set(current); if (next.has(key)) next.delete(key); else next.add(key); return next })
  const expanded = (ticket: MyTicket) => needsAction(ticket) ? !closedTickets.has(ticket.ticketId) : openTickets.has(ticket.ticketId)
  const toggleTicket = (ticket: MyTicket) => { if (canDiscard()) toggle(needsAction(ticket) ? setClosedTickets : setOpenTickets, ticket.ticketId) }
  const changeProject = (next: number) => { if (next !== activeProject?.projectId && !mutationLock.current && canDiscard()) { setProjectId(next); setView('active') } }
  const changeView = (next: TicketView) => { if (next !== view && !mutationLock.current && canDiscard()) setView(next) }
  const openSettings = async (targetProjectId: number) => {
    if (mutationLock.current || loading) return
    if (dirtyIds.current.size && !window.confirm('저장하지 않은 티켓 수정을 버리고 프로젝트 설정으로 이동할까요?')) return
    mutationLock.current = true
    setMutating(true)
    onBusyChange(true)
    setSettingsFeedback({ message: `프로젝트 #${targetProjectId} 설정 접근 권한을 확인하고 있습니다.`, error: false, pending: true })
    try {
      // 목록에 캐시된 canEdit을 믿지 않고 클릭 시점에 최신 권한을 확인한다.
      const detail = await api.getProject(targetProjectId)
      if (!lifecycle.current.mounted) return
      if (!detail.canEdit) {
        setSettingsFeedback({ message: `${detail.name}: 프로젝트 관리자만 설정을 변경할 수 있습니다. 관리자에게 GitHub 저장소 연결·토큰·접근 권한 확인을 요청해 주세요.`, error: false })
        return
      }
      onOpenProjectSettings(detail.projectId)
    } catch (e) {
      if (!lifecycle.current.mounted || unauthorized(e)) return
      const message = e instanceof ApiRequestError && e.status === 403
        ? '이 프로젝트에 접근할 권한이 없습니다. 프로젝트 관리자에게 멤버 및 접근 권한을 확인해 달라고 요청해 주세요.'
        : e instanceof ApiRequestError && e.status === 404
          ? '프로젝트를 찾을 수 없습니다. 삭제되었거나 접근 가능한 프로젝트가 아닙니다. 목록을 새로고침해 주세요.'
          : '프로젝트 정보를 불러오지 못했습니다. 연결을 확인한 뒤 프로젝트 설정 확인을 다시 눌러 주세요.'
      setSettingsFeedback({ message, error: true })
    } finally {
      mutationLock.current = false
      if (lifecycle.current.mounted) { setMutating(false); onBusyChange(false) }
    }
  }
  const projects = projectsOf(current?.tickets ?? [])
  // 처리할 일이 있는 프로젝트를 먼저 연다. 고른 프로젝트가 사라지면 첫 프로젝트로 돌아간다.
  const activeProject = projects.find((project) => project.projectId === projectId) ?? projects.find((project) => project.todo > 0) ?? projects[0] ?? null
  const meetings = groupMeetings((current?.tickets ?? []).filter((ticket) => ticket.projectId === activeProject?.projectId))
    .map((meeting) => awaiting.has(meeting.key) ? { ...meeting, active: true } : meeting)
  const meetingsRef = useRef(meetings)
  useEffect(() => { meetingsRef.current = groupMeetings(current?.tickets ?? []) })
  const visibleMeetings = meetings.filter((meeting) => meeting.active === (view === 'active'))
  const publishLock = useCallback((value: boolean) => {
    mutationLock.current = value
    setMutating(value)
    onBusyChange(value)
  }, [onBusyChange])
  const busy = mutating || loading
  const setAwaitingMeeting = (key: string, value: boolean) => setAwaiting((current) => {
    const next = new Set(current)
    if (value) next.add(key); else next.delete(key)
    return next
  })
  const dismissNotification = useCallback((id: number) => setNotifications((items) => items.filter((item) => item.id !== id)), [])
  // 발행 결과는 묶음 밖(화면 오른쪽 위)에 띄운다. 최근 3개까지만 쌓는다.
  const notifyPublished = (key: string, title: string, projectOf: number, result: PublishBatchResult) => {
    setAwaitingMeeting(key, false)
    const content = publishNotice(result, title)
    // 누르는 시점의 목록에서 회의가 어느 탭에 있는지 보고 그 탭의 회의 묶음으로 데려간다.
    // 다른 티켓에 확인할 일이 남아 있으면 회의는 진행 중에 그대로 있다.
    const action = { label: '결과 보기', onClick: () => {
      if (!canDiscard()) return
      const done = meetingsRef.current.find((meeting) => meeting.key === key)?.active === false
      setProjectId(projectOf)
      setView(done ? 'done' : 'active')
      if (done) setOpenMeetings((open) => new Set(open).add(key))
      window.requestAnimationFrame(() => document.getElementById(`meeting-${key}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' }))
    } }
    setNotifications((items) => [...items, { ...content, id: ++notificationId.current, action }].slice(-3))
  }
  // 조회 필터 변경 시 기존 프로젝트 카드가 잠깐 표시되지 않게 한다.
  // 펼쳐도 목록 줄은 그대로 두고(머리 역할) 아래에 내용만 붙인다.
  const ticketItem = (ticket: MyTicket, open: boolean) => {
    const url = safePublishUrl(ticket.githubIssueUrl ?? null, ticket.publishTarget)
    const quick = ticket.status === 'CONFIRMED' ? { action: 'reopen' as const, label: '승인 취소' } : ticket.status === 'REJECTED' ? { action: 'restore' as const, label: '거절 취소' } : null
    // 줄의 빈 곳을 눌러도 펼친다. 링크·버튼을 누를 때는 그 동작만 한다.
    // 발행 완료 탭에서는 요약과 같은 말(발행·생략·거절)로 보여 준다. 연결·갱신 구분은 링크 글자에 남긴다.
    const kind = view === 'done' ? summaryKinds.find((item) => item.match(ticket)) : undefined
    return <li key={ticket.ticketId} className={open ? 'meeting-ticket-open' : undefined}>
      <div className="meeting-ticket-line" onClick={(event) => { if (!busy && !(event.target as HTMLElement).closest('a, button')) toggleTicket(ticket) }}>
      <span className="meeting-ticket-chevron" aria-hidden="true">›</span>
      <span className={`badge ticket-status ${kind?.tone ?? ticket.status.toLowerCase()}`}>{kind?.label ?? ticketStatusLabel[ticket.status]}</span>
      <span className="meeting-ticket-tool">{targetLabel[ticket.publishTarget]}</span>
      <strong className={ticket.status === 'REJECTED' ? 'muted' : undefined}>{singleLineLabel(ticket.title, '제목 없음')}</strong>
      <span className="meeting-ticket-end">
        {url && <a href={url} target="_blank" rel="noreferrer">{ticket.status === 'LINKED' ? '기존 ' : ticket.status === 'UPDATED' ? '기존 ' : ''}{ticket.publishTarget === 'GITHUB' && ticket.githubIssueNumber ? `#${ticket.githubIssueNumber}` : linkLabel[ticket.publishTarget]}{ticket.status === 'LINKED' ? '에 연결' : ticket.status === 'UPDATED' ? ' 갱신' : ' 열기'} ↗</a>}
        <button type="button" className="link" disabled={busy} aria-expanded={open} onClick={() => toggleTicket(ticket)}>{open ? '접기' : needsAction(ticket) ? '펼치기' : '내용 보기'}</button>
        {quick && !open && <button type="button" className="link" disabled={busy} onClick={() => void commit(ticket, () => api.changeTicketStatus(ticket.ticketId, quick.action), false).catch((e: unknown) => { if (!unauthorized(e)) setNotice(e instanceof Error ? e.message : '요청에 실패했습니다.') })}>{quick.label}</button>}
      </span>
      </div>
      {open && <TicketCard ticket={ticket} busy={busy} inGroup onCommit={commit} onRefresh={load} onDirtyChange={dirtyChange} onOpenProjectSettings={(id) => void openSettings(id)} />}
    </li>
  }
  return (
    <section className="my-tickets" aria-label="내 티켓">
      {projects.length > 0 && <div className="project-tabs" role="tablist" aria-label="프로젝트">
        {projects.map((project) => <button key={project.projectId} type="button" role="tab" aria-selected={project.projectId === activeProject?.projectId} className={project.projectId === activeProject?.projectId ? 'active' : ''} disabled={busy} onClick={() => changeProject(project.projectId)}>
          {singleLineLabel(project.name, '프로젝트')}{project.todo > 0 && <span>{project.todo}</span>}
        </button>)}
      </div>}
      {activeProject && <div className="ticket-tabs" role="tablist" aria-label="회의 상태">
        {(['active', 'done'] as const).map((key) => <button key={key} type="button" role="tab" aria-selected={view === key} className={view === key ? 'active' : ''} disabled={busy} onClick={() => changeView(key)}>
          {key === 'active' ? '진행 중' : '발행 완료'} <span>{meetings.filter((meeting) => meeting.active === (key === 'active')).length}</span>
        </button>)}
      </div>}
      {settingsFeedback && <p ref={settingsMessage} tabIndex={-1} className={settingsFeedback.error ? 'error ticket-settings-feedback' : 'notice ticket-settings-feedback'} role={settingsFeedback.error ? 'alert' : 'status'}>{settingsFeedback.message}</p>}
      <Toast message={notice} onDone={() => setNotice(null)} />
      <NotificationStack items={notifications} onDismiss={dismissNotification} />
      {error && <div className="ticket-error" role="alert"><p className="error">{error}</p><button type="button" className="link" disabled={busy} onClick={() => void manualRefresh()}>목록 다시 시도</button></div>}
      {loading && !current && <p className="inline-status" role="status"><Spinner size="sm" label="내 티켓 조회 중" />내 티켓을 불러오고 있습니다.</p>}
      {current && !current.tickets.length && !loading && <p className="card empty">아직 나에게 전달된 티켓이 없습니다.</p>}
      {current && current.tickets.length > 0 && !visibleMeetings.length && <p className="card empty" role="status">{view === 'active' ? '처리할 회의가 없어요. 지난 회의는 발행 완료에서 볼 수 있어요.' : '아직 발행을 마친 회의가 없어요.'}</p>}
      <div className="meeting-groups" aria-busy={busy}>
        {view === 'active' && visibleMeetings.map((meeting) => <BatchPublishFlow key={meeting.key} id={`meeting-${meeting.key}`} tickets={meeting.tickets} busy={busy || Boolean(error)} dirty={hasDirty} onLock={publishLock} onRefresh={load}
          onPublishStart={() => setAwaitingMeeting(meeting.key, true)}
          onPublished={(result) => notifyPublished(meeting.key, singleLineLabel(meeting.tickets[0].meetingTitle), meeting.tickets[0].projectId, result)}>
          <ul className="meeting-tickets">
            {/* 확인해도 자리는 그대로 두고 접히기만 한다. */}
            {meeting.tickets.map((ticket) => ticketItem(ticket, expanded(ticket)))}
          </ul>
        </BatchPublishFlow>)}
        {view === 'done' && visibleMeetings.map((meeting) => {
          const open = openMeetings.has(meeting.key)
          return <section key={meeting.key} id={`meeting-${meeting.key}`} className="card meeting-group" aria-label={`${singleLineLabel(meeting.tickets[0].meetingTitle)} 회의`}>
            <header className="meeting-group-head">
              <div className="meeting-group-title"><h3>{singleLineLabel(meeting.tickets[0].meetingTitle)}</h3><p className="muted">{registeredAt(meeting.tickets[0].createdAt) ? `${registeredAt(meeting.tickets[0].createdAt)} 등록 · ` : ''}내 티켓 {meeting.tickets.length}건</p></div>
              <div className="meeting-group-publish">
                {statusSummary(meeting.tickets).map((kind) => <span key={kind.label} className={`badge ticket-status ${kind.tone}`}>{kind.label} {kind.count}</span>)}
                <button type="button" className="link" aria-expanded={open} onClick={() => toggle(setOpenMeetings, meeting.key)}>{open ? '접기' : '펼치기'}</button>
              </div>
            </header>
            {open && <ul className="meeting-tickets">{meeting.tickets.map((ticket) => ticketItem(ticket, expanded(ticket)))}</ul>}
          </section>
        })}
      </div>
    </section>
  )
}
