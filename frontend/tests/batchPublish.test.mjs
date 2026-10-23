import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(new URL('../src/batchPublish.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { meetingGroups, publishSelection, safeIssueUrl } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)
const ticket = (status, overrides = {}) => ({ status, projectId: 1, meetingId: 1, publishTarget: 'GITHUB', ...overrides })
test('프로젝트와 회의 ID로 구분하고 같은 제목이어도 합치지 않는다', () => {
  assert.deepEqual(meetingGroups([ticket('CONFIRMED'), ticket('REJECTED'), ticket('CONFIRMED', { meetingId: 2 }), ticket('CONFIRMED', { projectId: 2 })]).map((g) => g.tickets.length), [2, 1, 1])
})
test('확인과 거절을 모두 결정한 뒤 확인만 발행 대상으로 삼는다', () => {
  const confirmed = ticket('CONFIRMED')
  const selection = publishSelection([confirmed, ticket('REJECTED'), ticket('PUBLISHED')])
  assert.equal(selection.reason, null)
  assert.deepEqual(selection.confirmed, [confirmed])
})
test('미검토·담당 미정·발행 중·발행 대상 없음은 차단한다', () => {
  for (const status of ['PENDING_REVIEW', 'ASSIGNEE_REQUIRED', 'PUBLISHING']) assert.ok(publishSelection([ticket('CONFIRMED'), ticket(status)]).reason)
  assert.ok(publishSelection([ticket('REJECTED')]).reason)
  assert.ok(publishSelection([]).reason)
})
test('지원하지 않는 도구를 조용히 누락시키지 않는다', () => {
  assert.match(publishSelection([ticket('CONFIRMED'), ticket('CONFIRMED', { publishTarget: 'SLACK' })]).reason, /GitHub/)
})
test('결과 링크는 HTTPS GitHub만 허용한다', () => {
  assert.equal(safeIssueUrl('https://github.com/owner/repo/issues/1'), 'https://github.com/owner/repo/issues/1')
  for (const url of [null, 'javascript:alert(1)', 'https://github.com.evil.test/x', 'https://user@github.com/x', 'http://github.com/x']) assert.equal(safeIssueUrl(url), null)
})
