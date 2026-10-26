import { useRef, useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent } from 'react'
import type { MeetingAnalysisInput, Project } from '../api'
import { meetingDateFromText, readMeetingFile, validateMeetingText, validMeetingDate } from '../meetingInput'
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
  // 회의록에서 찾아 채운 날짜인지. 사용자가 직접 고르면 회의록을 바꿔도 덮어쓰지 않는다.
  const [dateFromText, setDateFromText] = useState(false)
  const [content, setContent] = useState('')
  // 파일에서 불러온 내용을 그대로 보내면 파일로 올려 파일 이름을 회의 제목 후보로 남긴다. 고치면 텍스트로 보낸다.
  const [loaded, setLoaded] = useState<{ file: File; text: string } | null>(null)
  const [reading, setReading] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const fileRead = useRef(0)
  const busy = starting || reading

  const changeContent = (text: string) => {
    setContent(text)
    if (meetingDate && !dateFromText) return
    const found = meetingDateFromText(text)
    setMeetingDate(found ?? '')
    setDateFromText(Boolean(found))
  }

  const handleFile = async (selected: File) => {
    if (starting) return
    if (content.trim() && content !== loaded?.text && !window.confirm('입력한 회의록을 파일 내용으로 바꿀까요?')) return
    const token = ++fileRead.current
    setError(null)
    setReading(true)
    try {
      const text = await readMeetingFile(selected)
      if (token !== fileRead.current) return
      setLoaded({ file: selected, text })
      changeContent(text)
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
  const handleDrop = (e: DragEvent<HTMLElement>) => {
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
    if (loaded && content === loaded.text) { await onStart({ meetingDate, file: loaded.file }); return }
    const invalid = validateMeetingText(content)
    if (invalid) { setError(invalid); return }
    await onStart({ meetingDate, content })
  }

  return <form className="card upload-form" onSubmit={handleSubmit} noValidate>
    <fieldset className="upload-fields" disabled={starting}>
      <div className="row upload-settings">
        <label>프로젝트 (필수)
          <select value={projectId ?? ''} onChange={(e) => { if (e.target.value) { onProjectChange(Number(e.target.value)); setError(null) } }} disabled={projectsLoading || busy} required>
            <option value="" disabled>{projectsLoading ? '프로젝트 불러오는 중' : '프로젝트를 선택하세요'}</option>
            {projects.map((project) => <option key={project.projectId} value={project.projectId}>{project.name}</option>)}
          </select>
        </label>
        <label>회의 날짜 (필수)
          <input type="date" value={meetingDate} onChange={(e) => { setMeetingDate(e.target.value); setDateFromText(false); setError(null) }} required />
          {dateFromText && <span className="muted upload-date-hint">회의록에 적힌 날짜로 채웠어요</span>}
        </label>
      </div>
      {!projectsLoading && projects.length === 0 && <p className="notice">참여 중인 프로젝트가 없습니다. 프로젝트 설정에서 먼저 등록해 주세요.</p>}
      <div className={`meeting-input ${dragOver ? 'over' : ''}`}
        onDragOver={(e) => { e.preventDefault(); if (!busy) setDragOver(true) }} onDragLeave={() => setDragOver(false)} onDrop={handleDrop}>
        <div className="meeting-input-heading">
          <label htmlFor="meeting-content">회의록 내용 (필수)</label>
          <div className="meeting-input-actions">
            <button type="button" className="link" disabled={busy} onClick={() => { void handleFile(new File([sampleMeetingText], sampleMeetingFileName, { type: 'text/plain' })) }}>샘플 사용</button>
            <label className={`file-picker ${busy ? 'disabled' : ''}`}>
              <input type="file" className="sr-only" aria-label="회의록 파일 선택" accept=".md,.txt,text/markdown,text/plain" onChange={handleChange} disabled={busy} />
              파일 불러오기
            </label>
          </div>
        </div>
        <textarea id="meeting-content" rows={14} value={content} disabled={reading}
          placeholder="회의록을 붙여넣거나 MD/TXT 파일을 끌어놓으세요. (최대 10MB)"
          onChange={(e) => { changeContent(e.target.value); if (!e.target.value.trim()) setLoaded(null); setError(null) }} />
        {/* 불러온 내용을 고치면 텍스트로 보내므로 파일 안내는 내용이 그대로일 때만 보인다. */}
        {(reading || (loaded && content === loaded.text)) && <p className="muted meeting-input-hint">
          {reading ? <span className="inline-status" role="status"><Spinner size="sm" label="파일 확인 중" /> 파일을 불러오고 있습니다.</span>
            : <>{loaded?.file.name}에서 불러왔어요</>}
        </p>}
      </div>
    </fieldset>
    {error && <p className="error" role="alert">{error}</p>}
    <button type="submit" className="primary" disabled={busy || projectsLoading || projectId === null || !meetingDate || !content.trim()}>
      {starting ? <><Spinner size="sm" label="회의록 처리 요청 중" /> 처리 요청 중...</> : '티켓 추출'}
    </button>
  </form>
}
