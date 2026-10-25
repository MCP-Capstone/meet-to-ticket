import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import type { ProjectReindex as Reindex } from '../api'
import { Spinner } from './Spinner'

interface Props {
  projectId: number
  canEdit: boolean
}

const message = (e: unknown) => e instanceof Error ? e.message : '기존 이슈 상태를 확인하지 못했습니다.'
const displayTime = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : null

/**
 * 중복 확인에 쓰는 기존 GitHub 이슈 목록(RAG 색인)을 전부 다시 불러온다.
 * 분석할 때마다 바뀐 이슈는 자동으로 반영되므로, GitHub에서 이슈를 지우거나 옮겨 결과가 어긋날 때만 쓴다.
 */
export function ProjectReindex({ projectId, canEdit }: Props) {
  const [data, setData] = useState<Reindex | null>(null)
  const [loading, setLoading] = useState(true)
  const [requesting, setRequesting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setData(await api.getProjectReindex(projectId))
      setError(null)
    } catch (e) {
      setError(message(e))
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    // 렌더 직후 동기 setState를 피하고, 요청 완료 시점에만 상태를 갱신한다.
    const timer = window.setTimeout(() => { void load() }, 0)
    return () => window.clearTimeout(timer)
  }, [load])

  useEffect(() => {
    if (data?.status !== 'RUNNING') return
    const timer = window.setInterval(() => { void load() }, 1500)
    return () => window.clearInterval(timer)
  }, [data?.status, load])

  const request = async () => {
    if (!canEdit || requesting || data?.status === 'RUNNING') return
    setRequesting(true)
    setError(null)
    try {
      setData(await api.requestProjectReindex(projectId))
    } catch (e) {
      setError(message(e))
    } finally {
      setRequesting(false)
    }
  }

  const running = data?.status === 'RUNNING'
  const count = data?.indexCount ?? data?.total ?? null
  const lastSynced = displayTime(data?.lastSyncedAt ?? null)
  return (
    <div className="project-reindex" role="group" aria-labelledby="reindex-title">
      <div className="project-reindex-row">
        <div>
          <strong id="reindex-title">기존 이슈</strong>
          <p className="muted">
            {loading ? '불러오는 중...'
              : running ? <span className="inline-status" role="status"><Spinner size="sm" label="기존 이슈 불러오는 중" /> 기존 이슈를 다시 불러오고 있어요.</span>
                : lastSynced ? `${count !== null ? `${count}건 · ` : ''}${lastSynced} 기준` : '아직 불러오지 않았어요.'}
          </p>
        </div>
        {canEdit && <button type="button" className="secondary" onClick={() => void request()} disabled={loading || requesting || running}>
          {requesting ? '요청 중...' : '다시 불러오기'}
        </button>}
      </div>
      <p className="muted hint">분석할 때마다 바뀐 이슈는 자동으로 반영돼요. GitHub에서 이슈를 지우거나 옮긴 뒤 결과가 어긋날 때만 눌러 주세요.</p>
      {data?.status === 'FAILED' && <p className="error" role="alert">{data.errorMessage ?? '기존 이슈를 불러오지 못했습니다.'}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  )
}
