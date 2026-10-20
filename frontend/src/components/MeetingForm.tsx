import { useState } from 'react'
import type { FormEvent } from 'react'
import type { CreateMeetingRequest } from '../api'
import { sampleMeetingContent } from '../mocks/data'

export const MAX_CONTENT_LENGTH = 10_000

interface Props {
  onSubmit: (body: CreateMeetingRequest) => void
}

const today = () => new Date().toISOString().slice(0, 10)

export function MeetingForm({ onSubmit }: Props) {
  const [title, setTitle] = useState('')
  const [meetingDate, setMeetingDate] = useState(today)
  const [participants, setParticipants] = useState('')
  const [content, setContent] = useState('')

  const length = content.length
  const tooLong = length > MAX_CONTENT_LENGTH
  const canSubmit = title.trim() !== '' && content.trim() !== '' && !tooLong

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    if (!canSubmit) return
    onSubmit({
      title: title.trim(),
      meetingDate,
      participants: participants
        .split(',')
        .map((p) => p.trim())
        .filter(Boolean),
      content: content.trim(),
    })
  }

  const fillSample = () => {
    setTitle('주간 기획 회의')
    setParticipants('김PM, 이백엔드, 박AI')
    setContent(sampleMeetingContent)
  }

  return (
    <form className="card" onSubmit={handleSubmit}>
      <div className="card-header">
        <div>
          <h2>회의록 입력</h2>
          <p className="muted">회의 정보와 회의록을 입력하면 AI가 할 일을 추출합니다.</p>
        </div>
        <button type="button" className="link" onClick={fillSample}>
          샘플 채우기
        </button>
      </div>

      <div className="row">
        <label>
          회의 제목
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={100}
            placeholder="예: 주간 기획 회의"
            required
          />
        </label>
        <label>
          회의 날짜
          <input type="date" value={meetingDate} onChange={(e) => setMeetingDate(e.target.value)} required />
        </label>
      </div>

      <label>
        참석자 (쉼표로 구분)
        <input
          value={participants}
          onChange={(e) => setParticipants(e.target.value)}
          placeholder="예: 홍길동, 김철수"
        />
      </label>

      <label>
        회의록
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={14}
          placeholder="회의록 텍스트를 붙여 넣으세요."
          required
        />
        <span className={`counter ${tooLong ? 'over' : ''}`}>
          {length.toLocaleString()} / {MAX_CONTENT_LENGTH.toLocaleString()}자
          {tooLong && ' (최대 글자 수를 넘었습니다)'}
        </span>
      </label>

      <button type="submit" className="primary" disabled={!canSubmit}>
        할 일 추출
      </button>
    </form>
  )
}
