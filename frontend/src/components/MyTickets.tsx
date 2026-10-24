import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, MyTicketList } from '../api'
import { Spinner } from './Spinner'
import { Toast } from './Toast'
import { TicketCard } from './TicketCard'
import { BatchPublishFlow } from './BatchPublishFlow'
import { singleLineLabel, ticketStatusLabel } from '../displayText'

interface Props {
  onBusyChange: (busy: boolean) => void
  onDirtyChange: (dirty: boolean) => void
  onOpenProjectSettings: (projectId: number) => void
}
const unauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'
const countKey = (status: MyTicket['status']) => status === 'PENDING_REVIEW' ? 'pendingReview' : status === 'CONFIRMED' ? 'confirmed' : status === 'REJECTED' ? 'rejected' : status === 'FAILED' ? 'failed' : null
type TicketTab = 'review' | 'approved' | 'rejected'

const tabLabel: Record<TicketTab, string> = {
  review: '검토',
  approved: '승인됨',
  rejected: '거절됨',
}
const targetLabel: Record<MyTicket['publishTarget'], string> = { GITHUB: 'GitHub 이슈', NOTION: 'Notion', SLACK: 'Slack' }

const tabTickets = (tickets: MyTicket[], tab: TicketTab) => tickets.filter((ticket) => {
  if (tab === 'review') return ticket.status === 'PENDING_REVIEW' || ticket.status === 'ASSIGNEE_REQUIRED'
  if (tab === 'approved') return ticket.status === 'CONFIRMED' || ticket.status === 'PUBLISHING' || ticket.status === 'PUBLISHED' || ticket.status === 'LINKED' || ticket.status === 'UPDATED' || ticket.status === 'SKIPPED' || ticket.status === 'FAILED'
  if (tab === 'rejected') return ticket.status === 'REJECTED'
  return false
})

const groupByProjectAndMeeting = (tickets: MyTicket[]) => {
  const groups = new Map<string, { projectName: string; meetingTitle: string; tickets: MyTicket[] }>()
  for (const ticket of tickets) {
    const key = `${ticket.projectId}:${ticket.meetingId}`
    const group = groups.get(key) ?? { projectName: ticket.projectName, meetingTitle: ticket.meetingTitle, tickets: [] }
    group.tickets.push(ticket)
    groups.set(key, group)
  }
  return [...groups.values()]
}

