-- V6. 발행 기록의 "결과 모름" 표시 (#263 PR2).
-- UNCERTAIN이던 기록이 재예약(reopen)된 뒤 확정 실패, 접수 취소, 발행 생략으로 끝나도 결과 모름이 사라지지 않게 한다.
-- true는 "이 키가 한 번이라도 UNCERTAIN이 됐고 아직 확인(CONFIRMED 또는 담당자의 반영 안 됨 확인)되지 않았다"는 뜻이다.
-- 이미 UNCERTAIN인 기존 행은 true로 채운다. 다른 상태의 기존 행은 false다.

ALTER TABLE publications ADD COLUMN outcome_unknown boolean NOT NULL DEFAULT false;
UPDATE publications SET outcome_unknown = true WHERE status = 'UNCERTAIN';
-- 복구 워커가 멈춘 진행 중 행을 오래된 순으로 찾는다.
CREATE INDEX idx_publications_status_updated_at ON publications (status, updated_at);
