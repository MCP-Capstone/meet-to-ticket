import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiRequestError } from '../api'
import type { ConnectionStatus, DirectoryUser, ProjectDetail, ProjectEditorMember, ProjectSaveRequest } from '../api'
import { Spinner } from './Spinner'

interface Props {
  projectId: number | null
  onSaved: (project: ProjectDetail) => void
  onCancel: () => void
  onChange: () => void
  onSavingChange: (saving: boolean) => void
}

type SelectedMember = DirectoryUser & { repositoryPermission?: ProjectEditorMember['repositoryPermission'] }
const connectionLabels: Record<ConnectionStatus, string> = {
  CONNECTED: '연결됨', NOT_CONNECTED: '미연결', ERROR: '연결 확인 실패',
}
const permissionLabels = { GRANTED: '권한 있음', DENIED: '권한 없음', NO_ACCOUNT: '계정 없음' }
const toMessage = (e: unknown) => e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.'

export function ProjectEditor({ projectId, onSaved, onCancel, onChange, onSavingChange }: Props) {
  const [original, setOriginal] = useState<ProjectDetail | null>(null)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [repository, setRepository] = useState('')
  const [notionPageUrl, setNotionPageUrl] = useState('')
  const [slackChannelId, setSlackChannelId] = useState('')
  const [members, setMembers] = useState<SelectedMember[]>([])
  const [keyword, setKeyword] = useState('')
  const [users, setUsers] = useState<DirectoryUser[]>([])
  const [loading, setLoading] = useState(projectId !== null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loadAttempt, setLoadAttempt] = useState(0)
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [searchAttempt, setSearchAttempt] = useState(0)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [permissionDenied, setPermissionDenied] = useState(false)
  const submitLock = useRef(false)
  const canEdit = !permissionDenied && (projectId === null || original?.canEdit === true)

  useEffect(() => {
    if (projectId === null) return
    let cancelled = false
    api.getProject(projectId).then((detail) => {
      if (cancelled) return
      setOriginal(detail)
      setName(detail.name)
      setDescription(detail.description ?? '')
      setRepository(`${detail.githubOwner}/${detail.githubRepo}`)
      setNotionPageUrl(detail.notionPageUrl ?? '')
      setSlackChannelId(detail.slackChannelId ?? '')
      setMembers(detail.members)
    }).catch((e: unknown) => {
      if (!cancelled) setLoadError(toMessage(e))
    }).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => { cancelled = true }
  }, [projectId, loadAttempt])

  useEffect(() => {
    if (loading || loadError || !canEdit) return
    let cancelled = false
    const timer = setTimeout(() => {
      setSearching(true)
      setSearchError(null)
      setUsers([])
      api.searchUsers(keyword.trim()).then((result) => {
        if (!cancelled) setUsers(result.users)
      }).catch((e: unknown) => {
        if (!cancelled) setSearchError(toMessage(e))
      }).finally(() => {
        if (!cancelled) setSearching(false)
      })
    }, 300)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [keyword, loading, loadError, canEdit, searchAttempt])

  const markChanged = () => { onChange(); setSaveError(null) }
  const repositoryChanged = original === null || repository.trim() !== `${original.githubOwner}/${original.githubRepo}`
  const visibleConnection = (tool: 'github' | 'notion' | 'slack') => {
    const value = tool === 'github' ? repository.trim() : tool === 'notion' ? notionPageUrl.trim() : slackChannelId.trim()
    if (!value) return '미입력'
    const previous = tool === 'github' ? `${original?.githubOwner}/${original?.githubRepo}`
      : tool === 'notion' ? original?.notionPageUrl : original?.slackChannelId
    if (!original || value !== previous) return '저장 후 확인'
    return connectionLabels[original.connections[tool]]
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (submitLock.current || !canEdit) return
    setSaveError(null)
    onChange()
    const parts = repository.trim().split('/')
    if (!name.trim()) { setSaveError('프로젝트 이름을 입력해 주세요.'); return }
    if (name.trim().length > 100) { setSaveError('프로젝트 이름은 100자 이하로 입력해 주세요.'); return }
    if (parts.length !== 2 || parts.some((part) => !part || /\s/.test(part) || part.length > 100)) {
      setSaveError('GitHub 저장소는 소유자/저장소 형식으로 입력해 주세요. 예: MCP-Capstone/mcp-capstone')
      return
    }
    const body: ProjectSaveRequest = {
      name: name.trim(), description: description.trim() || null,
      githubOwner: parts[0], githubRepo: parts[1],
      notionPageUrl: notionPageUrl.trim() || null, slackChannelId: slackChannelId.trim() || null,
      memberUserIds: members.map((member) => member.userId),
    }
    submitLock.current = true
    setSaving(true)
    onSavingChange(true)
    try {
      const detail = projectId === null ? await api.createProject(body) : await api.updateProject(projectId, body)
      setOriginal(detail)
      setName(detail.name)
      setDescription(detail.description ?? '')
      setRepository(`${detail.githubOwner}/${detail.githubRepo}`)
      setNotionPageUrl(detail.notionPageUrl ?? '')
      setSlackChannelId(detail.slackChannelId ?? '')
      setMembers(detail.members)
      onSaved(detail)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 403) setPermissionDenied(true)
      setSaveError(toMessage(e))
    } finally {
      submitLock.current = false
      setSaving(false)
      onSavingChange(false)
    }
  }

  if (loading) return <p className="loading"><Spinner size="sm" label="프로젝트 불러오는 중" /> 프로젝트 정보를 불러오고 있습니다.</p>
  if (loadError) return (
    <section className="card">
      <p className="error" role="alert">{loadError}</p>
      <div className="project-actions">
        <button type="button" className="secondary" onClick={onCancel}>돌아가기</button>
        <button type="button" className="primary" onClick={() => { setLoading(true); setLoadError(null); setLoadAttempt((value) => value + 1) }}>다시 불러오기</button>
      </div>
    </section>
  )

  const candidates = users.filter((user) => !members.some((member) => member.userId === user.userId))
  return (
    <form className="project-editor" onSubmit={handleSubmit}>
      <div className="card-header">
        <div>
          <h2>{projectId === null ? '새 프로젝트' : '프로젝트 편집'}</h2>
          <p className="muted">프로젝트 정보와 연결할 도구, 함께할 멤버를 설정하세요.</p>
        </div>
      </div>
      {!canEdit && <p className="notice" role="status">프로젝트 관리자만 정보를 수정할 수 있습니다. 현재는 읽기 전용입니다.</p>}
      {saveError && <p className="error" role="alert">{saveError}</p>}
      <fieldset className="project-fields" disabled={saving || !canEdit}>
        <section className="card">
          <h2>기본 정보</h2>
          <label>프로젝트 이름 (필수)
            <input value={name} maxLength={100} onChange={(e) => { setName(e.target.value); markChanged() }} required />
          </label>
          <label>설명
            <textarea value={description} rows={3} onChange={(e) => { setDescription(e.target.value); markChanged() }} />
          </label>
        </section>
        <section className="card">
          <h2>도구 연결 정보</h2>
          <p className="muted">연결 상태와 멤버의 저장소 권한은 저장 후 확인됩니다.</p>
          <div className="project-tool">
            <div className="card-header"><strong>GitHub</strong><span className="badge mock">{visibleConnection('github')}</span></div>
            <label>GitHub 저장소 (필수)
              <input value={repository} placeholder="MCP-Capstone/mcp-capstone" onChange={(e) => { setRepository(e.target.value); markChanged() }} required />
            </label>
          </div>
          <div className="project-tool">
            <div className="card-header"><strong>Notion</strong><span className="badge mock">{visibleConnection('notion')}</span></div>
            <label>Notion 기획 페이지 URL (선택)
              <input type="url" value={notionPageUrl} placeholder="https://www.notion.so/..." onChange={(e) => { setNotionPageUrl(e.target.value); markChanged() }} />
            </label>
          </div>
          <div className="project-tool">
            <div className="card-header"><strong>Slack</strong><span className="badge mock">{visibleConnection('slack')}</span></div>
            <label>Slack 채널 ID (선택)
              <input value={slackChannelId} placeholder="예: C0123456789" onChange={(e) => { setSlackChannelId(e.target.value); markChanged() }} />
            </label>
          </div>
        </section>
        <section className="card">
          <h2>프로젝트 멤버 <span className="muted">{members.length}명</span></h2>
          {members.length === 0 ? <p className="muted">선택한 멤버가 없습니다. 아래에서 가입한 사용자를 추가하세요.</p> : (
            <div className="project-table-wrap">
              <table className="project-member-table">
                <caption className="sr-only">선택한 프로젝트 멤버와 저장소 권한</caption>
                <thead><tr><th scope="col">이름</th><th scope="col">GitHub 계정</th><th scope="col">저장소 권한</th><th scope="col">선택</th></tr></thead>
                <tbody>{members.map((member) => (
                  <tr key={member.userId}>
                    <td>{member.name}</td><td>{member.githubName ?? '계정 없음'}</td>
                    <td>{!member.githubName ? '계정 없음' : repositoryChanged || !member.repositoryPermission ? '저장 후 확인' : permissionLabels[member.repositoryPermission]}</td>
                    <td><button type="button" className="link" aria-label={`${member.name} 멤버 제거`} onClick={() => {
                      setMembers((selected) => selected.filter((item) => item.userId !== member.userId)); markChanged()
                    }}>제거</button></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
          {canEdit && <>
            <label>가입 사용자 검색
              <input value={keyword} placeholder="이름 또는 GitHub 계정"
                onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault() }}
                onChange={(e) => { setKeyword(e.target.value); setSearching(true); setSearchError(null); setUsers([]) }} />
            </label>
            {searching ? <p className="inline-status" role="status"><Spinner size="sm" label="사용자 검색 중" /> 사용자를 검색하고 있습니다.</p>
              : searchError ? <div><p className="error" role="alert">{searchError}</p><button type="button" className="link" onClick={() => setSearchAttempt((value) => value + 1)}>검색 다시 시도</button></div>
              : candidates.length === 0 ? <p className="muted" role="status">추가할 수 있는 사용자가 없습니다.</p>
              : <ul className="project-candidates" aria-label="추가 가능한 사용자">{candidates.map((user) => (
                <li key={user.userId}><span><strong>{user.name}</strong> <span className="muted">{user.githubName ?? 'GitHub 계정 없음'}</span></span>
                  <button type="button" className="secondary" aria-label={`${user.name} 멤버 추가`} onClick={() => {
                    setMembers((selected) => selected.some((member) => member.userId === user.userId) ? selected : [...selected, user]); markChanged()
                  }}>추가</button>
                </li>
              ))}</ul>}
          </>}
        </section>
      </fieldset>
      <div className="project-actions">
        <button type="button" className="secondary" onClick={onCancel} disabled={saving}>취소</button>
        <button type="submit" className="primary" disabled={saving || !canEdit}>
          {saving ? <><Spinner size="sm" label="프로젝트 저장 중" /> 저장 중...</> : '저장'}
        </button>
      </div>
    </form>
  )
}
