import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, TicketPublicationPreview as Preview } from '../api'
import { IssueMarkdown } from './IssueMarkdown'
import { Spinner } from './Spinner'

// GitHub가 숨겨 표시하는 발행 중복 확인용 표식은 초안에서도 숨긴다.
const visibleBody = (body: string) => body.split('\n').filter((line) => !/^<!-- meettoticket:(link|update|update-comment) .* -->$/.test(line)).join('\n')

/** 검토자가 확인할 것은 발행될 초안과 회의록 근거뿐이다. 서버에 저장된 내용을 바로 펼쳐 보여 준다. */
// clamp: 회의 묶음처럼 티켓이 여러 개 펼쳐지는 곳에서는 본문을 일정 높이까지만 보여 주고 '전체 보기'로 펼친다.
export function TicketDraft({ ticket, clamp = false }: { ticket: MyTicket; clamp?: boolean }) {
  const [full, setFull] = useState(false)
  const [overflow, setOverflow] = useState(false)
  const bodyRef = useRef<HTMLDivElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [data, setData] = useState<{ version: string; value: Preview } | null>(null)
  const [failure, setFailure] = useState<{ version: string; message: string } | null>(null)
  const version = JSON.stringify([ticket.ticketId, ticket.title, ticket.description, ticket.due, ticket.assignee, ticket.processingType, ticket.publishTarget, ticket.status])
  useEffect(() => {
    let cancelled = false
    void api.getTicketPreview(ticket.ticketId).then((result) => {
      if (!cancelled) setData({ version, value: result })
    }).catch((e: unknown) => {
      if (!cancelled && !(e instanceof ApiRequestError && e.code === 'UNAUTHORIZED')) setFailure({ version, message: e instanceof Error ? e.message : '초안을 불러오지 못했습니다.' })
    })
    return () => { cancelled = true }
  }, [version, attempt, ticket.ticketId])
  const current = data?.version === version ? data.value : null
  const error = failure?.version === version ? failure.message : null

  // GitHub 본문은 Agent가 만들어 준다. 기다리는 동안이나 Agent에 닿지 못해도 저장된 본문은 바로 보여 준다.
  if (error || !current) return <section className="ticket-draft" aria-label="저장된 본문">
    {error
      ? <p className="muted" role="alert">발행 형식으로는 불러오지 못했어요. 저장된 본문을 보여 드려요. <button type="button" className="link" onClick={() => { setFailure(null); setAttempt((n) => n + 1) }}>다시 불러오기</button></p>
      : <p className="muted inline-status" role="status"><Spinner size="sm" label="초안 불러오는 중" /> 발행 형식을 불러오고 있어요.</p>}
    {ticket.description ? <p className="ticket-body">{ticket.description}</p> : <p className="muted">저장된 본문이 없습니다.</p>}
  </section>

  const body = current.publication?.body.trim() ? visibleBody(current.publication.body) : null
  const quotes = current.backlog.evidenceQuotes
  // GitHub 이슈 본문에는 근거가 들어 있다. 본문에 없는 근거만 따로 보여 준다.
  const showQuotes = quotes.length > 0 && !(body && quotes.every((quote) => body.includes(quote)))
  return <section className="ticket-draft" aria-label="발행될 초안">
    {current.publication && current.publication.targetIssueNumber !== null && <p className="muted">기존 이슈 #{current.publication.targetIssueNumber}에 {current.publication.kind === 'LINK' ? '댓글로 추가돼요' : '내용이 추가돼요'}</p>}
    <DraftBody clamp={clamp && !full} bodyRef={bodyRef} onOverflow={setOverflow}>
    {body ? <IssueMarkdown body={body} /> : <>
      {current.backlog.description && <p className="ticket-body">{current.backlog.description}</p>}
      <BacklogSection title="상세 작업" items={current.backlog.tasks} />
      <BacklogSection title="완료 조건" items={current.backlog.acceptanceCriteria} />
      <BacklogSection title="확인 필요 사항" items={current.backlog.openQuestions} />
      {!current.backlog.description && ![current.backlog.tasks, current.backlog.acceptanceCriteria, current.backlog.openQuestions].some((items) => items.length) && <p className="muted">초안 본문이 없습니다.</p>}
    </>}
    {current.publication?.secondaryBody && <div><h4>갱신 알림 댓글</h4><IssueMarkdown body={visibleBody(current.publication.secondaryBody)} /></div>}
    {showQuotes && <div className="ticket-draft-evidence"><h4>회의록 근거</h4><ul>{quotes.map((quote, i) => <li key={i}>{quote}</li>)}</ul></div>}
    </DraftBody>
    {clamp && (overflow || full) && <button type="button" className="link ticket-draft-toggle" aria-expanded={full} onClick={() => setFull((value) => !value)}>{full ? '접기' : '전체 보기'}</button>}
  </section>
}
function DraftBody({ clamp, bodyRef, onOverflow, children }: { clamp: boolean; bodyRef: RefObject<HTMLDivElement | null>; onOverflow: (value: boolean) => void; children: ReactNode }) {
  // 그린 뒤 실제 높이를 재서 넘칠 때만 '전체 보기'를 보여 준다.
  useLayoutEffect(() => { const el = bodyRef.current; if (el && clamp) onOverflow(el.scrollHeight > el.clientHeight + 1) })
  return <div ref={bodyRef} className={`ticket-draft-body${clamp ? ' clamped' : ''}`}>{children}</div>
}
function BacklogSection({ title, items }: { title: string; items: string[] }) {
  return items.length ? <div><h4>{title}</h4><ul>{items.map((item, i) => <li key={i}>{item}</li>)}</ul></div> : null
}
