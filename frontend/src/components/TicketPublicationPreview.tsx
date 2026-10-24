import { useEffect, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, TicketPublicationPreview as Preview } from '../api'
import { IssueMarkdown } from './IssueMarkdown'
import { Spinner } from './Spinner'

const kinds = { CREATE: '신규 이슈 본문', LINK: '기존 이슈에 추가할 댓글', UPDATE: '기존 이슈 본문에 추가할 내용' }
// GitHub가 숨겨 표시하는 발행 중복 확인용 표식은 미리보기에서도 숨긴다.
const visibleBody = (body: string) => body.split('\n').filter((line) => !/^<!-- meettoticket:(link|update|update-comment) .* -->$/.test(line)).join('\n')

export function TicketPublicationPreview({ ticket, dirty }: { ticket: MyTicket; dirty: boolean }) {
  const [open, setOpen] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [data, setData] = useState<{ version: string; value: Preview } | null>(null)
  const [failure, setFailure] = useState<{ version: string; message: string } | null>(null)
  const version = JSON.stringify([ticket.ticketId, ticket.title, ticket.description, ticket.due, ticket.assignee, ticket.processingType, ticket.publishTarget, ticket.status])
  useEffect(() => {
    if (!open) return
    let cancelled = false
    void Promise.resolve().then(async () => {
      if (cancelled) return
      setData(null)
      setFailure(null)
      try {
        const result = await api.getTicketPreview(ticket.ticketId)
        if (!cancelled) setData({ version, value: result })
      } catch (e) {
        if (!cancelled && !(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) setFailure({ version, message: e instanceof Error ? e.message : '미리보기를 불러오지 못했습니다.' })
      }
    })
    return () => { cancelled = true }
  }, [open, version, attempt, ticket.ticketId])
  const current = data?.version === version ? data.value : null
  const error = failure?.version === version ? failure.message : null
  return <div className="ticket-publication-preview">
    <button type="button" className="secondary" aria-expanded={open} aria-controls={`preview-${ticket.ticketId}`} onClick={() => { setData(null); setFailure(null); setOpen((value) => !value) }}>{open ? '실제 발행 내용 닫기' : '실제 발행 내용 확인'}</button>
    {open && <section id={`preview-${ticket.ticketId}`} className="publication-preview-panel" aria-label="실제 발행 내용">
      <h4>실제 발행 내용</h4>
      <p className="muted">현재 저장된 설정으로 외부 도구에 전달할 형식입니다. 발행 직전 재검수로 처리 유형과 대상이 달라질 수 있습니다.</p>
      {dirty && <p className="notice" role="status">저장하지 않은 수정이 있습니다. 현재 미리보기에는 반영되지 않았습니다. 저장하면 최신 내용으로 다시 조회합니다.</p>}
      {error ? <div role="alert"><p className="error">{error}</p><button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>미리보기 다시 조회</button></div>
        : !current ? <p role="status"><Spinner size="sm" label="미리보기 조회 중" /> 저장된 발행 내용을 불러오고 있습니다.</p>
        : <>
          <h5>{current.title}</h5>
          {current.publication ? <>
            <h5>{current.publishTarget === 'SLACK' ? 'Slack 메시지 미리보기' : kinds[current.publication.kind]}</h5>
            {current.publication.targetIssueNumber !== null && <p>대상 이슈 #{current.publication.targetIssueNumber}</p>}
            {!!current.publication.labels.length && <p>라벨: {current.publication.labels.join(', ')}</p>}
            {!!current.publication.githubAssignees.length && <p>GitHub 담당: {current.publication.githubAssignees.map((n) => `@${n}`).join(', ')}</p>}
            {current.publication.body.trim() ? <IssueMarkdown body={visibleBody(current.publication.body)} /> : <p className="muted">미리보기 본문이 없습니다.</p>}
            {current.publication.secondaryBody && <div><h5>갱신 알림 댓글</h5><IssueMarkdown body={visibleBody(current.publication.secondaryBody)} /></div>}
          </> : <p className="notice">{current.publishTarget} 발행 본문 미리보기는 제공하지 않습니다. 아래에서 저장된 상세 내용을 확인할 수 있습니다.</p>}
          <details className="publication-backlog"><summary>상세 백로그와 근거</summary>
            {current.backlog.description && <div><h5>저장된 설명</h5><p>{current.backlog.description}</p></div>}
            <BacklogSection title="상세 작업" items={current.backlog.tasks} />
            <BacklogSection title="완료 조건" items={current.backlog.acceptanceCriteria} />
            <BacklogSection title="회의록 근거" items={current.backlog.evidenceQuotes} />
            <BacklogSection title="확인 필요 사항" items={current.backlog.openQuestions} />
            {!current.backlog.description && ![current.backlog.tasks, current.backlog.acceptanceCriteria, current.backlog.evidenceQuotes, current.backlog.openQuestions].some((items) => items.length) && <p className="muted">상세 백로그 정보가 제공되지 않았습니다.</p>}
          </details>
        </>}
    </section>}
  </div>
}
function BacklogSection({ title, items }: { title: string; items: string[] }) {
  return items.length ? <div><h5>{title}</h5><ul>{items.map((item, i) => <li key={i}>{item}</li>)}</ul></div> : null
}
