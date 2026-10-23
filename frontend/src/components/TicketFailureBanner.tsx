interface Props {
  count: number
  onlyFailed: boolean
  busy: boolean
  stale: boolean
  onShowFailed: () => void
}

export function TicketFailureBanner({ count, onlyFailed, busy, stale, onShowFailed }: Props) {
  if (count <= 0) return null
  return <section className="ticket-failure-banner" aria-label="발행 실패 안내">
    <p role="status"><strong>발행에 실패한 티켓이 {count}건 있습니다.</strong></p>
    <p>티켓별 실패 사유를 확인하세요. 저장소 권한 문제는 프로젝트 관리자가 설정에서 확인해야 합니다.</p>
    {stale && <p className="muted">목록 조회에 실패해 이전 조회 결과를 표시하고 있습니다. 목록을 새로고침해 주세요.</p>}
    {!onlyFailed && <button type="button" className="secondary" disabled={busy} onClick={onShowFailed}>실패한 티켓만 보기</button>}
  </section>
}
