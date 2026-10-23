import type { MyTicket } from '../api'
import { safeIssueUrl } from '../batchPublish'

const relations = { SAME: '같은 업무', CHANGE: '기존 업무의 변경', RELATED: '관련된 업무', UNRELATED: '관계없는 업무' }
const processingTypes = { CREATE: '신규 발행', LINK: '기존 이슈 연결', UPDATE: '기존 이슈 갱신' }
const skippedReasons: Record<string, string> = {
  timeout: '판단 시간이 초과됐습니다.',
  deadline: '판단에 사용할 시간이 부족했습니다.',
  tool_limit: '도구 호출 한도에 도달했습니다.',
  same_tool_limit: '같은 도구의 반복 호출 한도에 도달했습니다.',
  rag_unavailable: '기존 이슈 검색을 사용할 수 없었습니다.',
  no_tool_model: '도구를 사용하는 모델이 준비되지 않았습니다.',
  unverified_issue: '기존 이슈 정보를 확인하지 못했습니다.',
  no_decision: '판단 결과를 얻지 못했습니다.',
  invalid_decision: '유효한 판단 결과를 얻지 못했습니다.',
}

export function TicketDedupeDetails({ ticket }: { ticket: MyTicket }) {
  const d = ticket.dedupe
  const headingId = `dedupe-${ticket.ticketId}`
  if (!d) return <section className="ticket-dedupe" aria-labelledby={headingId}>
    <h4 id={headingId}>중복 판단</h4><p className="muted">중복 판단 정보가 제공되지 않았습니다.</p>
  </section>
  if (d.judged !== true) return <section className="ticket-dedupe" aria-labelledby={headingId}>
    <h4 id={headingId}>중복 판단</h4><p>중복 판단 안 됨</p>
    {d.skipped && <p className="muted">{Object.hasOwn(skippedReasons, d.skipped) ? skippedReasons[d.skipped] : '판단을 완료하지 못했습니다.'}</p>}
  </section>
  const issue = d.issue
  const url = safeIssueUrl(issue?.url ?? null)
  const before = d.before?.trim() ? d.before : null
  const after = d.after?.trim() ? d.after : null
  const compare = (ticket.processingType === 'UPDATE' || d.relation === 'CHANGE') && Boolean(before || after)
  const relation = d.relation && Object.hasOwn(relations, d.relation) ? relations[d.relation] : '관계 정보 없음'
  return <section className="ticket-dedupe" aria-labelledby={headingId}>
    <h4 id={headingId}>중복 판단</h4>
    {ticket.publishTarget === 'GITHUB' && ticket.processingType === 'CREATE' && d.overlap === true && <p className="ticket-overlap" role="status"><strong>겹칠 수 있음</strong> · 기존 이슈와 중복되는 업무가 있는지 확인하세요.</p>}
    <p>기존 이슈와의 관계: <strong>{relation}</strong></p>
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
