import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, MyTicketList, Project } from '../api'
import { Spinner } from './Spinner'
import { TicketCard } from './TicketCard'

interface Props {
  projects: Project[]
  projectsLoading: boolean
  onBusyChange: (busy: boolean) => void
  onDirtyChange: (dirty: boolean) => void
}
const unauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'
const countKey = (status: MyTicket['status']) => status === 'PENDING_REVIEW' ? 'pendingReview' : status === 'CONFIRMED' ? 'confirmed' : status === 'REJECTED' ? 'rejected' : null

export function MyTickets({ projects, projectsLoading, onBusyChange, onDirtyChange }: Props) {
  const [projectId, setProjectId] = useState<number | undefined>()
  const [data, setData] = useState<{ filter: number | undefined; value: MyTicketList } | null>(null)
  const [loading, setLoading] = useState(true)
  const [mutating, setMutating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const lifecycle = useRef({ mounted: true, version: 0 })
  const mutationLock = useRef(false)
  const dirtyIds = useRef(new Set<string>())
  const dirtyChange = useCallback((id: string, dirty: boolean) => {
    if (dirty) dirtyIds.current.add(id)
    else dirtyIds.current.delete(id)
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
      const result = await api.listMyTickets(projectId)
      if (!lifecycle.current.mounted || version !== lifecycle.current.version) return false
      setData({ filter: projectId, value: result })
      return true
    } catch (e) {
      if (lifecycle.current.mounted && version === lifecycle.current.version && !unauthorized(e)) {
        setError(e instanceof Error ? e.message : '내 티켓을 불러오지 못했습니다.')
      }
      return false
    } finally {
      if (lifecycle.current.mounted && version === lifecycle.current.version) setLoading(false)
    }
  }, [projectId])
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
    try {
      const updated = await run()
      if (!lifecycle.current.mounted) return
      // 저장 성공을 먼저 반영한다. 후속 조회 실패 때문에 변경 요청을 재전송하지 않는다.
      setData((current) => {
        if (!current) return current
        const counts = { ...current.value.counts }
        const oldKey = countKey(previous.status)
        const newKey = countKey(updated.status)
        if (oldKey) counts[oldKey] = Math.max(0, counts[oldKey] - 1)
        if (!transferred && newKey) counts[newKey] += 1
        return { ...current, value: { counts, tickets: current.value.tickets.flatMap((ticket) => ticket.ticketId !== updated.ticketId ? [ticket] : transferred ? [] : [updated]) } }
      })
      setNotice(transferred ? '담당자를 변경했습니다. 해당 티켓은 새 담당자의 내 티켓으로 이동했습니다.' : '티켓 변경을 저장했습니다.')
      const refreshed = await load()
      if (!refreshed && lifecycle.current.mounted) setNotice('티켓 변경은 저장됐습니다. 최신 목록 조회에 실패했으니 목록만 다시 불러와 주세요.')
    } finally {
      mutationLock.current = false
      if (lifecycle.current.mounted) { setMutating(false); onBusyChange(false) }
    }
  }
  const canDiscard = () => !dirtyIds.current.size || window.confirm('저장하지 않은 수정을 버리고 목록을 변경할까요?')
  const current = data && data.filter === projectId ? data.value : null
  const manualRefresh = async () => {
    if (canDiscard() && await load()) setRevision((value) => value + 1)
  }
  const busy = mutating || loading
  // 조회 필터 변경 시 기존 프로젝트 카드가 잠깐 표시되지 않게 한다.
  return (
    <section className="my-tickets" aria-labelledby="my-tickets-title">
      <div className="card">
        <div className="card-header"><div><h2 id="my-tickets-title">내 티켓</h2><p className="muted">내가 담당인 티켓을 수정하고 확인하거나 거절하세요.</p></div></div>
        <div className="ticket-toolbar">
          <label>프로젝트 필터
            <select value={projectId ?? ''} disabled={mutating || projectsLoading} onChange={(e) => {
              if (!canDiscard()) return
              setNotice(null)
              setProjectId(e.target.value === '' ? undefined : Number(e.target.value))
            }}>
              <option value="">전체 프로젝트</option>
              {projects.map((project) => <option key={project.projectId} value={project.projectId}>{project.name}</option>)}
            </select>
          </label>
          <button type="button" className="secondary" disabled={busy} onClick={() => void manualRefresh()}>목록 새로고침</button>
        </div>
        {current && <dl className="ticket-counts" aria-label="상태별 건수">
          <div><dt>검토 대기</dt><dd>{current.counts.pendingReview}</dd></div>
          <div><dt>확인</dt><dd>{current.counts.confirmed}</dd></div>
          <div><dt>거절</dt><dd>{current.counts.rejected}</dd></div>
        </dl>}
        <p className="muted">확인은 발행 전 검토를 마쳤다는 뜻입니다. 거절한 티켓은 발행되지 않습니다.</p>
      </div>
      {notice && <p className="project-success" role="status">{notice}</p>}
      {error && <div className="ticket-error" role="alert"><p className="error">{error}</p><button type="button" className="link" disabled={busy} onClick={() => void manualRefresh()}>목록 다시 시도</button></div>}
      {loading && <p className="inline-status" role="status"><Spinner size="sm" label="내 티켓 조회 중" />내 티켓을 불러오고 있습니다.</p>}
      {current && !current.tickets.length && !loading && <p className="card empty">{projectId === undefined ? '아직 나에게 전달된 티켓이 없습니다.' : '이 프로젝트에 내가 담당인 티켓이 없습니다.'}</p>}
      {current && <div className="ticket-list" aria-busy={busy}>
        {current.tickets.map((ticket) => <TicketCard key={`${ticket.ticketId}-${revision}`} ticket={ticket} busy={busy} onCommit={commit} onRefresh={load} onDirtyChange={dirtyChange} />)}
      </div>}
    </section>
  )
}
