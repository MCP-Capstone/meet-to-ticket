import { useEffect, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { MyTicket, TicketPublicationPreview as Preview } from '../api'
import { IssueMarkdown } from './IssueMarkdown'
import { EvidenceToggle } from './EvidenceToggle'
import { Spinner } from './Spinner'

// Slack 공지 문구는 Slack 서식(*굵게*, • 목록)으로 저장돼 있다.
const flavorOf = (target: MyTicket['publishTarget']) => target === 'SLACK' ? 'slack' : 'markdown'
// GitHub가 숨겨 표시하는 발행 중복 확인용 표식은 초안에서도 숨긴다.
const visibleBody = (body: string) => body.split('\n').filter((line) => !/^<!-- meettoticket:(link|update|update-comment) .* -->$/.test(line)).join('\n')

/** 검토자가 확인할 것은 발행될 초안과 회의록 근거뿐이다. 서버에 저장된 내용을 바로 펼쳐 보여 준다. */
export function TicketDraft({ ticket }: { ticket: MyTicket }) {
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
    {ticket.description ? <IssueMarkdown body={ticket.description} flavor={flavorOf(ticket.publishTarget)} /> : <p className="muted">저장된 본문이 없습니다.</p>}
  </section>

  const quotes = current.backlog.evidenceQuotes
  // 발행 본문은 GitHub 등에서 볼 때도 참고할 수 있게 회의 근거 칸까지 그대로 보여 준다.
  const body = current.publication?.body.trim() ? visibleBody(current.publication.body) : null
  return <section className="ticket-draft" aria-label="발행될 초안">
    {current.publication && current.publication.targetIssueNumber !== null && <p className="muted">기존 이슈 #{current.publication.targetIssueNumber}에 {current.publication.kind === 'LINK' ? '댓글로 추가돼요' : '내용이 추가돼요'}</p>}
    <div className="ticket-draft-body">
    {body ? <IssueMarkdown body={body} flavor={flavorOf(ticket.publishTarget)} /> : <>
      {current.backlog.description && <IssueMarkdown body={current.backlog.description} flavor={flavorOf(ticket.publishTarget)} />}
      <BacklogSection title="상세 작업" items={current.backlog.tasks} />
      <BacklogSection title="완료 조건" items={current.backlog.acceptanceCriteria} />
      {/* Notion 페이지에도 회의 근거 칸이 들어간다(agent build_notion_page). Slack 공지에는 없다. */}
      {ticket.publishTarget !== 'SLACK' && <BacklogSection title="회의 근거" items={quotes} />}
      <BacklogSection title="확인 필요 사항" items={current.backlog.openQuestions} />
      {!current.backlog.description && ![current.backlog.tasks, current.backlog.acceptanceCriteria, current.backlog.openQuestions].some((items) => items.length) && <p className="muted">초안 본문이 없습니다.</p>}
    </>}
    {current.publication?.secondaryBody && <div><h4>갱신 알림 댓글</h4><IssueMarkdown body={visibleBody(current.publication.secondaryBody)} /></div>}
    </div>
    <EvidenceToggle quotes={quotes} excerpts={current.evidenceExcerpts ?? []} />
  </section>
}
function BacklogSection({ title, items }: { title: string; items: string[] }) {
  return items.length ? <div><h4>{title}</h4><ul>{items.map((item, i) => <li key={i}>{item}</li>)}</ul></div> : null
}
