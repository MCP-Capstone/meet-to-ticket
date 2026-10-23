import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent } from 'react'
import { api } from '../api'
import type { ConnectionStatus, MeetingAnalysisInput, Project, ProjectDetail } from '../api'
import { readMeetingFile, validateMeetingText, validMeetingDate } from '../meetingInput'
import { sampleMeetingFileName, sampleMeetingText } from '../mocks/data'
import { Spinner } from './Spinner'

interface Props {
  projects: Project[]
  projectId: number | null
  projectsLoading: boolean
  onProjectChange: (projectId: number) => void
  starting: boolean
  onStart: (input: MeetingAnalysisInput) => Promise<void>
}

const toMessage = (e: unknown) => e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.'
const connectionLabel = (status: ConnectionStatus | null | undefined) =>
  status === 'CONNECTED' ? '연결됨' : status === 'NOT_CONNECTED' ? '미연결' : status === 'ERROR' ? '연결 확인 실패' : '확인되지 않음'
const countLabel = (count: number | null | undefined) =>
  typeof count === 'number' && Number.isSafeInteger(count) && count >= 0 ? `${count}개` : '확인되지 않음'

function ProjectSummary({ projectId }: { projectId: number }) {
  const [detail, setDetail] = useState<ProjectDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let cancelled = false
    api.getProject(projectId).then((result) => {
      if (!cancelled) setDetail(result)
    }).catch((e: unknown) => {
      if (!cancelled) setError(toMessage(e))
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [projectId, attempt])

  if (loading) return <p className="inline-status" role="status"><Spinner size="sm" label="프로젝트 요약 조회 중" /> 프로젝트 정보를 불러오고 있습니다.</p>
  if (error) return <div className="upload-context-error">
    <p className="notice">프로젝트 요약을 불러오지 못했습니다. {error}</p>
    <button type="button" className="link" onClick={() => { setLoading(true); setError(null); setAttempt((value) => value + 1) }}>요약 다시 불러오기</button>
  </div>
  if (!detail) return null
  return <dl className="upload-project-summary" aria-label="선택한 프로젝트 요약">
    <div><dt>GitHub 저장소</dt><dd>{detail.githubOwner}/{detail.githubRepo} · {connectionLabel(detail.connections.github)}</dd></div>
    <div><dt>기존 이슈 수</dt><dd>{countLabel(detail.existingIssueCount)}</dd></div>
    <div><dt>멤버 수</dt><dd>{detail.members.length}명</dd></div>
    {detail.connections.notion === 'CONNECTED' && <div><dt>Notion 문서 수</dt><dd>{countLabel(detail.notionDocumentCount)}</dd></div>}
    {detail.notionPageUrl && detail.connections.notion !== 'CONNECTED' && <div><dt>Notion</dt><dd>{connectionLabel(detail.connections.notion)}</dd></div>}
    <div><dt>Slack 채널</dt><dd>{detail.slackChannelId ? `${detail.slackChannelId} · ${connectionLabel(detail.connections.slack)}` : '미연결'}</dd></div>
  </dl>
}

export function UploadForm({ projects, projectId, projectsLoading, onProjectChange, starting, onStart }: Props) {
  const [mode, setMode] = useState<'file' | 'text'>('file')
  const [meetingDate, setMeetingDate] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState('')
  const [content, setContent] = useState('')
  const [reading, setReading] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fileRead = useRef(0)
  const busy = starting || reading

  const handleFile = async (selected: File) => {
    if (starting) return
    const token = ++fileRead.current
    setError(null)
    setFile(null)
    setPreview('')
    setReading(true)
    try {
      const text = await readMeetingFile(selected)
      if (token !== fileRead.current) return
      setFile(selected)
      setPreview(text.split('\n').slice(0, 12).join('\n'))
    } catch (e) {
      if (token === fileRead.current) setError(toMessage(e))
    } finally {
      if (token === fileRead.current) setReading(false)
    }
  }

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0]
    e.target.value = ''
    if (selected) void handleFile(selected)
  }
  const handleDrop = (e: DragEvent<HTMLLabelElement>) => {
    e.preventDefault()
    setDragOver(false)
    if (busy) return
    const selected = e.dataTransfer.files[0]
    if (selected) void handleFile(selected)
  }
  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    setError(null)
    if (projectId === null) { setError('프로젝트를 선택해 주세요.'); return }
    if (!validMeetingDate(meetingDate)) { setError('회의 날짜를 올바르게 선택해 주세요.'); return }
    if (mode === 'file') {
      if (!file) { setError('회의록 파일을 선택해 주세요.'); return }
      await onStart({ meetingDate, file })
    } else {
      const invalid = validateMeetingText(content)
      if (invalid) { setError(invalid); return }
      await onStart({ meetingDate, content })
    }
  }

  return <form className="card upload-form" onSubmit={handleSubmit}>
    <div className="card-header"><div>
      <h2>회의록 업로드</h2>
      <p className="muted">프로젝트와 회의 날짜를 선택하고, 파일을 올리거나 회의록을 붙여넣어 주세요.</p>
    </div></div>
    <fieldset className="upload-fields" disabled={starting}>
      <div className="row upload-settings">
        <label>프로젝트 (필수)
          <select value={projectId ?? ''} onChange={(e) => { if (e.target.value) { onProjectChange(Number(e.target.value)); setError(null) } }} disabled={projectsLoading || busy} required>
            <option value="" disabled>{projectsLoading ? '프로젝트 불러오는 중' : '프로젝트를 선택하세요'}</option>
            {projects.map((project) => <option key={project.projectId} value={project.projectId}>{project.name}</option>)}
          </select>
        </label>
        <label>회의 날짜 (필수)
          <input type="date" value={meetingDate} onChange={(e) => { setMeetingDate(e.target.value); setError(null) }} required />
        </label>
      </div>
      {!projectsLoading && projects.length === 0 && <p className="notice">참여 중인 프로젝트가 없습니다. 프로젝트 설정에서 먼저 등록해 주세요.</p>}
      {projectId !== null && <ProjectSummary key={projectId} projectId={projectId} />}
      <div className="tabs" role="tablist" aria-label="회의록 입력 방식">
        <button type="button" role="tab" id="meeting-file-tab" aria-selected={mode === 'file'} aria-controls="meeting-file-panel" className={mode === 'file' ? 'active' : ''} disabled={busy} onClick={() => { setMode('file'); setError(null) }}>파일 업로드</button>
        <button type="button" role="tab" id="meeting-text-tab" aria-selected={mode === 'text'} aria-controls="meeting-text-panel" className={mode === 'text' ? 'active' : ''} disabled={busy} onClick={() => { setMode('text'); setError(null) }}>텍스트 붙여넣기</button>
      </div>
      {mode === 'file' ? <div id="meeting-file-panel" role="tabpanel" aria-labelledby="meeting-file-tab" className="upload-panel">
        <label className={`dropzone ${dragOver ? 'over' : ''} ${busy ? 'disabled' : ''}`}
          onDragOver={(e) => { e.preventDefault(); if (!busy) setDragOver(true) }} onDragLeave={() => setDragOver(false)} onDrop={handleDrop}>
          <input type="file" className="sr-only" aria-label="회의록 파일 선택" accept=".md,.txt,text/markdown,text/plain" onChange={handleChange} disabled={busy} />
          <strong>{file ? '다른 파일 선택' : 'MD/TXT 파일을 끌어놓거나 클릭해서 선택하세요'}</strong>
          <span className="muted">UTF-8 · 최대 10MB · 파일을 고른 뒤 티켓 추출을 눌러 주세요.</span>
        </label>
        <button type="button" className="link" disabled={busy} onClick={() => { void handleFile(new File([sampleMeetingText], sampleMeetingFileName, { type: 'text/plain' })) }}>샘플 파일 사용</button>
        {reading && <p className="inline-status" role="status"><Spinner size="sm" label="파일 확인 중" /> 파일을 확인하고 있습니다.</p>}
        {file && <div className="file">
          <div className="file-name"><strong>{file.name}</strong><span className="muted">{file.size < 1024 ? `${file.size}B` : `${(file.size / 1024).toFixed(1)}KB`}</span>
            <button type="button" className="link" disabled={busy} onClick={() => { ++fileRead.current; setFile(null); setPreview(''); setError(null) }}>파일 제거</button>
          </div>
          <pre className="preview">{preview}</pre>
        </div>}
      </div> : <div id="meeting-text-panel" role="tabpanel" aria-labelledby="meeting-text-tab" className="upload-panel">
        <label>회의록 내용
          <textarea rows={12} value={content} placeholder="회의록 요약본을 붙여넣어 주세요." onChange={(e) => { setContent(e.target.value); setError(null) }} />
        </label>
        <p className="muted">회의록 안에 회의 날짜나 참석자 줄이 없어도 됩니다.</p>
      </div>}
    </fieldset>
    {error && <p className="error" role="alert">{error}</p>}
    <button type="submit" className="primary" disabled={busy || projectsLoading || projectId === null || !meetingDate || (mode === 'file' ? !file : !content.trim())}>
      {starting ? <><Spinner size="sm" label="회의록 처리 요청 중" /> 처리 요청 중...</> : '티켓 추출'}
    </button>
  </form>
}
