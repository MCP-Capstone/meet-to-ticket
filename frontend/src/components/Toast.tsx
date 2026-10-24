import { useEffect, useRef } from 'react'

interface Props {
  message: string | null
  onDone: () => void
}

// 저장 완료 같은 짧은 성공 안내는 화면 흐름을 밀어내지 않게 오른쪽 아래에 잠깐 띄운다. 오류는 이 컴포넌트를 쓰지 않는다.
export function Toast({ message, onDone }: Props) {
  // 부모가 매번 새 함수를 넘겨도 타이머가 다시 시작되지 않게 마지막 콜백만 기억한다.
  const done = useRef(onDone)
  useEffect(() => { done.current = onDone })
  useEffect(() => {
    if (!message) return
    const timer = window.setTimeout(() => done.current(), 3500)
    return () => window.clearTimeout(timer)
  }, [message])
  return <div className="toast-region" role="status" aria-live="polite">
    {message && <div className="toast"><span>{message}</span><button type="button" className="toast-close" aria-label="안내 닫기" onClick={onDone}>×</button></div>}
  </div>
}
