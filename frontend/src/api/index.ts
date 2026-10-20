import { mockApi } from '../mocks/api'
import { ApiRequestError } from './client'
import { realApi } from './real'

// VITE_USE_MOCK=false 일 때만 실제 백엔드를 호출한다. (기본값: mock)
export const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

type Api = typeof realApi

let unauthorizedHandler: (() => void) | null = null

// 로그인이 풀린 상태(401 UNAUTHORIZED)로 API를 호출했을 때 실행할 동작. App에서 등록한다.
// 로그인 실패(401 LOGIN_FAILED)는 코드가 달라 여기에 해당하지 않는다.
export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler
}

const isUnauthorized = (e: unknown) => e instanceof ApiRequestError && e.code === 'UNAUTHORIZED'

// mock / real 어느 쪽이든 모든 API 호출에 401 처리를 붙인다.
function withUnauthorizedHandler(target: Api): Api {
  const wrapped = {} as Record<string, unknown>
  for (const [name, fn] of Object.entries(target) as [string, (...args: unknown[]) => Promise<unknown>][]) {
    wrapped[name] = async (...args: unknown[]) => {
      try {
        return await fn(...args)
      } catch (e) {
        if (isUnauthorized(e)) unauthorizedHandler?.()
        throw e
      }
    }
  }
  return wrapped as Api
}

export const api = withUnauthorizedHandler(useMock ? mockApi : realApi)

export { ApiRequestError } from './client'
export type * from './types'
