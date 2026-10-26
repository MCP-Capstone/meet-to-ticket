import type { ReactNode } from 'react'

// Agent가 만든 이슈 본문 Markdown을 읽기 전용으로 그린다 (TASK-024).
// 본문에는 회의록 원문이 섞여 있어 HTML로 넣지 않고(dangerouslySetInnerHTML 없음) React 요소로만 만든다.
// 그래서 회의록에 <script>나 onerror가 있어도 글자로만 보인다.
// 이슈 출력문(agent publisher.build_issue_body)이 쓰는 문법만 지원한다:
// ## 제목, - 목록, - [ ] 체크리스트, > 인용, 문단, <details><summary>…</summary> … </details>
// 문단·목록 안에서는 **굵게**, `코드`, [글자](https 링크)를 그린다.
// Slack 공지 문구(flavor="slack")는 *굵게*, • 목록, <링크|글자>를 같은 모양으로 바꿔 그린다.

type Flavor = 'markdown' | 'slack'
type Block =
  | { kind: 'heading'; text: string }
  | { kind: 'list'; items: { text: string; nested: boolean }[] }
  | { kind: 'tasks'; items: string[] }
  | { kind: 'quote'; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'rule' }
  | { kind: 'details'; summary: string; blocks: Block[] }

// 두 칸 이상 들여 쓴 항목은 바로 위 항목의 하위 항목으로 그린다.
const bullet = /^\s*(- |• |\* )/

function parse(lines: string[]): Block[] {
  const blocks: Block[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i].trimEnd()
    if (!line.trim()) {
      i++
    } else if (line.trim() === '<details>') {
      // <summary>…</summary> 다음부터 </details>까지를 접힌 블록으로 만든다
      const end = lines.findIndex((l, j) => j > i && l.trim() === '</details>')
      const inner = lines.slice(i + 1, end === -1 ? lines.length : end)
      const summary = inner[0]?.match(/^\s*<summary>(.*)<\/summary>\s*$/)?.[1] ?? '자세히'
      blocks.push({ kind: 'details', summary, blocks: parse(inner.slice(inner[0]?.includes('<summary>') ? 1 : 0)) })
      i = end === -1 ? lines.length : end + 1
    } else if (line.trim() === '---') {
      blocks.push({ kind: 'rule' })
      i++
    } else if (/^#{2,3} /.test(line)) {
      blocks.push({ kind: 'heading', text: line.replace(/^#{2,3} /, '') })
      i++
    } else if (line.startsWith('- [ ] ')) {
      const items: string[] = []
      while (i < lines.length && lines[i].startsWith('- [ ] ')) items.push(lines[i++].slice(6))
      blocks.push({ kind: 'tasks', items })
    } else if (bullet.test(line)) {
      const items: { text: string; nested: boolean }[] = []
      while (i < lines.length && bullet.test(lines[i]) && !lines[i].startsWith('- [ ] ')) {
        const item = lines[i++]
        items.push({ text: item.trimStart().slice(2), nested: /^\s{2,}/.test(item) && items.length > 0 })
      }
      blocks.push({ kind: 'list', items })
    } else if (line.startsWith('> ')) {
      blocks.push({ kind: 'quote', text: line.slice(2) })
      i++
    } else {
      // 첫 줄은 항상 넣는다 ("<details>뒤에 글자"처럼 위 규칙에 안 맞는 줄에서 멈추지 않게)
      const text: string[] = [lines[i++]]
      while (i < lines.length && lines[i].trim() && !bullet.test(lines[i]) && !/^(#{2,3} |> |<details>|---$)/.test(lines[i])) text.push(lines[i++])
      blocks.push({ kind: 'paragraph', text: text.join('\n') })
    }
  }
  return blocks
}

// 링크는 http(s)만 걸고, 나머지는 글자로 둔다.
const safeHref = (url: string) => /^https?:\/\//i.test(url) ? url : null

function inline(text: string, flavor: Flavor): ReactNode[] {
  const pattern = flavor === 'slack'
    ? /(\*[^*\n]+\*|`[^`\n]+`|<(https?:\/\/[^|>\s]+)\|([^>]+)>|<(https?:\/\/[^>\s]+)>)/g
    : /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[([^\]\n]+)\]\(([^)\s]+)\))/g
  const out: ReactNode[] = []
  let last = 0
  for (const match of text.matchAll(pattern)) {
    const [token] = match
    const at = match.index ?? 0
    if (at > last) out.push(text.slice(last, at))
    const key = out.length
    if (token.startsWith('`')) out.push(<code key={key}>{token.slice(1, -1)}</code>)
    else if (flavor === 'markdown' && token.startsWith('**')) out.push(<strong key={key}>{token.slice(2, -2)}</strong>)
    else if (flavor === 'slack' && token.startsWith('*')) out.push(<strong key={key}>{token.slice(1, -1)}</strong>)
    else {
      const [label, url] = flavor === 'slack' ? [match[3] ?? match[4], match[2] ?? match[4]] : [match[2], match[3]]
      const href = safeHref(url)
      out.push(href ? <a key={key} href={href} target="_blank" rel="noreferrer">{label}</a> : label)
    }
    last = at + token.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

function render(blocks: Block[], flavor: Flavor): ReactNode[] {
  return blocks.map((b, i) => {
    switch (b.kind) {
      case 'heading':
        return <h4 key={i}>{inline(b.text, flavor)}</h4>
      case 'tasks':
        return (
          <ul key={i} className="tasks">
            {b.items.map((t, j) => <li key={j}><span aria-hidden="true">☐</span> {inline(t, flavor)}</li>)}
          </ul>
        )
      case 'list':
        return <ul key={i}>{b.items.map((t, j) => <li key={j} className={t.nested ? 'nested' : undefined}>{inline(t.text, flavor)}</li>)}</ul>
      case 'quote':
        return <blockquote key={i}>{inline(b.text, flavor)}</blockquote>
      case 'paragraph':
        return <p key={i}>{inline(b.text, flavor)}</p>
      case 'rule':
        return <hr key={i} />
      case 'details':
        return (
          <details key={i}>
            <summary>{b.summary}</summary>
            {render(b.blocks, flavor)}
          </details>
        )
    }
  })
}

// 일부 저장 본문은 실제 줄바꿈 대신 문자 그대로의 "\n"을 담고 있어 줄바꿈으로 되돌린다.
const lines = (body: string) => body.replace(/\r/g, '').replace(/\\n/g, '\n').split('\n')

export function IssueMarkdown({ body, flavor = 'markdown' }: { body: string; flavor?: Flavor }) {
  return <div className="issue-markdown">{render(parse(lines(body)), flavor)}</div>
}
