import type { ReactNode } from 'react'

// Agent가 만든 이슈 본문 Markdown을 읽기 전용으로 그린다 (TASK-024).
// 본문에는 회의록 원문이 섞여 있어 HTML로 넣지 않고(dangerouslySetInnerHTML 없음) React 요소로만 만든다.
// 그래서 회의록에 <script>나 onerror가 있어도 글자로만 보인다.
// 이슈 출력문(agent publisher.build_issue_body)이 쓰는 문법만 지원한다:
// ## 제목, - 목록, - [ ] 체크리스트, > 인용, 문단, <details><summary>…</summary> … </details>

type Block =
  | { kind: 'heading'; text: string }
  | { kind: 'list'; items: string[] }
  | { kind: 'tasks'; items: string[] }
  | { kind: 'quote'; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'rule' }
  | { kind: 'details'; summary: string; blocks: Block[] }

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
    } else if (line.startsWith('## ')) {
      blocks.push({ kind: 'heading', text: line.slice(3) })
      i++
    } else if (line.startsWith('- [ ] ')) {
      const items: string[] = []
      while (i < lines.length && lines[i].startsWith('- [ ] ')) items.push(lines[i++].slice(6))
      blocks.push({ kind: 'tasks', items })
    } else if (line.startsWith('- ')) {
      const items: string[] = []
      while (i < lines.length && lines[i].startsWith('- ')) items.push(lines[i++].slice(2))
      blocks.push({ kind: 'list', items })
    } else if (line.startsWith('> ')) {
      blocks.push({ kind: 'quote', text: line.slice(2) })
      i++
    } else {
      // 첫 줄은 항상 넣는다 ("<details>뒤에 글자"처럼 위 규칙에 안 맞는 줄에서 멈추지 않게)
      const text: string[] = [lines[i++]]
      while (i < lines.length && lines[i].trim() && !/^(## |- |> |<details>|---$)/.test(lines[i])) text.push(lines[i++])
      blocks.push({ kind: 'paragraph', text: text.join('\n') })
    }
  }
  return blocks
}

function render(blocks: Block[]): ReactNode[] {
  return blocks.map((b, i) => {
    switch (b.kind) {
      case 'heading':
        return <h4 key={i}>{b.text}</h4>
      case 'tasks':
        return (
          <ul key={i} className="tasks">
            {b.items.map((t, j) => <li key={j}><span aria-hidden="true">☐</span> {t}</li>)}
          </ul>
        )
      case 'list':
        return <ul key={i}>{b.items.map((t, j) => <li key={j}>{t}</li>)}</ul>
      case 'quote':
        return <blockquote key={i}>{b.text}</blockquote>
      case 'paragraph':
        return <p key={i}>{b.text}</p>
      case 'rule':
        return <hr key={i} />
      case 'details':
        return (
          <details key={i}>
            <summary>{b.summary}</summary>
            {render(b.blocks)}
          </details>
        )
    }
  })
}

export function IssueMarkdown({ body }: { body: string }) {
  return <div className="issue-markdown">{render(parse(body.split('\n')))}</div>
}
