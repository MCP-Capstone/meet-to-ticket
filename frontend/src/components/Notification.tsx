import { useEffect, useRef, useState } from 'react'

export interface NotificationItem {
  id: number
  // success·info는 잠깐 뒤 사라지고, warning·error는 사용자가 닫을 때까지 남는다.
  tone: 'success' | 'info' | 'warning' | 'error'
  title: string
  lines: string[]
  action?: { label: string; onClick: () => void }
}

const AUTO_CLOSE_MS = 8000

// 발행 결과처럼 화면이 바뀌어도 남아야 하는 안내는 화면 오른쪽 위에 쌓아 띄운다. 짧은 저장 안내는 Toast를 쓴다.
export function NotificationStack({ items, onDismiss }: { items: NotificationItem[]; onDismiss: (id: number) => void }) {
  return <div className="notification-region" aria-live="polite">
    {items.map((item) => <NotificationCard key={item.id} item={item} onDismiss={onDismiss} />)}
  </div>
}

function NotificationCard({ item, onDismiss }: { item: NotificationItem; onDismiss: (id: number) => void }) {
  const [paused, setPaused] = useState(false)
  const dismiss = useRef(onDismiss)
  useEffect(() => { dismiss.current = onDismiss })
  const autoClose = item.tone === 'success' || item.tone === 'info'
  useEffect(() => {
    // 마우스를 올리거나 버튼에 초점이 있으면 읽는 중이므로 닫지 않는다.
    if (!autoClose || paused) return
    const timer = window.setTimeout(() => dismiss.current(item.id), AUTO_CLOSE_MS)
    return () => window.clearTimeout(timer)
  }, [autoClose, paused, item.id])
  return <section className={`notification ${item.tone}`} role={item.tone === 'error' ? 'alert' : 'status'} aria-label={item.title}
    onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onFocus={() => setPaused(true)} onBlur={() => setPaused(false)}>
    <div className="notification-body">
      <strong>{item.title}</strong>
      {item.lines.map((line) => <p key={line}>{line}</p>)}
      {item.action && <button type="button" className="link" onClick={() => { item.action?.onClick(); onDismiss(item.id) }}>{item.action.label}</button>}
    </div>
    <button type="button" className="notification-close" aria-label="알림 닫기" onClick={() => onDismiss(item.id)}>×</button>
  </section>
}
