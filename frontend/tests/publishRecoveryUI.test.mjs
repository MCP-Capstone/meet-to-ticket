import assert from 'node:assert/strict'
import test from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { fileURLToPath } from 'node:url'
import { rolldown } from 'rolldown'

async function component(path) {
  const bundle = await rolldown({ input: fileURLToPath(new URL(path, import.meta.url)), platform: 'node',
    external: ['react', 'react/jsx-runtime'] })
  const generated = await bundle.generate({ format: 'esm' })
  await bundle.close()
  let source = generated.output[0].code.replaceAll('import.meta.env.VITE_USE_MOCK', '"false"')
  for (const name of ['react', 'react/jsx-runtime']) {
    source = source.replaceAll(`"${name}"`, JSON.stringify(import.meta.resolve(name)))
      .replaceAll(`'${name}'`, JSON.stringify(import.meta.resolve(name)))
  }
  try { return await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`) }
  catch (error) { throw new Error(error.message) }
}
const { TicketCard } = await component('../src/components/TicketCard.tsx')
const failure = { errorCode: 'GITHUB_AUTH', errorMessage: '권한 오류', retryable: false, outcomeUnknown: false, resolution: 'RETRY', checkUrl: null, failureCategory: 'OTHER' }
const ticket = { ticketId: 't', title: '작업', status: 'FAILED', processingType: 'CREATE', publishTarget: 'GITHUB', projectId: 1,
  projectName: '프로젝트', meetingId: 1, meetingTitle: '회의', taskId: 'task', createdAt: '2026-10-24', description: '설명',
  assignee: { projectMemberId: 1, name: '담당자' }, due: {}, publishFailure: failure, publishTargetLocked: false }
const render = (overrides = {}) => renderToStaticMarkup(createElement(TicketCard, { ticket: { ...ticket, ...overrides }, busy: false,
  onCommit: async () => {}, onRefresh: async () => true, onDirtyChange: () => {}, onOpenProjectSettings: () => {} }))

test('재시도 불가 실패 카드에 비활성 버튼과 수정·재승인 안내를 표시한다', () => {
  const html = render()
  assert.match(html, /<button[^>]*disabled=""[^>]*>다시 시도<\/button>/)
  assert.match(html, /수정.*다시 승인/)
  assert.match(render({ publishFailure: { ...failure, retryable: true } }), /<button type="button" class="primary">다시 시도<\/button>/)
})
test('UPDATED 댓글 실패만 전용 댓글 재전송을 표시하고 전체 재시도는 제공하지 않는다', () => {
  const html = render({ status: 'UPDATED', processingType: 'UPDATE', publishFailure: { ...failure, errorCode: 'COMMENT_FAILED', retryable: true } })
  assert.match(html, /이슈 본문은 갱신/)
  assert.match(html, /알림 댓글 다시 보내기/)
  assert.doesNotMatch(html, />다시 시도<|>수정</)
  assert.doesNotMatch(render({ status: 'UPDATED', publishFailure: null }), /알림 댓글 다시 보내기/)
})
