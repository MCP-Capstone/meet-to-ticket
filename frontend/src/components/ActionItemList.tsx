import type { ActionItem, Meeting, Priority } from '../api'

interface Props {
  meeting: Meeting
  actionItems: ActionItem[]
}

const priorityLabel: Record<Priority, string> = {
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
}

export function ActionItemList({ meeting, actionItems }: Props) {
  return (
    <>
      <section className="card summary">
        <div>
          <h2>{meeting.title}</h2>
          <div className="meta">
            <span>{meeting.meetingDate}</span>
            {meeting.participants.length > 0 && <span>참석자 {meeting.participants.join(', ')}</span>}
          </div>
        </div>
        <div className="count">
          <strong>{actionItems.length}</strong>
          <span>할 일</span>
        </div>
      </section>

      <section className="card">
        <h2>추출된 할 일</h2>
        {actionItems.length === 0 ? (
          <p className="muted empty">회의록에서 추출된 할 일이 없습니다.</p>
        ) : (
          <ul className="items">
            {actionItems.map((item) => (
              <li key={item.actionItemId} className="item">
                <div className="item-title">
                  <span className={`badge ${item.priority.toLowerCase()}`}>{priorityLabel[item.priority]}</span>
                  <strong>{item.title}</strong>
                </div>
                <p>{item.description}</p>
                <div className="meta">
                  <span>담당 {item.assignee ?? '미정'}</span>
                  <span>마감 {item.dueDate ?? '미정'}</span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}
