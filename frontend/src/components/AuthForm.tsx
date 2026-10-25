import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiRequestError } from '../api'
import type { User } from '../api'
import { Spinner } from './Spinner'

type Mode = 'login' | 'signup'

interface Props {
  onLogin: (user: User) => void
  // 로그인 만료 등으로 로그인 화면에 돌아왔을 때 보여줄 안내
  notice?: string | null
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

export function AuthForm({ onLogin, notice }: Props) {
  const [mode, setMode] = useState<Mode>('login')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [name, setName] = useState('')
  const [githubName, setGithubName] = useState('')
  const [slackEmail, setSlackEmail] = useState('')
  const [githubError, setGithubError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const isSignup = mode === 'signup'

  const switchMode = (next: Mode) => {
    setMode(next)
    setError(null)
    setGithubError(null)
    setPassword('')
    setPasswordConfirm('')
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setGithubError(null)
    // 브라우저 기본 안내(영문 말풍선) 대신 화면의 한국어 안내로 알린다.
    const invalid = isSignup ? validateSignup(loginId, password, passwordConfirm, name) : !loginId.trim() || !password ? '아이디와 비밀번호를 입력해 주세요.' : null
    if (invalid) {
      setError(invalid)
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const user = isSignup
        ? await api.signup({ loginId, password, name: name.trim(), githubName: githubName.trim() || null, slackEmail: slackEmail.trim() || null })
        : await api.login({ loginId, password })
      onLogin(user)
    } catch (e) {
      if (isSignup && e instanceof ApiRequestError && e.code === 'INVALID_GITHUB_ACCOUNT') {
        setGithubError(e.message || 'GitHub 계정을 찾을 수 없습니다. 로그인명을 확인해 주세요.')
      } else {
        setError(toMessage(e))
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="auth-layout" aria-label="Meet-to-Ticket 시작하기">
      <div className="auth-intro">
        <a className="auth-brand" href="/" aria-label="Meet-to-Ticket 홈"><span aria-hidden="true">M↗</span>Meet-to-Ticket</a>
        <p className="auth-eyebrow">팀의 후속 업무 관리</p>
        <h1>회의록에서 티켓까지,<br />검토와 발행을 한곳에서.</h1>
        <p className="auth-description">회의록을 올리고, 티켓을 검토하고,<br />팀이 일하는 곳으로 보내세요.</p>
        <ol className="auth-flow"><li><span>01</span>회의록으로 초안 만들기</li><li><span>02</span>담당자가 검토하고 승인하기</li><li><span>03</span>GitHub · Notion · Slack으로 발행하기</li></ol>
      </div>
      <form className="card auth" onSubmit={handleSubmit} noValidate>
      <div className="auth-heading"><h2>{isSignup ? '회원가입' : '로그인'}</h2><p className="muted">{isSignup ? '티켓을 검토하고 발행할 계정을 만듭니다.' : '계정에 로그인해 티켓을 확인하고 발행하세요.'}</p></div>
      <div className="tabs" role="tablist" aria-label="계정 접속 방식">
        <button type="button" role="tab" aria-selected={!isSignup} className={!isSignup ? 'active' : ''} disabled={submitting} onClick={() => switchMode('login')}>
          로그인
        </button>
        <button type="button" role="tab" aria-selected={isSignup} className={isSignup ? 'active' : ''} disabled={submitting} onClick={() => switchMode('signup')}>
          회원가입
        </button>
      </div>

      {notice && !error && !githubError && <p className="notice">{notice}</p>}
      {error && <p className="error" role="alert">{error}</p>}

      <div className="auth-fields">
        <label>
          아이디
          <input
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            autoCapitalize="none"
            spellCheck={false}
            autoComplete="username"
            placeholder={isSignup ? '영문 / 숫자 4~20자' : ''}
            required
          />
        </label>
        {isSignup && (
          <label>
            이름
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={20} placeholder="예: 홍길동" required />
          </label>
        )}
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
        )}
      </div>

      {isSignup && (
        <fieldset className="auth-optional">
          <legend>연결 정보 <span className="muted">(선택 · 나중에 입력해도 돼요)</span></legend>
          <div className="auth-fields">
            <label>
              GitHub 로그인명
              <input
                value={githubName}
                onChange={(e) => {
                  setGithubName(e.target.value)
                  setGithubError(null)
                }}
                placeholder="예: jadoolim"
                autoCapitalize="none"
                spellCheck={false}
                aria-invalid={Boolean(githubError)}
                aria-describedby={githubError ? 'auth-optional-help github-account-error' : 'auth-optional-help'}
              />
            </label>
            <label>
              Slack 이메일
              <input
                type="email"
                value={slackEmail}
                onChange={(e) => setSlackEmail(e.target.value)}
                placeholder="예: member@example.com"
                autoCapitalize="none"
                spellCheck={false}
                aria-describedby="auth-optional-help"
              />
            </label>
          </div>
          <p id="auth-optional-help" className="muted hint">이슈 담당자 지정과 Slack 멘션에만 사용해요.</p>
          {githubError && <p id="github-account-error" className="error" role="alert">{githubError}</p>}
        </fieldset>
      )}

      <button type="submit" className="primary" disabled={submitting}>
        {submitting ? (
          <>
            <Spinner size="sm" label="처리 중" /> 처리 중...
          </>
        ) : isSignup ? (
          '가입하고 시작하기'
        ) : (
          '로그인'
        )}
      </button>

    </form>
    </section>
  )
}
