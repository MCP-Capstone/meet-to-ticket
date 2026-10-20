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
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })

  if (!res.ok) {
    const error: ApiError = await res
      .json()
      .catch(() => ({ code: 'UNKNOWN', message: `요청 실패 (${res.status})` }))
    throw new ApiRequestError(res.status, error)
  }

  return res.json() as Promise<T>
}
