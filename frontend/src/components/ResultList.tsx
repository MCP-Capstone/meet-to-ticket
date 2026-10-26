import type { Meeting, PublishStatus, TaskItem, TaskItemList } from '../api'
import { safePublishUrl, toolName } from '../batchPublish'

interface Props {
  meeting: Meeting
  participants: string[]
  result: TaskItemList
}

const publishLabel: Record<PublishStatus, string> = {
  SUCCEEDED: '발행됨',
  FAILED: '발행 실패',
  UNKNOWN: '확인 필요',
  SKIPPED: '발행됨',
  BLOCKED: '발행 제외',
  DRAFT: '발행 전',
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

// 새 이슈를 만들지 않고 기존 GitHub 이슈에 연결만 한 항목
const isLinked = (item: TaskItem) =>
  item.publishStatus === 'SUCCEEDED' && item.contentJson?.dedupe?.linked_existing === true

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
  const linkedCount = result.items.filter(isLinked).length
  // 모의 실행으로 발행한 항목(skipped)도 실제 발행과 같게 센다.
  const published = summary.succeeded - linkedCount + summary.skipped

  return (
    <>
      {result.executionMode === 'MOCK' && (
        <p className="notice">모의 실행 결과입니다. Agent를 호출하지 않았고 실제 외부 발행은 하지 않았습니다.</p>
      )}
      {result.status === 'PARTIALLY_FAILED' && <p className="error">일부 항목의 발행에 실패했습니다.</p>}
      {result.status === 'FAILED' && <p className="error">승인된 항목의 발행에 모두 실패했습니다.</p>}

      <section className="card summary">
        <div>
          <h2>{meeting.title}</h2>
          <div className="meta">
            <span>{meeting.meetingDate}</span>
            {participants.length > 0 && <span>참여자 {participants.join(', ')}</span>}
          </div>
        </div>
        <div className="counts">
          {summary.drafts > 0 && (
            <div className="count draft">
              <strong>{summary.drafts}</strong>
              <span>발행 전 초안</span>
            </div>
          )}
          {/* 초안만 있는 결과(기획_v16)에서는 발행·반려가 0이라 숨긴다 */}
          {published > 0 && (
            <div className="count">
              <strong>{published}</strong>
              <span>발행</span>
            </div>
          )}
          {linkedCount > 0 && (
            <div className="count linked">
              <strong>{linkedCount}</strong>
              <span>기존 이슈 연결</span>
            </div>
          )}
          {summary.rejected > 0 && (
            <div className="count excluded">
              <strong>{summary.rejected}</strong>
              <span>검수 반려</span>
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
              const linked = isLinked(item)
              const statusClass = linked ? 'linked' : item.publishStatus === 'SKIPPED' ? 'succeeded' : item.publishStatus.toLowerCase()
              const note = linked ? (item.reason ?? item.contentJson?.dedupe?.reason ?? null) : noteText(item)
              const corrections = correctionCount(item)
              const target = item.publishTarget ?? 'GITHUB'
              const url = safePublishUrl(item.githubIssueUrl, target)
              const background = backgroundText(item)
              const warnings = item.contentJson?.warnings ?? []
              const reviewNotes = item.contentJson?.review?.notes ?? []
              return (
                <li key={item.itemId} className={`item ${statusClass}`}>
                  <div className="item-title">
                    <span className={`badge ${statusClass}`}>{linked ? '기존 이슈 연결' : publishLabel[item.publishStatus]}</span>
                    <strong>{item.title}</strong>
                  </div>
                  {background && <p>{background}</p>}
                  <div className="meta">
                    <span>
                      {item.reviewStatus === 'PENDING' ? '확인 대기' : `검수 ${item.reviewStatus === 'APPROVED' ? '통과' : '반려'}`}
                      {corrections > 0 && ` (보완 ${corrections}회)`}
                    </span>
                    <span>담당 {assigneeText(item)}</span>
                    <span>마감 {dueText(item)}</span>
                    {url && (
                      <a href={url} target="_blank" rel="noreferrer">
                        {target === 'GITHUB' && item.githubIssueNumber ? `#${item.githubIssueNumber} ` : ''}{toolName[target]}에서 보기 ↗
                      </a>
                    )}
                  </div>
                  {note && <p className="reason">{note}</p>}
                  {warnings.length > 0 && (
                    <ul className="warnings">
                      {warnings.map((w) => (
                        <li key={w}>{w}</li>
                      ))}
                    </ul>
                  )}
                  {reviewNotes.length > 0 && (
                    <div className="review-notes">
                      <span>
                        검수 지적{item.contentJson?.review?.fallback && ' · 기본 초안으로 대체됨'}
                      </span>
                      <ul>
                        {reviewNotes.map((n) => (
                          <li key={n}>{n}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </section>
    </>
  )
}
