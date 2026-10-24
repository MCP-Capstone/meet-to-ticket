import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(new URL('../src/publishFailure.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { failureGuide, needsOutcomeCheck, canRetryFailure, canRetryComment } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)
const failure = (errorCode, overrides = {}) => ({ errorCode, errorMessage: '서버 사유', failureCategory: 'OTHER', retryable: false, outcomeUnknown: true, resolution: 'RETRY', ...overrides })
test('백엔드가 MANUAL_CHECK로 내려준 실패만 다시 시도 대신 확인 결과가 필요하다', () => {
  // Notion 첫 타임아웃처럼 오류 코드가 VERIFY가 아니어도 resolution을 따른다
  assert.equal(needsOutcomeCheck(failure('RESULT_TIMEOUT', { resolution: 'MANUAL_CHECK' })), true)
  assert.equal(needsOutcomeCheck(failure('SLACK_VERIFY_REQUIRED', { resolution: 'MANUAL_CHECK' })), true)
})
test('GitHub 결과 모름과 일반 실패는 다시 시도한다', () => {
  for (const code of ['RESULT_UNKNOWN', 'RESULT_TIMEOUT', 'VERIFIED_NOT_APPLIED', 'GITHUB_RATE_LIMIT', null]) assert.equal(needsOutcomeCheck(failure(code)), false)
})
test('확인 관련 안내는 전용 문구, 미반영 확인은 서버 사유를 보여 준다', () => {
  assert.match(failureGuide(failure('SLACK_VERIFY_REQUIRED')), /Slack 채널에서 확인한 결과/)
  assert.equal(failureGuide(failure('VERIFIED_NOT_APPLIED')), '서버 사유')
})
test('retryable=false, 실패 정보 없음, 수동 확인 필요는 바로 재시도하지 않는다', () => {
  assert.equal(canRetryFailure(null), false)
  assert.equal(canRetryFailure(failure('GITHUB_AUTH')), false)
  assert.equal(canRetryFailure(failure('TIMEOUT', { retryable: true, resolution: 'MANUAL_CHECK' })), false)
  assert.equal(canRetryFailure(failure('GITHUB_RATE_LIMIT', { retryable: true })), true)
})
test('갱신 완료하고 댓글만 실패한 GitHub 티켓에만 댓글 재전송을 제공한다', () => {
  const ticket = { status: 'UPDATED', publishTarget: 'GITHUB', publishFailure: failure('COMMENT_FAILED', { retryable: true }) }
  assert.equal(canRetryComment(ticket), true)
  assert.equal(canRetryComment({ ...ticket, status: 'PUBLISHING' }), false)
  assert.equal(canRetryComment({ ...ticket, publishTarget: 'NOTION' }), false)
  assert.equal(canRetryComment({ ...ticket, publishFailure: null }), false)
})
