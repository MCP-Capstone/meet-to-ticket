import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(new URL('../src/batchPublish.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { meetingGroups, publishSelection, safeIssueUrl, safePublishUrl } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)
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
test('GitHub, Notion, Slack 확인 티켓을 함께 발행한다', () => {
  const tickets = [ticket('CONFIRMED'), ticket('CONFIRMED', { publishTarget: 'NOTION' }), ticket('CONFIRMED', { publishTarget: 'SLACK' })]
  const selection = publishSelection(tickets)
  assert.equal(selection.reason, null)
  assert.deepEqual(selection.confirmed, tickets)
})
test('결과 링크는 HTTPS GitHub만 허용한다', () => {
  assert.equal(safeIssueUrl('https://github.com/owner/repo/issues/1'), 'https://github.com/owner/repo/issues/1')
  for (const url of [null, 'javascript:alert(1)', 'https://github.com.evil.test/x', 'https://user@github.com/x', 'http://github.com/x']) assert.equal(safeIssueUrl(url), null)
})
test('결과 링크는 발행 대상 도구의 HTTPS 주소만 허용한다', () => {
  assert.equal(safePublishUrl('https://www.notion.so/team/page-1', 'NOTION'), 'https://www.notion.so/team/page-1')
  assert.equal(safePublishUrl('https://team.notion.site/page-1', 'NOTION'), 'https://team.notion.site/page-1')
  assert.equal(safePublishUrl('https://team.slack.com/archives/C1/p1', 'SLACK'), 'https://team.slack.com/archives/C1/p1')
  // 다른 도구의 주소나 비슷한 도메인은 링크로 만들지 않는다
  for (const [url, target] of [['https://github.com/o/r/issues/1', 'NOTION'], ['https://www.notion.so/p', 'SLACK'], ['https://team.slack.com/x', 'GITHUB'],
    ['https://notion.so.evil.test/p', 'NOTION'], ['https://evilslack.com/x', 'SLACK'], ['http://team.slack.com/x', 'SLACK']]) assert.equal(safePublishUrl(url, target), null)
})

// 실제 mock 모듈로 결과 패널 없이 목록만 조회하는 경로를 검증한다.
const { rolldown } = await import('rolldown')
const bundle = await rolldown({ input: new URL('../src/mocks/tickets.ts', import.meta.url).pathname, platform: 'node' })
const generated = await bundle.generate({ format: 'esm' })
await bundle.close()
const mockTickets = await import(`data:text/javascript;base64,${Buffer.from(generated.output[0].code).toString('base64')}`)

test('결과 패널을 닫아도 목록 조회로 mock 발행이 완료된다', (t) => {
  const storage = new Map()
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
  } })
  t.after(() => {
    if (descriptor) Object.defineProperty(globalThis, 'sessionStorage', descriptor)
    else delete globalThis.sessionStorage
  })
  let now = Date.now()
  t.mock.method(Date, 'now', () => now)
  const user = { userId: 1, loginId: 'demo1', name: 'demo', githubName: null, slackEmail: null, slackUserId: null }
  for (const item of mockTickets.listMockTickets(user).tickets.filter((item) => item.status === 'PENDING_REVIEW')) {
    mockTickets.changeMockTicket(user, item.ticketId, 'confirm')
  }
  const confirmed = mockTickets.listMockTickets(user).tickets.filter((item) => item.status === 'CONFIRMED')
  const first = confirmed[0]
  const ids = confirmed.map((item) => item.ticketId)
  const started = mockTickets.publishMockTickets(user, first.projectId, first.meetingId, ids)
  assert.equal(mockTickets.getMockPublishResult(user, started.batchId).status, 'RUNNING')
  // 패널을 닫고 결과 GET을 호출하지 않아도 목록 폴링으로 완료되어야 한다.
  const selected = () => mockTickets.listMockTickets(user).tickets.filter((item) => ids.includes(item.ticketId))
  assert.ok(selected().every((item) => item.status === 'PUBLISHING'))
  now += 2000
  const expectedStatus = { CREATE: 'PUBLISHED', LINK: 'LINKED', UPDATE: 'UPDATED' }
  assert.deepEqual(selected().map((item) => [item.ticketId, item.status]), confirmed.map((item) => [item.ticketId, expectedStatus[item.processingType]]))
  assert.doesNotMatch(publishSelection(mockTickets.listMockTickets(user).tickets).reason, /발행 중/)
  const result = mockTickets.getMockPublishResult(user, started.batchId)
  assert.equal(result.status, 'SUCCEEDED')
  assert.equal(result.summary.publishing, 0)
  assert.equal(result.summary.published, confirmed.filter((item) => item.processingType === 'CREATE').length)
  assert.equal(result.summary.linked, confirmed.filter((item) => item.processingType === 'LINK').length)
  assert.equal(result.summary.updated, confirmed.filter((item) => item.processingType === 'UPDATE').length)
  assert.deepEqual(mockTickets.getMockPublishResult(user, started.batchId), result)
})
