import type { Meeting, PublishStatus, TicketResult } from '../api'

interface Props {
  meeting: Meeting
  results: TicketResult[]
}

const publishLabel: Record<PublishStatus, string> = {
  PUBLISHED: '발행됨',
  FAILED: '발행 실패',
  EXCLUDED: '발행 제외',
}

const count = (results: TicketResult[], status: PublishStatus) => results.filter((r) => r.publishStatus === status).length

export function ResultList({ meeting, results }: Props) {
  return (
    <>
      <section className="card summary">
        <div>
          <h2>{meeting.title}</h2>
          <div className="meta">
            <span>{meeting.meetingDate}</span>
            {meeting.participants.length > 0 && <span>참여자 {meeting.participants.join(', ')}</span>}
          </div>
        </div>
        <div className="counts">
          <div className="count">
            <strong>{count(results, 'PUBLISHED')}</strong>
            <span>발행</span>
          </div>
          <div className="count excluded">
            <strong>{count(results, 'EXCLUDED') + count(results, 'FAILED')}</strong>
            <span>제외 / 실패</span>
          </div>
        </div>
      </section>

      <section className="card">
        <h2>항목별 결과</h2>
        {results.length === 0 ? (
          <p className="muted empty">회의록에서 추출된 할 일이 없습니다.</p>
        ) : (
          <ul className="items">
            {results.map((r) => (
              <li key={r.resultId} className={`item ${r.publishStatus.toLowerCase()}`}>
                <div className="item-title">
                  <span className={`badge ${r.publishStatus.toLowerCase()}`}>{publishLabel[r.publishStatus]}</span>
                  <strong>{r.title}</strong>
                </div>
                <p>{r.description}</p>
                <div className="meta">
                  <span>검수 {r.reviewStatus === 'APPROVED' ? '통과' : '반려'}</span>
                  <span>담당 {r.assignee ?? '미정'}</span>
                  <span>마감 {r.dueDate ?? '미정'}</span>
                  {r.issueUrl && (
                    <a href={r.issueUrl} target="_blank" rel="noreferrer">
                      #{r.issueNumber} GitHub에서 보기 ↗
                    </a>
                  )}
                </div>
                {r.reason && <p className="reason">{r.reason}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}
