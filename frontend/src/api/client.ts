import type { ApiError } from './types'

export class ApiRequestError extends Error {
  readonly code: string
  readonly status: number

  constructor(status: number, error: ApiError) {
    super(error.message)
    this.code = error.code
    this.status = status
  }
}

// 백엔드 주소를 직접 쓰지 않는다. /api 요청은 Vite 프록시가 백엔드(8080)로 전달한다.
// 같은 출처 요청이라 로그인 세션 쿠키(JSESSIONID)가 자동으로 함께 전송된다.
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // 파일 업로드(FormData)는 브라우저가 multipart 경계값을 넣도록 Content-Type을 비워 둔다.
  const isForm = init?.body instanceof FormData
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: isForm ? init?.headers : { 'Content-Type': 'application/json', ...init?.headers },
  })

  if (!res.ok) {
    const error: ApiError = await res
      .json()
      .catch(() => ({ code: 'UNKNOWN', message: `요청 실패 (${res.status})` }))
    throw new ApiRequestError(res.status, error)
  }

  // 204 No Content (로그아웃 등)는 본문이 없다.
  if (res.status === 204) return undefined as T

  return res.json() as Promise<T>
}
