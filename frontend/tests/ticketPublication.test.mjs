import assert from 'node:assert/strict'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { rolldown } from 'rolldown'
async function bundle(path) {
  const build = await rolldown({ input: fileURLToPath(new URL(path, import.meta.url)), platform: 'node' })
  const result = await build.generate({ format: 'esm' })
  await build.close()
  return import(`data:text/javascript;base64,${Buffer.from(result.output[0].code).toString('base64')}`)
}
const { canUseExistingIssue, publicationRequest } = await bundle('../src/ticketPublication.ts')
const tickets = await bundle('../src/mocks/tickets.ts')

test('기존 이슈 후보는 열린 정수 번호가 제공된 경우만 허용한다', () => {
  for (const dedupe of [undefined, null, { issue: null }, { issue: { number: 12, state: 'closed' } }, { issue: { number: 12, state: null } }, { issue: { number: 0, state: 'open' } }, { issue: { number: '12', state: 'open' } }]) assert.equal(canUseExistingIssue({ dedupe }), false)
  assert.equal(canUseExistingIssue({ dedupe: { issue: { number: 12, state: 'open' } } }), true)
})
test('Notion/Slack은 신규 생성으로 정규화하고 GitHub 선택은 보존한다', () => {
  for (const type of ['CREATE', 'LINK', 'UPDATE']) {
    for (const target of ['NOTION', 'SLACK']) assert.deepEqual(publicationRequest(target, type), { publishTarget: target, processingType: 'CREATE' })
    assert.deepEqual(publicationRequest('GITHUB', type), { publishTarget: 'GITHUB', processingType: type })
  }
})

test('Slack 미리보기는 저장한 티켓 본문을 발행 기준으로 표시한다', (t) => {
  storage(t)
  const item = tickets.listMockTickets(user).tickets.find((ticket) => ticket.status === 'PENDING_REVIEW')
  assert.ok(item)
  const updated = changes(item.ticketId, 'CREATE', 'SLACK')
  const preview = tickets.previewMockTicket(user, updated.ticketId)
  assert.equal(preview.publication.kind, 'CREATE')
  assert.equal(preview.publication.body, updated.description)
})
function storage(t) {
  const values = new Map()
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) } })
  t.after(() => { if (descriptor) Object.defineProperty(globalThis, 'sessionStorage', descriptor); else delete globalThis.sessionStorage })
  return values
}
const user = { userId: 1, loginId: 'demo1', name: 'demo' }
const changes = (id, type, target = 'GITHUB') => tickets.changeMockTicketPublication(user, id, { publishTarget: target, processingType: type })

test('처리 유형 저장/미리보기 갱신은 본문/기한/승인 상태를 변경하지 않는다', (t) => {
  storage(t)
  const item = tickets.listMockTickets(user).tickets.find((item) => item.status === 'PENDING_REVIEW' && canUseExistingIssue(item))
  const linked = changes(item.ticketId, 'LINK')
  assert.equal(linked.processingType, 'LINK')
  assert.equal(linked.description, item.description)
  assert.deepEqual(linked.due, item.due)
  assert.equal(linked.status, 'PENDING_REVIEW')
  assert.equal(tickets.previewMockTicket(user, item.ticketId).publication.kind, 'LINK')
  assert.equal(changes(item.ticketId, 'CREATE').dedupe.overlap, true)
  assert.equal(changes(item.ticketId, 'UPDATE').dedupe.overlap, false)
  const notion = changes(item.ticketId, 'CREATE', 'NOTION')
  assert.equal(notion.publishTarget, 'NOTION')
  assert.equal(tickets.previewMockTicket(user, item.ticketId).publication, null)
})
test('잘못된 유형/대상 조합과 닫힌 이슈는 저장하지 않는다', (t) => {
  const values = storage(t)
  const item = tickets.listMockTickets(user).tickets.find((item) => item.status === 'PENDING_REVIEW' && !canUseExistingIssue(item))
  const before = [...values.entries()]
  for (const type of ['LINK', 'UPDATE']) assert.throws(() => changes(item.ticketId, type), (e) => e.code === 'EXISTING_ISSUE_REQUIRED')
  assert.throws(() => changes(item.ticketId, 'LINK', 'SLACK'), (e) => e.code === 'PROCESSING_TYPE_TARGET_MISMATCH')
  assert.throws(() => changes(item.ticketId, 'CREATE', 'JIRA'), (e) => e.code === 'VALIDATION_FAILED')
  assert.deepEqual([...values.entries()], before)
})
test('다른 담당자/없는 티켓/검토 대기가 아닌 티켓의 변경을 거절한다', (t) => {
  storage(t)
  const items = tickets.listMockTickets(user).tickets
  const pending = items.find((item) => item.status === 'PENDING_REVIEW')
  const confirmed = items.find((item) => item.status === 'CONFIRMED')
  assert.throws(() => tickets.changeMockTicketPublication({ ...user, userId: 999 }, pending.ticketId, publicationRequest('GITHUB', 'CREATE')), (e) => e.code === 'TICKET_ASSIGNEE_REQUIRED')
  assert.throws(() => changes('missing', 'CREATE'), (e) => e.code === 'TICKET_NOT_FOUND')
  assert.throws(() => changes(confirmed.ticketId, 'CREATE'), (e) => e.code === 'TICKET_STATUS_CONFLICT')
})
