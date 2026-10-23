import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

// 기존 개발 의존성으로 TS의 타입만 제거해 실제 화면 판단 함수를 Node에서 검사한다.
// 별도 테스트 라이브러리나 브라우저 설치는 필요 없다.
const source = await readFile(new URL('../src/analysisProgress.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { progressHeading, progressStates, progressCount, progressDetails } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)

const task = (status, progress = null, stage = null) => ({ status, progress, stage })

test('접수 대기와 아직 도착하지 않은 상태 응답을 구분한다', () => {
  assert.match(progressHeading(null), /불러오고/)
  assert.match(progressHeading(task('QUEUED')), /순서를 기다리고/)
  assert.deepEqual(progressStates(task('QUEUED', { step: 'EXTRACTING' })), ['waiting', 'waiting', 'waiting', 'waiting'])
})

test('화면 단계는 legacy stage가 아니라 progress.step을 따른다', () => {
  const running = task('RUNNING', { step: 'DEDUPING', dedupe: { done: 1, total: 3, linked: 0 } }, 'REVIEWING')
  assert.match(progressHeading(running), /기존 이슈/)
  assert.deepEqual(progressStates(running), ['done', 'done', 'active', 'waiting'])
})

test('단계가 아직 없거나 알 수 없는 값이면 실행 단계를 지어내지 않는다', () => {
  for (const progress of [null, undefined, {}, { step: 'FUTURE_STEP' }]) {
    const running = task('RUNNING', progress, 'PUBLISHING')
    assert.deepEqual(progressStates(running), ['unknown', 'unknown', 'unknown', 'unknown'])
    assert.equal(progressHeading(running), '회의록을 처리하고 있습니다')
    assert.doesNotMatch(progressHeading(running), /발행|대기/)
  }
})

test('실패한 단계와 이후 단계를 성공 표시하지 않는다', () => {
  for (const status of ['FAILED', 'PARTIALLY_FAILED']) {
    const failed = task(status, { step: 'EXTRACTING' })
    assert.deepEqual(progressStates(failed), ['done', 'stopped', 'unknown', 'unknown'])
    assert.doesNotMatch(progressHeading(failed), /처리가 완료/)
  }
})

test('전송 단계에 도달해도 보고되지 않은 중복 검수는 완료로 추정하지 않는다', () => {
  assert.deepEqual(progressStates(task('RUNNING', { step: 'DISPATCHING' })), ['done', 'done', 'unknown', 'active'])
  assert.deepEqual(progressStates(task('COMPLETED', { step: 'DISPATCHING' })), ['done', 'done', 'unknown', 'done'])
})

test('실제 0건의 중복 검수 보고가 있으면 실행한 단계로 반영한다', () => {
  const complete = task('COMPLETED', { step: 'DISPATCHING', dedupe: { done: 0, total: 0, linked: 0 } })
  assert.deepEqual(progressStates(complete), ['done', 'done', 'done', 'done'])
  assert.match(progressHeading(complete), /결과를 불러오고/)
  assert.doesNotMatch(progressHeading(complete), /발행/)
})

test('최종 응답만 있어도 보고되지 않은 단계를 전부 완료 처리하지 않는다', () => {
  assert.deepEqual(progressStates(task('COMPLETED')), ['unknown', 'unknown', 'unknown', 'unknown'])
  assert.deepEqual(progressStates(task('COMPLETED', { step: 'EXTRACTING' })), ['done', 'done', 'unknown', 'unknown'])
})

test('미확인·잘못된 건수와 실제 0건을 구분한다', () => {
  assert.equal(progressCount(0), '0건')
  assert.equal(progressCount(6, '명'), '6명')
  assert.equal(progressCount(1000), '1,000건')
  for (const value of [null, undefined, '', '0', -1, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(progressCount(value), '확인 중')
  }
})

test('아직 도착하지 않은 세부 정보는 임의로 추가하지 않는다', () => {
  assert.deepEqual(progressDetails(null), [])
  assert.deepEqual(progressDetails(undefined), [])
  const groups = progressDetails({ step: 'CONTEXT', context: { members: 0, issues: null, notionDocs: null } })
  assert.equal(groups.length, 1)
  assert.deepEqual(groups[0].values.map(({ value }) => value), ['0명', '확인 중', '확인 중'])
})

test('부분 세부 정보는 오류 없이 표시하고 제공된 누적 건수를 유지한다', () => {
  const groups = progressDetails({ step: 'DISPATCHING', context: {}, extraction: { todos: 2 }, dedupe: { done: 0, total: 2, linked: null } })
  assert.equal(groups.length, 3)
  assert.equal(groups[1].values[0].value, '2건')
  assert.equal(groups[1].values[1].value, '확인 중')
  assert.equal(groups[2].values[0].value, '0건 / 2건')
  assert.equal(groups[2].values[1].value, '확인 중')
})
