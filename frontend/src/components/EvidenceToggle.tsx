import type { ReactNode } from 'react'
import type { EvidenceExcerpt } from '../api'

// 티켓이 회의록 어디에서 나왔는지 접어 두었다가 펼쳐 본다. 기본은 닫혀 있고 근거 개수만 보인다.
// 펼치면 근거 문장이 들어 있는 회의록 원문 단락을 그 앞의 제목과 함께 보여 주고, 근거 문장은 강조한다.
// 원문 단락을 받지 못한 근거(전송 결과 화면 등)는 문장만 보여 준다.
export function EvidenceToggle({ quotes, excerpts = [] }: { quotes: string[]; excerpts?: EvidenceExcerpt[] }) {
  if (!quotes.length) return null
  const shown = new Set(excerpts.flatMap((excerpt) => excerpt.quotes))
  const rest = quotes.filter((quote) => !shown.has(quote))
  return <details className="evidence-toggle">
    <summary><span className="evidence-chevron" aria-hidden="true">›</span>회의 근거 보기 <span className="evidence-count">{quotes.length}</span></summary>
    {excerpts.map((excerpt, i) => <figure key={i} className="evidence-excerpt">
      {excerpt.heading && <figcaption>{excerpt.heading}</figcaption>}
      <blockquote>{highlight(excerpt.text, excerpt.quotes)}</blockquote>
    </figure>)}
    {rest.length > 0 && <ul>{rest.map((quote, i) => <li key={i}><blockquote>{quote}</blockquote></li>)}</ul>}
  </details>
}

// 단락 안의 근거 문장만 <mark>로 감싼다. 글자 그대로 찾지 못한 문장은 강조 없이 둔다.
function highlight(text: string, quotes: string[]): ReactNode[] {
  const marks = quotes.flatMap((quote) => { const at = text.indexOf(quote); return at >= 0 ? [{ at, end: at + quote.length }] : [] })
    .sort((a, b) => a.at - b.at)
  const out: ReactNode[] = []
  let last = 0
  for (const { at, end } of marks) {
    if (at < last) continue
    if (at > last) out.push(text.slice(last, at))
    out.push(<mark key={at}>{text.slice(at, end)}</mark>)
    last = end
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}
