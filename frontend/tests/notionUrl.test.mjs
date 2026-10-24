import assert from 'node:assert/strict'
import test from 'node:test'
import { rolldown } from 'rolldown'
const bundle = await rolldown({ input: new URL('../src/notionUrl.ts', import.meta.url).pathname, platform: 'node' })
const generated = await bundle.generate({ format: 'esm' })
await bundle.close()
const { normalizeNotionPageUrl } = await import(`data:text/javascript;base64,${Buffer.from(generated.output[0].code).toString('base64')}`)

test('scheme 없는 Notion 주소에는 https://를 붙인다', () => {
  const id = '0123456789abcdef0123456789abcdef'
  assert.equal(normalizeNotionPageUrl(` www.notion.so/Plan-${id} `), `https://www.notion.so/Plan-${id}`)
  assert.equal(normalizeNotionPageUrl(`notion.so/${id}`), `https://notion.so/${id}`)
  assert.equal(normalizeNotionPageUrl(`team.notion.site/Plan-${id}`), `https://team.notion.site/Plan-${id}`)
  assert.equal(normalizeNotionPageUrl('NOTION.SO/x?v=1'), 'https://NOTION.SO/x?v=1')
})

test('이미 scheme이 있거나 Notion 도메인이 아닌 값은 trim만 한다', () => {
  assert.equal(normalizeNotionPageUrl(' https://www.notion.so/x '), 'https://www.notion.so/x')
  assert.equal(normalizeNotionPageUrl('http://www.notion.so/x'), 'http://www.notion.so/x')
  assert.equal(normalizeNotionPageUrl('example.com/notion.so'), 'example.com/notion.so')
  assert.equal(normalizeNotionPageUrl('notion.somewhere.com/x'), 'notion.somewhere.com/x')
  assert.equal(normalizeNotionPageUrl('   '), '')
})
