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
