interface Props {
  meetingTitle: string
}

export function ExtractingView({ meetingTitle }: Props) {
  return (
    <section className="card extracting">
      <div className="spinner" aria-hidden />
      <h2>할 일을 추출하고 있습니다</h2>
      <p className="muted">
        <strong>{meetingTitle}</strong> 회의록을 AI가 분석하고 있습니다.
        <br />
        회의록 길이에 따라 수십 초 걸릴 수 있습니다.
      </p>
    </section>
  )
}
