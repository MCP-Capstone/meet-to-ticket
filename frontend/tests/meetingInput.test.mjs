import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'
const source = await readFile(new URL('../src/meetingInput.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { meetingDateFromText } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)

test('회의록 머리의 회의 일시에서 날짜를 찾는다', () => {
  const text = '# 회의 요약 - 빈 결과 CSV 생성 오류 수정\n\n- 회의 ID: 30min_1\n- 회의 일시: 2026-10-22 10:00~10:30\n\n## [구간 2]\nCSV-08로 박종민이 23일 17시까지 수정한다.'
  assert.equal(meetingDateFromText(text), '2026-10-22')
})
test('점·슬래시·한글 표기와 한 자리 월일도 읽는다', () => {
  assert.equal(meetingDateFromText('# 회의\n일시: 2026. 10. 3'), '2026-10-03')
  assert.equal(meetingDateFromText('# 회의\n- 날짜: 2026/1/9 (금)'), '2026-01-09')
  assert.equal(meetingDateFromText('# 회의\n회의일: 2026년 10월 22일'), '2026-10-22')
})
test('날짜 칸이 없으면 제목 줄의 날짜를 쓴다', () => {
  assert.equal(meetingDateFromText('# 2026-10-24 전체 발행 흐름 검증 회의\n\n결정 사항'), '2026-10-24')
})
test('본문 속 날짜나 없는 날짜는 쓰지 않는다', () => {
  assert.equal(meetingDateFromText('# 회의\n\n2026-10-30까지 배포한다.'), null)
  assert.equal(meetingDateFromText('# 회의\n- 회의 일시: 2026-02-30'), null)
  assert.equal(meetingDateFromText(''), null)
})
