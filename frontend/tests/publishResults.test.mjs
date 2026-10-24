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
const { ResultList } = await component('../src/components/ResultList.tsx')
const failure = { errorCode: 'GITHUB_AUTH', errorMessage: '권한 오류', retryable: false, outcomeUnknown: false, resolution: 'RETRY', checkUrl: null, failureCategory: 'OTHER' }
const ticket = { ticketId: 't', title: '작업', status: 'FAILED', processingType: 'CREATE', publishTarget: 'GITHUB', projectId: 1,
  projectName: '프로젝트', meetingId: 1, meetingTitle: '회의', taskId: 'task', createdAt: '2026-10-24', description: '설명',
  assignee: { projectMemberId: 1, name: '담당자' }, due: {}, publishFailure: failure, publishTargetLocked: false }
const render = (overrides = {}) => renderToStaticMarkup(createElement(TicketCard, { ticket: { ...ticket, ...overrides }, busy: false,
  onCommit: async () => {}, onRefresh: async () => true, onDirtyChange: () => {}, onOpenProjectSettings: () => {} }))

test('내 티켓에서 각 도구 결과 링크를 안전하게 표시한다', () => {
  for (const [publishTarget, githubIssueUrl, name] of [['GITHUB', 'https://github.com/o/r/issues/7', 'GitHub'],
    ['NOTION', 'https://app.notion.com/p/result', 'Notion'], ['SLACK', 'https://team.slack.com/archives/C1/p1', 'Slack']]) {
    const html = render({ status: 'PUBLISHED', publishFailure: null, publishTarget, githubIssueUrl })
    assert.ok(html.includes(`href="${githubIssueUrl}"`))
    assert.ok(html.includes(`${name}에서 발행 결과 보기`))
  }
  assert.doesNotMatch(render({ githubIssueUrl: 'javascript:alert(1)' }), /href="javascript:/)
})
test('분석 결과에서 Notion/Slack을 GitHub나 #null로 표시하지 않는다', () => {
  for (const [publishTarget, githubIssueUrl, name] of [['NOTION', 'https://app.notion.com/p/result', 'Notion'], ['SLACK', 'https://team.slack.com/archives/C1/p1', 'Slack']]) {
    const html = renderToStaticMarkup(createElement(ResultList, { meeting: { title: '회의' }, participants: [], result: {
      executionMode: 'LIVE', status: 'COMPLETED', summary: { succeeded: 1, failed: 0, unknown: 0 },
      items: [{ itemId: 'i', title: '티켓', publishStatus: 'SUCCEEDED', reviewStatus: 'APPROVED', publishTarget,
        githubIssueNumber: null, githubIssueUrl, contentJson: null }],
    } }))
    assert.ok(html.includes(`${name}에서 보기`))
    assert.doesNotMatch(html, /#null|GitHub에서 보기/)
  }
})
