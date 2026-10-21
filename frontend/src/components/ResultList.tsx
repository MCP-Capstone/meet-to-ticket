import type { Meeting, PublishStatus, TaskItem, TaskItemList } from '../api'

interface Props {
  meeting: Meeting
  participants: string[]
  result: TaskItemList
}

const publishLabel: Record<PublishStatus, string> = {
  SUCCEEDED: '발행됨',
  FAILED: '발행 실패',
  UNKNOWN: '확인 필요',
  SKIPPED: '발행 생략',
  BLOCKED: '발행 제외',
}

const backgroundText = (item: TaskItem) => item.contentJson?.background ?? item.contentJson?.description ?? null

const assigneeText = (item: TaskItem) => {
  const assignees = item.contentJson?.assignees
  const names = Array.isArray(assignees) ? assignees.map((a) => a.mentionedName) : (assignees?.names ?? [])
  return names.length > 0 ? names.join(', ') : '미정'
}

const dueText = (item: TaskItem) => {
  const due = item.contentJson?.due
  const original = due?.originalText ?? due?.text
  if (!due?.date) return original || '미정'
  return due.time ? `${due.date} ${due.time}` : due.date
}

const correctionCount = (item: TaskItem) =>
  item.contentJson?.review?.correctionCount ?? item.contentJson?.review?.revisions ?? 0

// 반려 사유는 reason / 검수 사유, 발행 실패 / 확인 필요는 errorMessage
const noteText = (item: TaskItem) =>
  item.publishStatus === 'FAILED' || item.publishStatus === 'UNKNOWN'
    ? item.errorMessage
    : item.reviewStatus === 'REJECTED'
      ? (item.reason ?? item.contentJson?.review?.reason ?? null)
      : null

export function ResultList({ meeting, participants, result }: Props) {
  const { summary } = result
  const problems = summary.failed + summary.unknown

  return (
    <>
      {result.executionMode === 'MOCK' && (
        <p className="notice">모의 실행 결과입니다. Agent를 호출하지 않았고 실제 GitHub 이슈는 만들지 않았습니다.</p>
      )}
      {result.status === 'PARTIALLY_FAILED' && <p className="error">일부 항목의 GitHub 이슈 발행에 실패했습니다.</p>}
      {result.status === 'FAILED' && <p className="error">승인된 항목의 GitHub 이슈 발행에 모두 실패했습니다.</p>}

      <section className="card summary">
        <div>
          <h2>{meeting.title}</h2>
          <div className="meta">
            <span>{meeting.meetingDate}</span>
            {participants.length > 0 && <span>참여자 {participants.join(', ')}</span>}
          </div>
        </div>
        <div className="counts">
          <div className="count">
            <strong>{summary.succeeded}</strong>
            <span>발행</span>
          </div>
          <div className="count excluded">
            <strong>{summary.rejected}</strong>
            <span>검수 반려</span>
          </div>
          {summary.skipped > 0 && (
            <div className="count excluded">
              <strong>{summary.skipped}</strong>
              <span>발행 생략</span>
            </div>
          )}
          {problems > 0 && (
            <div className="count failed">
              <strong>{problems}</strong>
              <span>실패 / 확인</span>
            </div>
          )}
        </div>
      </section>

      <section className="card">
        <h2>항목별 결과</h2>
        {result.items.length === 0 ? (
          <p className="muted empty">회의록에서 추출된 할 일이 없습니다.</p>
        ) : (
          <ul className="items">
            {result.items.map((item) => {
              const note = noteText(item)
              const corrections = correctionCount(item)
              const background = backgroundText(item)
              return (
                <li key={item.itemId} className={`item ${item.publishStatus.toLowerCase()}`}>
                  <div className="item-title">
                    <span className={`badge ${item.publishStatus.toLowerCase()}`}>{publishLabel[item.publishStatus]}</span>
                    <strong>{item.title}</strong>
                  </div>
                  {background && <p>{background}</p>}
                  <div className="meta">
                    <span>
                      검수 {item.reviewStatus === 'APPROVED' ? '통과' : '반려'}
                      {corrections > 0 && ` (보완 ${corrections}회)`}
                    </span>
                    <span>담당 {assigneeText(item)}</span>
                    <span>마감 {dueText(item)}</span>
                    {item.githubIssueUrl && (
                      <a href={item.githubIssueUrl} target="_blank" rel="noreferrer">
                        #{item.githubIssueNumber} GitHub에서 보기 ↗
                      </a>
                    )}
                  </div>
                  {note && <p className="reason">{note}</p>}
                </li>
              )
            })}
          </ul>
        )}
      </section>
    </>
  )
}
