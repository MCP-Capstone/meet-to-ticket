import type { MyTicket } from '../api'
import { safeIssueUrl } from '../batchPublish'

const relations = { SAME: '같은 업무', CHANGE: '기존 업무의 변경', RELATED: '관련된 업무', UNRELATED: '관계없는 업무' }
const processingTypes = { CREATE: '신규 발행', LINK: '기존 이슈 연결', UPDATE: '기존 이슈 갱신' }
export function TicketDedupeDetails({ ticket }: { ticket: MyTicket }) {
  const d = ticket.dedupe
  const headingId = `dedupe-${ticket.ticketId}`
  // 중복 검수는 발행 안전장치다. 새 작업으로 판단됐거나 판단 정보가 없을 때는
  // 사용자 검토에 도움이 되는 정보가 없으므로 카드에서 노출하지 않는다.
  if (!d || d.judged !== true) return null
  const issue = d.issue
  const url = safeIssueUrl(issue?.url ?? null)
  const before = d.before?.trim() ? d.before : null
  const after = d.after?.trim() ? d.after : null
  const compare = (ticket.processingType === 'UPDATE' || d.relation === 'CHANGE') && Boolean(before || after)
  const relation = d.relation && Object.hasOwn(relations, d.relation) ? relations[d.relation] : '관계 정보 없음'
  const needsReview = Boolean(issue) || d.relation !== 'UNRELATED' || d.changedByRecheck === true
  if (!needsReview) return null
  return <section className="ticket-dedupe" aria-labelledby={headingId}>
    <h4 id={headingId}>기존 이슈 확인</h4>
    {ticket.publishTarget === 'GITHUB' && ticket.processingType === 'CREATE' && d.overlap === true && <p className="ticket-overlap" role="status"><strong>유사 이슈가 있습니다.</strong> 발행 전에 처리 방식을 확인하세요.</p>}
    <p>확인 결과: <strong>{relation}</strong></p>
    {issue ? <div className="ticket-related-issue">
      <span>관련 이슈 </span>
      {url ? <a href={url} target="_blank" rel="noopener noreferrer">#{issue.number}{issue.title?.trim() ? ` ${issue.title}` : ''}</a> : <span>#{issue.number}{issue.title?.trim() ? ` ${issue.title}` : ''} · 링크를 확인할 수 없습니다.</span>}
      <span className="badge">{issue.state === 'open' ? '열림' : issue.state === 'closed' ? '닫힘' : '상태 정보 없음'}</span>
    </div> : d.relation !== 'UNRELATED' && <p className="muted">관련 이슈 정보가 제공되지 않았습니다.</p>}
    {d.reason?.trim() ? <div><h5>판단 사유</h5><p className="ticket-dedupe-text">{d.reason}</p></div> : <p className="muted">판단 사유가 제공되지 않았습니다.</p>}
    {d.changedByRecheck === true && <p className="notice">발행 직전 재대조에서 처리 유형이 변경됐습니다. 현재 처리 유형: {processingTypes[ticket.processingType]}.</p>}
    {compare && <div>
      <h5>변경 전후 비교</h5>
      <p className="muted">서버가 제공한 판단 시점의 내용입니다.</p>
      <div className="ticket-dedupe-comparison">
        {before && <div><h6>변경 전</h6><p className="ticket-dedupe-text">{before}</p></div>}
        {after && <div><h6>변경 후</h6><p className="ticket-dedupe-text">{after}</p></div>}
      </div>
      {!before && <p className="muted">변경 전 정보가 제공되지 않았습니다.</p>}
      {!after && <p className="muted">변경 후 정보가 제공되지 않았습니다.</p>}
    </div>}
  </section>
}
