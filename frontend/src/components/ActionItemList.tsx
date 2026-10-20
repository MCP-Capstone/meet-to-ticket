import type { ActionItem, Priority, Ticket } from '../api'

interface Props {
  actionItems: ActionItem[]
  tickets: Ticket[]
  creating: boolean
  onCreateTickets: () => void
}

const priorityLabel: Record<Priority, string> = {
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
}

export function ActionItemList({ actionItems, tickets, creating, onCreateTickets }: Props) {
  const ticketByItem = new Map(tickets.map((t) => [t.actionItemId, t]))
  const done = tickets.length > 0

  return (
    <section className="card">
      <div className="card-header">
        <h2>추출된 할 일 ({actionItems.length})</h2>
        <button onClick={onCreateTickets} disabled={creating || done || actionItems.length === 0}>
          {creating ? '생성 중...' : done ? '티켓 생성 완료' : '전체 티켓 생성'}
        </button>
      </div>

      {actionItems.length === 0 ? (
        <p className="muted">추출된 할 일이 없습니다.</p>
      ) : (
        <ul className="items">
          {actionItems.map((item) => {
            const ticket = ticketByItem.get(item.actionItemId)
            return (
              <li key={item.actionItemId} className="item">
                <div className="item-main">
                  <strong>{item.title}</strong>
                  <p>{item.description}</p>
                  <div className="meta">
                    <span className={`badge ${item.priority.toLowerCase()}`}>
                      {priorityLabel[item.priority]}
                    </span>
                    <span>담당: {item.assignee ?? '미정'}</span>
                    <span>마감: {item.dueDate ?? '미정'}</span>
                  </div>
                </div>
                {ticket && (
                  <div className="item-ticket">
                    {ticket.status === 'CREATED' && ticket.externalUrl ? (
                      <a href={ticket.externalUrl} target="_blank" rel="noreferrer">
                        티켓 보기
                      </a>
                    ) : (
                      <span className="error-text">생성 실패</span>
                    )}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
