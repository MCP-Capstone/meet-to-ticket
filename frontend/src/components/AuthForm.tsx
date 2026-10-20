import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, useMock } from '../api'
import type { User } from '../api'

type Mode = 'login' | 'signup'

interface Props {
  onLogin: (user: User) => void
}

const LOGIN_ID_PATTERN = /^[a-zA-Z0-9]{4,20}$/

const toMessage = (e: unknown) => (e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.')

// 백엔드 검증 규칙(SignupRequest)과 같은 기준으로 먼저 확인한다.
function validateSignup(loginId: string, password: string, passwordConfirm: string, name: string) {
  if (!LOGIN_ID_PATTERN.test(loginId)) return '아이디는 영문 / 숫자 4~20자입니다.'
  if (password.length < 8 || password.length > 50) return '비밀번호는 8~50자입니다.'
  if (password !== passwordConfirm) return '비밀번호가 일치하지 않습니다.'
  if (name.trim() === '' || name.trim().length > 20) return '이름은 1~20자입니다.'
  return null
}

export function AuthForm({ onLogin }: Props) {
  const [mode, setMode] = useState<Mode>('login')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const isSignup = mode === 'signup'

  const switchMode = (next: Mode) => {
    setMode(next)
    setError(null)
    setPassword('')
    setPasswordConfirm('')
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    const invalid = isSignup ? validateSignup(loginId, password, passwordConfirm, name) : null
    if (invalid) {
      setError(invalid)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const user = isSignup
        ? await api.signup({ loginId, password, name: name.trim() })
        : await api.login({ loginId, password })
      onLogin(user)
    } catch (e) {
      setError(toMessage(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="card auth" onSubmit={handleSubmit}>
      <div className="tabs" role="tablist">
        <button type="button" role="tab" aria-selected={!isSignup} className={!isSignup ? 'active' : ''} onClick={() => switchMode('login')}>
          로그인
        </button>
        <button type="button" role="tab" aria-selected={isSignup} className={isSignup ? 'active' : ''} onClick={() => switchMode('signup')}>
          회원가입
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      <label>
        아이디
        <input
          value={loginId}
          onChange={(e) => setLoginId(e.target.value)}
          autoComplete="username"
          placeholder={isSignup ? '영문 / 숫자 4~20자' : ''}
          required
        />
      </label>

      <label>
        비밀번호
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={isSignup ? 'new-password' : 'current-password'}
          placeholder={isSignup ? '8자 이상' : ''}
          required
        />
      </label>

      {isSignup && (
        <>
          <label>
            비밀번호 확인
            <input
              type="password"
              value={passwordConfirm}
              onChange={(e) => setPasswordConfirm(e.target.value)}
              autoComplete="new-password"
              required
            />
          </label>
          <label>
            이름
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={20} placeholder="예: 홍길동" required />
          </label>
        </>
      )}

      <button type="submit" className="primary" disabled={submitting}>
        {submitting ? '처리 중...' : isSignup ? '가입하고 시작하기' : '로그인'}
      </button>

      {useMock && !isSignup && <p className="muted hint">mock 모드 데모 계정: demo / demo1234</p>}
    </form>
  )
}
