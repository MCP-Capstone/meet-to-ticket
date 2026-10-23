import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import type { ProjectReindex } from '../api'
import { Spinner } from './Spinner'

interface Props {
  projectId: number
  canEdit: boolean
}

const labels = {
  RUNNING: '재색인 중',
  SUCCEEDED: '최신 색인 완료',
  FAILED: '재색인 실패',
} as const

const message = (e: unknown) => e instanceof Error ? e.message : '재색인 상태를 확인하지 못했습니다.'

const displayTime = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR') : '아직 실행하지 않음'

/** 프로젝트 설정에서만 보이는 RAG 색인 제어. 실행 중인 경우에만 짧게 폴링한다. */
export function ProjectReindex({ projectId, canEdit }: Props) {
  const [data, setData] = useState<ProjectReindex | null>(null)
  const [loading, setLoading] = useState(true)
  const [requesting, setRequesting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const result = await api.getProjectReindex(projectId)
      setData(result)
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
  return (
    <section className="card project-reindex" aria-labelledby="reindex-title">
      <div className="card-header">
        <div>
          <h2 id="reindex-title">기존 이슈 색인</h2>
          <p className="muted">GitHub 이슈 전체를 다시 읽어 중복 이슈 판단용 색인을 갱신합니다.</p>
        </div>
        {data?.status && <span className={`badge ${data.status === 'FAILED' ? 'failed' : data.status === 'SUCCEEDED' ? 'linked' : 'mock'}`}>
          {labels[data.status]}
        </span>}
      </div>
      {loading ? <p className="inline-status"><Spinner size="sm" label="색인 상태 불러오는 중" /> 색인 상태를 불러오는 중...</p> : <>
        <p className="muted">마지막 완료: {displayTime(data?.lastSyncedAt ?? null)}</p>
        {data?.status === 'SUCCEEDED' && <p className="project-success" role="status">
          이슈 {data.indexCount ?? data.total ?? 0}건 색인됨{data.indexed !== null && data.indexed !== undefined ? ` · 이번 갱신 ${data.indexed}건` : ''}
        </p>}
        {running && <p className="inline-status" role="status"><Spinner size="sm" label="재색인 중" /> {data.progressMessage ?? 'GitHub 이슈를 다시 색인하고 있습니다.'}</p>}
        {data?.status === 'FAILED' && <p className="error" role="alert">{data.errorMessage ?? '재색인에 실패했습니다.'}</p>}
        {error && <p className="error" role="alert">{error}</p>}
      </>}
      <div className="project-actions">
        <button type="button" className="secondary" onClick={() => void load()} disabled={loading || requesting}>상태 새로고침</button>
        {canEdit && <button type="button" className="primary" onClick={() => void request()} disabled={loading || requesting || running}>
          {requesting || running ? <><Spinner size="sm" label="재색인 요청 중" /> {running ? '재색인 중...' : '요청 중...'}</> : '다시 색인'}
        </button>}
      </div>
    </section>
  )
}