export function MyTickets({ onBusyChange, onDirtyChange, onOpenProjectSettings }: Props) {
  const [data, setData] = useState<MyTicketList | null>(null)
  const [tab, setTab] = useState<TicketTab>('review')
  const [detailOpen, setDetailOpen] = useState(false)
  const [selectedTicketId, setSelectedTicketId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [mutating, setMutating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
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
      if (!transferred && previous.status === 'PENDING_REVIEW' && (updated.status === 'CONFIRMED' || updated.status === 'REJECTED')) {
        const next = data?.tickets.find(item => item.ticketId !== previous.ticketId && item.projectId === previous.projectId && item.meetingId === previous.meetingId && item.status === 'PENDING_REVIEW')
        setSelectedTicketId(next?.ticketId ?? updated.ticketId)
        if (!next) setTab(updated.status === 'CONFIRMED' || data?.tickets.some(item => item.projectId === previous.projectId && item.meetingId === previous.meetingId && item.status === 'CONFIRMED') ? 'approved' : 'rejected')
      }
      // 승인·거절을 취소하거나 실패한 티켓을 수정하러 되돌리면 검토 탭으로 옮겨 그대로 이어서 보게 한다.
      if (!transferred && previous.status !== 'PENDING_REVIEW' && updated.status === 'PENDING_REVIEW') {
        setTab('review')
        setSelectedTicketId(updated.ticketId)
      }
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
      setNotice(transferred ? '담당자를 변경했습니다. 해당 티켓은 새 담당자의 내 티켓으로 이동했습니다.' : updated.status === 'PENDING_REVIEW' && previous.status !== 'PENDING_REVIEW' ? '검토 탭으로 옮겼어요.' : '티켓 변경을 저장했습니다.')
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
    if (canDiscard() && await load()) setRevision((value) => value + 1)
  }
  const changeTab = (next: TicketTab) => {
    if (next === tab || mutationLock.current || loading || !canDiscard()) return
    setDetailOpen(false)
    setTab(next)
    setSelectedTicketId(null)
    setRevision((n) => n + 1)
  }
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
  const tabs: TicketTab[] = ['review', 'approved', 'rejected']
  const activeTab = tab
  const visibleTickets = tabTickets(current?.tickets ?? [], activeTab)
  const ticketGroups = groupByProjectAndMeeting(visibleTickets)
  const selectedTicket = visibleTickets.find((ticket) => ticket.ticketId === selectedTicketId) ?? visibleTickets[0] ?? null
  const meetingContext = selectedTicket ?? current?.tickets.find(ticket => ticket.ticketId === selectedTicketId) ?? current?.tickets[0]
  const selectedMeetingTickets = meetingContext
    ? (current?.tickets.filter((ticket) => ticket.projectId === meetingContext.projectId && ticket.meetingId === meetingContext.meetingId) ?? [])
    : []
  const publishLock = useCallback((value: boolean) => {
    mutationLock.current = value
    setMutating(value)
    onBusyChange(value)
  }, [onBusyChange])
  const busy = mutating || loading
  // 조회 필터 변경 시 기존 프로젝트 카드가 잠깐 표시되지 않게 한다.
  return (
    <section className="my-tickets" aria-label="내 티켓">
      {current && <div className="ticket-tabs" role="tablist" aria-label="티켓 상태별 목록">
          {tabs.map((key) => {
            const count = tabTickets(current.tickets, key).length
            return <button key={key} type="button" role="tab" aria-selected={activeTab === key} className={activeTab === key ? 'active' : ''} onClick={() => changeTab(key)} disabled={busy}>{tabLabel[key]} <span>{count}</span></button>
          })}
      </div>}
      {current && <div className="meeting-publish-area"><BatchPublishFlow tickets={selectedMeetingTickets} busy={busy || Boolean(error)} dirty={hasDirty} onLock={publishLock} onRefresh={load} /></div>}
      {settingsFeedback && <p ref={settingsMessage} tabIndex={-1} className={settingsFeedback.error ? 'error ticket-settings-feedback' : 'notice ticket-settings-feedback'} role={settingsFeedback.error ? 'alert' : 'status'}>{settingsFeedback.message}</p>}
      <Toast message={notice} onDone={() => setNotice(null)} />
      {error && <div className="ticket-error" role="alert"><p className="error">{error}</p><button type="button" className="link" disabled={busy} onClick={() => void manualRefresh()}>목록 다시 시도</button></div>}
      {loading && <p className="inline-status" role="status"><Spinner size="sm" label="내 티켓 조회 중" />내 티켓을 불러오고 있습니다.</p>}
      {current && !current.tickets.length && !loading && <p className="card empty">아직 나에게 전달된 티켓이 없습니다.</p>}
      {current && current.tickets.length > 0 && !visibleTickets.length && !loading && <p className="card empty" role="status">이 상태의 티켓이 없습니다.</p>}
      {current && visibleTickets.length > 0 && <div className={`ticket-workspace${detailOpen ? ' detail-open' : ''}`} aria-busy={busy}>
        <aside className="ticket-list-panel" aria-label={`${tabLabel[activeTab]} 목록`}>
          <div className="ticket-list-panel-heading"><strong>티켓 목록</strong><span>{visibleTickets.length}건</span></div>
          {ticketGroups.map((group) => <section className="ticket-group" key={`${group.projectName}:${group.meetingTitle}`}>
            <header><strong>{group.projectName}</strong><span>{singleLineLabel(group.meetingTitle)}</span><em>{group.tickets.length}</em></header>
            {group.tickets.map((ticket) => <button type="button" key={`${ticket.ticketId}-${revision}`} className={`ticket-list-item${selectedTicket?.ticketId === ticket.ticketId ? ' selected' : ''}`} onClick={() => {
              if (canDiscard()) { setSelectedTicketId(ticket.ticketId); setDetailOpen(true) }
            }} disabled={busy}>
              <strong>{singleLineLabel(ticket.title, '제목 없음')}</strong>
              <span>{targetLabel[ticket.publishTarget]}</span>
              <em className={`ticket-list-status ${ticket.status.toLowerCase()}`}>{ticketStatusLabel[ticket.status]}</em>
            </button>)}
          </section>)}
        </aside>
        <section className="ticket-detail-panel" aria-label="선택한 티켓 상세">
          <button type="button" className="mobile-ticket-back" disabled={busy} onClick={() => { if (canDiscard()) setDetailOpen(false) }}>← 티켓 목록으로</button>
          {selectedTicket && <>
            <TicketCard key={selectedTicket.ticketId} ticket={selectedTicket} busy={busy} onCommit={commit} onRefresh={load} onDirtyChange={dirtyChange} onOpenProjectSettings={(id) => void openSettings(id)} />
          </>}
        </section>
      </div>}
    </section>
  )
}
