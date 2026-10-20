import { mockApi } from '../mocks/api'
import { realApi } from './real'

// VITE_USE_MOCK=false 일 때만 실제 백엔드를 호출한다. (기본값: mock)
export const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

export const api = useMock ? mockApi : realApi

export { ApiRequestError } from './client'
export type * from './types'
