import { useEffect, useRef, useState } from 'react'
import { api, ApiRequestError } from '../api'
import type { AnalysisTask } from '../api'

export const POLL_INTERVAL_MS = 2000

// 네트워크 오류가 이 횟수만큼 연속되면 조회를 멈추고 안내한다.
const MAX_CONSECUTIVE_FAILURES = 3

const isFinished = (task: AnalysisTask) =>
  task.status === 'COMPLETED' || task.status === 'PARTIALLY_FAILED' || task.status === 'FAILED'

// 분석 작업 상태를 2초마다 조회한다. 끝나면(COMPLETED / PARTIALLY_FAILED / FAILED) onFinish를 한 번 호출하고 멈춘다.
// 화면을 벗어나거나 taskId가 바뀌면 조회를 정리한다. 조회 반복은 진행 확인이지 재시도가 아니다.
export function useAnalysisPolling(taskId: string | null, onFinish: (task: AnalysisTask) => void) {
  const [task, setTask] = useState<AnalysisTask | null>(null)
  const [error, setError] = useState<string | null>(null)

  // 화면이 다시 그려져도 조회를 처음부터 다시 시작하지 않도록 최신 콜백은 ref로 들고 있는다.
  const onFinishRef = useRef(onFinish)
  useEffect(() => {
    onFinishRef.current = onFinish
  })

  useEffect(() => {
    if (taskId === null) return

    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let failures = 0

    const tick = async () => {
      try {
        const next = await api.getAnalysis(taskId)
        if (cancelled) return
        failures = 0
        setTask(next)
        setError(null)
        if (isFinished(next)) {
          onFinishRef.current(next)
          return
        }
      } catch (e) {
        if (cancelled) return
        // 로그인 만료는 App이 로그인 화면으로 보낸다.
        if (e instanceof ApiRequestError && e.code === 'UNAUTHORIZED') return
        // 작업이 없는 등 서버가 거절한 요청은 다시 물어도 같으므로 바로 멈춘다.
        if (e instanceof ApiRequestError && e.status < 500) {
          setError(e.message)
          return
        }
        failures += 1
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
          setError('서버와 연결이 원활하지 않아 진행 상황을 확인할 수 없습니다.')
          return
        }
      }
      timer = setTimeout(tick, POLL_INTERVAL_MS)
    }

    void tick()

    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [taskId])

  return { task, error }
}
