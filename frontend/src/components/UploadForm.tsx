import { useRef, useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent } from 'react'
import type { MeetingAnalysisInput, Project } from '../api'
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
export function UploadForm({ projects, projectId, projectsLoading, onProjectChange, starting, onStart }: Props) {
  const [meetingDate, setMeetingDate] = useState('')
  const [file, setFile] = useState<File | null>(null)
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
    setReading(true)
    try {
      const text = await readMeetingFile(selected)
      if (token !== fileRead.current) return
      setFile(selected)
      // 첨부한 텍스트를 원문 입력란에 채워 사용자가 분석 전에 직접 고칠 수 있게 한다.
      setContent(text)
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
    const invalid = validateMeetingText(content)
    if (invalid) { setError(invalid); return }
    await onStart({ meetingDate, content })
  }

  const reset = () => {
    ++fileRead.current
    setMeetingDate('')
    setFile(null)
    setContent('')
    setError(null)
  }

  return <form className="card upload-form" onSubmit={handleSubmit}>
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
      <div className="upload-panel">
        <label className={`dropzone ${dragOver ? 'over' : ''} ${busy ? 'disabled' : ''}`}
          onDragOver={(e) => { e.preventDefault(); if (!busy) setDragOver(true) }} onDragLeave={() => setDragOver(false)} onDrop={handleDrop}>
          <input type="file" className="sr-only" aria-label="회의록 파일 선택" accept=".md,.txt,text/markdown,text/plain" onChange={handleChange} disabled={busy} />
          <strong>{file ? '다른 파일 선택' : '회의록 파일을 끌어놓거나 클릭해서 선택하세요'}</strong>
          <span className="muted">첨부한 텍스트는 아래 원문 입력란에 채워집니다. 직접 입력도 가능합니다.</span>
        </label>
        <button type="button" className="link" disabled={busy} onClick={() => { void handleFile(new File([sampleMeetingText], sampleMeetingFileName, { type: 'text/plain' })) }}>샘플 파일 사용</button>
        {reading && <p className="inline-status" role="status"><Spinner size="sm" label="파일 확인 중" /> 파일을 확인하고 있습니다.</p>}
        {file && <div className="file">
          <div className="file-name"><strong>{file.name}</strong><span className="muted">{file.size < 1024 ? `${file.size}B` : `${(file.size / 1024).toFixed(1)}KB`}</span>
            <button type="button" className="link" disabled={busy} onClick={() => { ++fileRead.current; setFile(null); setError(null) }}>파일 제거</button>
          </div>
        </div>}
        <label>회의록 원문
          <textarea rows={12} value={content} placeholder="회의록 내용을 입력하거나 첨부 파일에서 추출한 내용을 수정하세요." onChange={(e) => { setContent(e.target.value); setError(null) }} />
        </label>
      </div>
    </fieldset>
    {error && <p className="error" role="alert">{error}</p>}
    <div className="upload-actions">
      <button type="button" className="secondary" disabled={busy} onClick={reset}>초기화</button>
      <button type="submit" className="primary" disabled={busy || projectsLoading || projectId === null || !meetingDate || !content.trim()}>
        {starting ? <><Spinner size="sm" label="회의록 처리 요청 중" /> 처리 요청 중...</> : '분석 시작'}
      </button>
    </div>
  </form>
}
