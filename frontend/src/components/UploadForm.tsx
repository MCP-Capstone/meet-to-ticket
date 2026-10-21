import { useState } from 'react'
import type { ChangeEvent, DragEvent } from 'react'
import { api } from '../api'
import type { Meeting } from '../api'
import { sampleMeetingFileName, sampleMeetingText } from '../mocks/data'
import { Spinner } from './Spinner'

// 백엔드(Spring) 기본 업로드 한도와 같다.
export const MAX_FILE_SIZE = 1024 * 1024

const PREVIEW_LINES = 12

interface Props {
  projectId: number
  // 참여자 ID를 이름으로 바꾼다.
  toNames: (ids: number[]) => string[]
  starting: boolean
  onStart: (meeting: Meeting) => void
}

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

const formatSize = (bytes: number) =>
  bytes < 1024 ? `${bytes}B` : bytes < MAX_FILE_SIZE ? `${(bytes / 1024).toFixed(1)}KB` : `${(bytes / MAX_FILE_SIZE).toFixed(1)}MB`

function validate(file: File) {
  if (!/\.txt$/i.test(file.name)) return '.txt 파일만 업로드할 수 있습니다.'
  if (file.size === 0) return '빈 파일입니다.'
  if (file.size > MAX_FILE_SIZE) return '1MB 이하 파일만 업로드할 수 있습니다.'
  return null
}

export function UploadForm({ projectId, toNames, starting, onStart }: Props) {
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState('')
  const [meeting, setMeeting] = useState<Meeting | null>(null)
  const [uploading, setUploading] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 파일을 고르면 바로 검사하고 업로드한다. 업로드가 끝나야 분석 버튼이 켜진다.
  const handleFile = async (selected: File) => {
    setError(null)
    setMeeting(null)
    const invalid = validate(selected)
    if (invalid) {
      setFile(null)
      setError(invalid)
      return
    }
    setFile(selected)
    setPreview((await selected.text()).split('\n').slice(0, PREVIEW_LINES).join('\n'))
    setUploading(true)
    try {
      setMeeting(await api.uploadMeeting(projectId, selected))
    } catch (e) {
      setError(toMessage(e))
    } finally {
      setUploading(false)
    }
  }

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0]
    // 같은 파일을 다시 골라도 change가 일어나도록 비운다.
    e.target.value = ''
    if (selected) void handleFile(selected)
  }

  const handleDrop = (e: DragEvent<HTMLLabelElement>) => {
    e.preventDefault()
    setDragOver(false)
    const dropped = e.dataTransfer.files[0]
    if (dropped) void handleFile(dropped)
  }

  const useSample = () => {
    void handleFile(new File([sampleMeetingText], sampleMeetingFileName, { type: 'text/plain' }))
  }

  const busy = uploading || starting

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>회의록 업로드</h2>
          <p className="muted">.txt 회의록을 올리면 AI가 할 일을 추출하고, 검수를 통과한 항목을 GitHub 이슈로 발행합니다.</p>
        </div>
        <button type="button" className="link" onClick={useSample} disabled={busy}>
          샘플 파일 사용
        </button>
      </div>

      <label
        className={`dropzone ${dragOver ? 'over' : ''} ${busy ? 'disabled' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        <input type="file" accept=".txt,text/plain" onChange={handleChange} disabled={busy} hidden />
        <strong>{file ? '다른 파일 선택' : '.txt 파일을 끌어놓거나 클릭해서 선택하세요'}</strong>
        <span className="muted">최대 1MB, 제목 / 회의 일시 / 회의 참여자가 적힌 회의록 (참여자는 팀원 이름과 같아야 합니다)</span>
      </label>

      {error && <p className="error">{error}</p>}

      {file && (
        <div className="file">
          <div className="file-name">
            <strong>{file.name}</strong>
            <span className="muted">{formatSize(file.size)}</span>
          </div>

          {uploading && (
            <p className="inline-status">
              <Spinner size="sm" label="업로드 중" /> 업로드하고 회의 정보를 읽는 중입니다...
            </p>
          )}

          {meeting && (
            <dl className="meeting-info">
              <dt>제목</dt>
              <dd>{meeting.title}</dd>
              <dt>날짜</dt>
              <dd>{meeting.meetingDate}</dd>
              <dt>참여자</dt>
              <dd>{meeting.participantIds.length > 0 ? toNames(meeting.participantIds).join(', ') : '없음'}</dd>
            </dl>
          )}

          <pre className="preview">{preview}</pre>
        </div>
      )}

      <button type="button" className="primary" disabled={!meeting || busy} onClick={() => meeting && onStart(meeting)}>
        {starting ? (
          <>
            <Spinner size="sm" label="분석 요청 중" /> 분석 요청 중...
          </>
        ) : (
          '분석 시작'
        )}
      </button>
    </section>
  )
}
