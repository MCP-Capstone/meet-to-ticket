import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { ConnectionStatus, ProjectSummary } from '../api'
import { Spinner } from './Spinner'
import { Toast } from './Toast'

interface Props {
  projects: ProjectSummary[]
  loading: boolean
  error: string | null
  onReload: () => void
  onCreate: () => void
  onEdit: (projectId: number) => void
  onDeleted: (projectId: number) => void
  onBusyChange: (busy: boolean) => void
}

function Connection({ tool, status }: { tool: string; status: ConnectionStatus | null }) {
  const label = status === 'CONNECTED' ? '연결됨' : status === 'NOT_CONNECTED' ? '미연결' : '권한 확인 필요'
  const kind = status === 'CONNECTED' ? 'connected' : status === 'NOT_CONNECTED' ? 'disconnected' : 'unchecked'
  return <span className={`project-connection ${kind}`} title={status === 'ERROR' ? '연결 확인에 실패했습니다. 프로젝트 설정을 확인해 주세요.' : undefined}>{tool} {label}</span>
}

export function ProjectList({ projects, loading, error, onReload, onCreate, onEdit, onDeleted, onBusyChange }: Props) {
  const [deleting, setDeleting] = useState<ProjectSummary | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const createButton = useRef<HTMLButtonElement>(null)

  return <section className="project-list" aria-labelledby="project-list-title">
    <div className="project-list-heading">
      <h2 id="project-list-title">프로젝트</h2>
      <button ref={createButton} type="button" className="primary" disabled={loading} onClick={onCreate}>새 프로젝트</button>
    </div>
    <Toast message={notice} onDone={() => setNotice(null)} />
    {error && <div role="alert"><p className="error">{error}</p><button type="button" disabled={loading} onClick={onReload}>다시 불러오기</button></div>}
    {loading ? <Spinner label="프로젝트를 불러오는 중" /> : !error && <>
      {projects.length === 0 ? <div className="card project-empty"><h3>아직 소속된 프로젝트가 없어요</h3><p className="muted">새 프로젝트를 만들거나 프로젝트 관리자에게 멤버 추가를 요청하세요.</p></div> :
        <div className="card project-list-table-wrap">
          <table className="project-list-table">
            <caption className="sr-only">소속 프로젝트의 저장소, 도구 연결 상태, 멤버 수, 최근 회의와 관리 기능</caption>
            <thead><tr><th scope="col">이름</th><th scope="col">GitHub 저장소</th><th scope="col">연결</th><th scope="col">멤버</th><th scope="col">최근 회의</th><th scope="col">관리</th></tr></thead>
            <tbody>{projects.map((project) => <tr key={project.projectId}>
              <th scope="row">{project.name}</th>
              <td data-label="저장소" className="project-repository">{project.githubOwner}/{project.githubRepo}</td>
              <td data-label="도구 연결"><div className="project-connections"><Connection tool="GitHub" status={project.connections.github} /><Connection tool="Notion" status={project.connections.notion} /><Connection tool="Slack" status={project.connections.slack} /></div></td>
              <td data-label="멤버">{project.memberCount}명</td>
              <td data-label="최근 회의" className="project-date">{project.lastMeetingDate ? <time dateTime={project.lastMeetingDate}>{project.lastMeetingDate}</time> : <span className="muted">없음</span>}</td>
              <td data-label="관리">{project.canEdit ? <div className="project-row-actions"><button type="button" aria-label={`${project.name} 편집`} onClick={() => onEdit(project.projectId)}>편집</button><button type="button" className="danger" aria-label={`${project.name} 삭제`} onClick={() => { setNotice(null); setDeleting(project) }}>삭제</button></div> : <span className="muted">관리자만 관리</span>}</td>
            </tr>)}</tbody>
          </table>
        </div>}
      <p className="muted">회의록을 올릴 때 고른 프로젝트의 저장소, 멤버, 기존 이슈, 문서를 맥락으로 사용해요.</p>
    </>}
    {deleting && <DeleteProjectDialog project={deleting} onCancel={() => setDeleting(null)} onBusyChange={onBusyChange} onDeleted={() => {
      onDeleted(deleting.projectId)
      setNotice(`${deleting.name} 프로젝트를 삭제했습니다.`)
      setDeleting(null)
      createButton.current?.focus()
    }} />}
  </section>
}

function DeleteProjectDialog({ project, onCancel, onDeleted, onBusyChange }: {
  project: ProjectSummary
  onCancel: () => void
  onDeleted: () => void
  onBusyChange: (busy: boolean) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const lock = useRef(false)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])

  const remove = async (event: React.SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (lock.current || name.trim() !== project.name) return
    lock.current = true
    setBusy(true)
    onBusyChange(true)
    setError(null)
    try {
      await api.deleteProject(project.projectId, name.trim())
      onDeleted()
    } catch (e) {
      setError(e instanceof Error ? e.message : '프로젝트를 삭제하지 못했습니다.')
    } finally {
      lock.current = false
      setBusy(false)
      onBusyChange(false)
    }
  }

  return <dialog ref={dialog} className="project-delete-dialog" aria-labelledby="project-delete-title" aria-describedby="project-delete-description" onCancel={(event) => { event.preventDefault(); if (!lock.current) onCancel() }}>
    <form onSubmit={remove}>
      <h2 id="project-delete-title">{project.name} 프로젝트를 삭제할까요?</h2>
      <ul id="project-delete-description">
        <li>회의 처리 기록과 티켓(검토 대기 포함), 멤버 정보가 삭제돼요.</li>
        <li>이미 발행한 GitHub 이슈, Slack 메시지, Notion 페이지는 그대로 남아요.</li>
      </ul>
      <label htmlFor="project-delete-name">확인을 위해 프로젝트 이름을 입력하세요</label>
      <input autoFocus id="project-delete-name" autoComplete="off" value={name} placeholder={project.name} disabled={busy} onChange={(event) => setName(event.target.value)} />
      {error && <p className="error" role="alert">{error}</p>}
      <div className="project-delete-actions"><button type="button" disabled={busy} onClick={onCancel}>취소</button><button type="submit" className="danger" disabled={busy || name.trim() !== project.name}>{busy ? '삭제 중...' : '삭제'}</button></div>
    </form>
  </dialog>
}
