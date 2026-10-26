// 서버의 기본 MAX_UPLOAD_SIZE(10MB)에 맞춘 사전 검사. 최종 한도는 서버 설정을 따른다.
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

export function validMeetingDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

export function validateMeetingText(text: string): string | null {
  if (!text.trim()) return '회의록 내용을 입력해 주세요.'
  if (text.includes('\0')) return '회의록에 사용할 수 없는 문자가 포함되어 있습니다.'
  if (new TextEncoder().encode(text).byteLength > MAX_UPLOAD_BYTES) return '회의록은 10MB 이하로 입력해 주세요.'
  return null
}

export async function readMeetingFile(file: File): Promise<string> {
  if (!/\.(md|txt)$/i.test(file.name)) throw new Error('.md 또는 .txt 파일만 업로드할 수 있습니다.')
  if (file.name.length > 255 || /[/\\]/.test(file.name)) throw new Error('파일 이름을 확인해 주세요.')
  if (file.size === 0) throw new Error('빈 파일입니다.')
  if (file.size > MAX_UPLOAD_BYTES) throw new Error('10MB 이하 파일만 업로드할 수 있습니다.')
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
  } catch {
    throw new Error('UTF-8 텍스트 파일인지 확인해 주세요.')
  }
  const invalid = validateMeetingText(text)
  if (invalid) throw new Error(invalid)
  return text
}

// 회의록 머리에 적힌 회의 날짜(예: "- 회의 일시: 2026-10-22 10:00~10:30")를 찾는다.
// Agent는 월 없는 기한("23일")을 회의 날짜로 계산하므로, 업로드한 날을 고르면 기한이 다음 달로 밀린다.
const DATE_LABEL = /(회의\s*일시|회의\s*날짜|회의일|일시|날짜|일자|date)/i
const DATE_VALUE = /(\d{4})\s*(?:[-./]\s*(\d{1,2})\s*[-./]\s*(\d{1,2})|년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일)/
const HEADER_LINES = 20

export function meetingDateFromText(text: string): string | null {
  const lines = text.replace(/\r/g, '').split('\n').slice(0, HEADER_LINES)
  // 날짜 칸 이름이 붙은 줄을 먼저 보고, 없으면 제목 줄에 적힌 날짜를 쓴다.
  for (const line of [...lines.filter((l) => DATE_LABEL.test(l)), ...lines.filter((l) => l.trim().startsWith('#'))]) {
    const m = DATE_VALUE.exec(line)
    if (!m) continue
    const value = `${m[1]}-${(m[2] ?? m[4]).padStart(2, '0')}-${(m[3] ?? m[5]).padStart(2, '0')}`
    if (validMeetingDate(value)) return value
  }
  return null
}
