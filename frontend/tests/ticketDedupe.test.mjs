import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import ts from 'typescript'

// 설치된 React/TypeScript로 실제 컴포넌트를 렌더링한다. 추가 의존성은 없다.
async function sourceModule(path, replacements = {}) {
  const url = new URL(path, import.meta.url)
  const source = await readFile(url, 'utf8')
  let { outputText } = ts.transpileModule(source, { fileName: url.pathname, compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } })
  const modules = { 'react/jsx-runtime': import.meta.resolve('react/jsx-runtime'), ...replacements }
  for (const [name, value] of Object.entries(modules)) outputText = outputText.replaceAll(`'${name}'`, JSON.stringify(value)).replaceAll(`"${name}"`, JSON.stringify(value))
  return `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
}
const helper = await sourceModule('../src/batchPublish.ts')
const component = await sourceModule('../src/components/TicketDedupeDetails.tsx', { '../batchPublish': helper })
const { TicketDedupeDetails } = await import(component)
const base = { judged: true, skipped: null, relation: 'RELATED', issue: { number: 12, title: '기존 업무', state: 'open', url: 'https://github.com/example/repo/issues/12' }, overlap: true, reason: '기존 업무와 연관됩니다.', before: null, after: null, changedByRecheck: false }
const render = (dedupe, rest = {}) => renderToStaticMarkup(createElement(TicketDedupeDetails, { ticket: { ticketId: 'test', publishTarget: 'GITHUB', processingType: 'CREATE', dedupe, ...rest } }))

test('누락된 정보와 명시적인 판단 미수행을 구분한다', () => {
  for (const d of [null, undefined]) assert.match(render(d), /정보가 제공되지 않았습니다/)
  const html = render({ ...base, judged: false, skipped: 'timeout' })
  assert.match(html, /중복 판단 안 됨/)
  assert.match(html, /판단 시간이 초과/)
  assert.doesNotMatch(html, /겹칠 수 있음|기존 업무와 연관|href=/)
})
test('겹침 경고는 판단한 GitHub 신규 발행에만 표시한다', () => {
  assert.match(render(base), /겹칠 수 있음/)
  for (const rest of [{ processingType: 'LINK' }, { processingType: 'UPDATE' }, { publishTarget: 'NOTION' }]) assert.doesNotMatch(render(base, rest), /겹칠 수 있음/)
  assert.doesNotMatch(render({ ...base, overlap: false }), /겹칠 수 있음/)
})
test('관계·사유·기존 이슈 상태와 새 탭 링크를 표시한다', () => {
  const html = render(base)
  assert.match(html, /관련된 업무/)
  assert.match(html, /기존 업무와 연관됩니다/)
  assert.match(html, /열림/)
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.match(render({ ...base, issue: { ...base.issue, state: 'closed' } }), /닫힘/)
})
test('변경 전후는 실제 제공된 내용만 표시하고 텍스트로 렌더링한다', () => {
  const html = render({ ...base, relation: 'CHANGE', before: '동기 처리', after: '비동기 처리\n<script>alert(1)</script>' }, { processingType: 'UPDATE' })
  assert.match(html, /변경 전후 비교/)
  assert.match(html, /동기 처리/)
  assert.match(html, /비동기 처리\n&lt;script&gt;/)
  assert.doesNotMatch(html, /<script>/)
  const partial = render({ ...base, relation: 'CHANGE', before: '이전 조건', after: '  ' })
  assert.match(partial, /변경 후 정보가 제공되지 않았습니다/)
  assert.equal((partial.match(/class="ticket-dedupe-text"/g) ?? []).length, 2) // 판단 사유 + 제공된 변경 전
  assert.doesNotMatch(render({ ...base, relation: 'CHANGE', before: null, after: null }), /변경 전후 비교|ticket-dedupe-comparison/)
})
test('재대조 변경은 현재 처리 유형만 알리고 이전 유형을 추정하지 않는다', () => {
  const html = render({ ...base, changedByRecheck: true }, { processingType: 'LINK' })
  assert.match(html, /발행 직전 재대조/)
  assert.match(html, /현재 처리 유형: 기존 이슈 연결/)
  assert.doesNotMatch(html, /신규 발행에서/)
  assert.doesNotMatch(render(base), /발행 직전 재대조/)
})
test('비정상 링크는 누를 수 없고 알 수 없는 관계·상태를 추정하지 않는다', () => {
  for (const url of ['javascript:alert(1)', 'https://github.com.evil.test/issue', 'http://github.com/example/repo/issues/12']) {
    const html = render({ ...base, issue: { ...base.issue, url, state: null }, relation: 'constructor' })
    assert.doesNotMatch(html, /href=/)
    assert.match(html, /링크를 확인할 수 없습니다/)
    assert.match(html, /관계 정보 없음/)
    assert.match(html, /상태 정보 없음/)
  }
})
test('사유 누락과 알 수 없는 미수행 이유를 안전하게 안내한다', () => {
  assert.match(render({ ...base, reason: '  ' }), /판단 사유가 제공되지 않았습니다/)
  const html = render({ ...base, judged: false, skipped: 'error: internal-details' })
  assert.match(html, /판단을 완료하지 못했습니다/)
  assert.doesNotMatch(html, /internal-details/)
  assert.match(render({ ...base, judged: false, skipped: 'constructor' }), /판단을 완료하지 못했습니다/)
})
test('판단 정보는 발행·수정용 컨트롤 없이 읽기 전용이다', () => {
  assert.doesNotMatch(render(base), /<button|<input|<select|<form|<textarea/)
})
