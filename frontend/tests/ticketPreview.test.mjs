import assert from 'node:assert/strict'
import test from 'node:test'
import { rolldown } from 'rolldown'
const bundle = await rolldown({ input: new URL('../src/mocks/tickets.ts', import.meta.url).pathname, platform: 'node' })
const generated = await bundle.generate({ format: 'esm' })
await bundle.close()
const tickets = await import(`data:text/javascript;base64,${Buffer.from(generated.output[0].code).toString('base64')}`)

test('미리보기는 저장된 상세 백로그를 조회하며 승인·발행·저장을 수행하지 않는다', (t) => {
  const storage = new Map()
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
    getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value),
  } })
  t.after(() => { if (descriptor) Object.defineProperty(globalThis, 'sessionStorage', descriptor); else delete globalThis.sessionStorage })
  const user = { userId: 1, loginId: 'demo1', name: 'demo' }
  const before = tickets.listMockTickets(user)
  const item = before.tickets.find((item) => item.status === 'PENDING_REVIEW')
  const saved = [...storage.entries()]
  const preview = tickets.previewMockTicket(user, item.ticketId)
  assert.equal(preview.executionMode, 'MOCK')
  assert.equal(preview.status, 'PENDING_REVIEW')
  assert.ok(preview.backlog.tasks.length)
  assert.ok(preview.backlog.acceptanceCriteria.length)
  assert.deepEqual([...storage.entries()], saved)
  assert.deepEqual(tickets.listMockTickets(user), JSON.parse(JSON.stringify(before)))
  // 조회 응답을 고쳐도 저장 데이터에 영향을 주지 않는다.
  preview.backlog.tasks[0] = '응답만 변경'
  assert.notEqual(tickets.previewMockTicket(user, item.ticketId).backlog.tasks[0], '응답만 변경')
  tickets.editMockTicket(user, item.ticketId, { description: '담당자가 저장한 본문', due: { text: null, date: '2026-10-30', time: '14:00' } })
  const edited = tickets.previewMockTicket(user, item.ticketId)
  assert.match(edited.publication.body, /담당자가 저장한 본문/)
  assert.match(edited.publication.body, /2026-10-30/)
  assert.equal(edited.status, 'PENDING_REVIEW')
  assert.throws(() => tickets.previewMockTicket({ ...user, userId: 999 }, item.ticketId))
  assert.throws(() => tickets.previewMockTicket(user, 'missing'))
})
