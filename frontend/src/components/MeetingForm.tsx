import { useState } from 'react'
import type { FormEvent } from 'react'
import type { CreateMeetingRequest } from '../api'
import { sampleMeetingContent } from '../mocks/data'

interface Props {
  disabled: boolean
  onSubmit: (body: CreateMeetingRequest) => void
}

const today = () => new Date().toISOString().slice(0, 10)

export function MeetingForm({ disabled, onSubmit }: Props) {
  const [title, setTitle] = useState('')
  const [meetingDate, setMeetingDate] = useState(today)
  const [participants, setParticipants] = useState('')
  const [content, setContent] = useState('')

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
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
    setParticipants('PM, 백엔드, AI')
    setContent(sampleMeetingContent)
  }

  return (
    <form className="card" onSubmit={handleSubmit}>
      <div className="card-header">
        <h2>회의록 입력</h2>
        <button type="button" className="link" onClick={fillSample} disabled={disabled}>
          샘플 채우기
        </button>
      </div>

      <div className="row">
        <label>
          제목
          <input value={title} onChange={(e) => setTitle(e.target.value)} required disabled={disabled} />
        </label>
        <label>
          회의 날짜
          <input
            type="date"
            value={meetingDate}
            onChange={(e) => setMeetingDate(e.target.value)}
            required
            disabled={disabled}
          />
        </label>
      </div>

      <label>
        참석자 (쉼표로 구분)
        <input
          value={participants}
          onChange={(e) => setParticipants(e.target.value)}
          placeholder="홍길동, 김철수"
          disabled={disabled}
        />
      </label>

      <label>
        회의록
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={10}
          required
          disabled={disabled}
        />
      </label>

      <button type="submit" disabled={disabled || !title.trim() || !content.trim()}>
        할 일 추출
      </button>
    </form>
  )
}
