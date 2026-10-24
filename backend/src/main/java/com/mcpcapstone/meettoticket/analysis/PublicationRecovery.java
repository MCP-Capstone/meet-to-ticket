package com.mcpcapstone.meettoticket.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

// 크래시로 티켓/묶음 상태와 어긋난 채 RESERVED/SENT에 남은 발행 기록을 정리한다 (#263 PR2, docs/publications.md "복구 워커").
//
// 역할 경계: 티켓이 그 묶음을 기다리는 중(PUBLISHING)인 행은 건드리지 않는다. 그 행은 결과 조회(PublishService.reconcileRunning)와
// 기한 정리(PublishService.expireStale)가 묶음과 함께 끝낸다. 이 워커는 그 둘이 닿지 못하는 행, 즉 티켓이 더 이상 그 묶음을
// 기다리지 않는데 발행 기록만 진행 중으로 남은 행만 맡는다. 발행 시간 상한, Agent 재시작 처리, 결과 대기 기한은 다루지 않는다 (#302).
//
// 원칙: 외부에 이미 쓰였을 수 있는 건은 자동으로 다시 보내지 않는다. 티켓에 이 묶음의 결과가 남아 있으면 그 결과로 마무리하고,
// 그 밖에는 UNCERTAIN으로 보낸다. 보내기 전이 확실한 경우(접수 취소 기록이 있는 RESERVED)에만 FAILED다.
// 모든 전이는 조건부 UPDATE(CAS)이고 여러 인스턴스가 동시에 돌려도 한쪽만 바꾼다. 다시 돌려도 이미 정리된 행은 후보에서 빠진다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationRecovery {
    // 복구 워커가 UNCERTAIN으로 보낸 행과 티켓의 오류 코드.
    public static final String CODE_RECOVERED_UNCERTAIN = "PUBLISH_RECOVERY_UNCERTAIN";
    private static final String MESSAGE_UNCERTAIN =
            "발행 도중 서버가 중단되어 외부에 반영됐는지 알 수 없습니다. 대상에서 발행 여부를 확인한 뒤 다시 시도하세요.";
    private static final Set<TicketStatus> RESULT_STATES = Set.of(
            TicketStatus.PUBLISHED, TicketStatus.LINKED, TicketStatus.UPDATED, TicketStatus.SKIPPED, TicketStatus.FAILED);

    public enum Outcome {
        SKIPPED, // 정리할 필요가 없거나 다른 요청이 먼저 바꿨다
        SETTLED, // 티켓에 남은 이 묶음의 결과로 마무리했다
        UNCERTAIN, // 결과 모름으로 보냈다
        FAILED // 보내기 전이 확실해 실패로 보냈다
    }

    public record Summary(int scanned, int settled, int uncertain, int failed, int skipped) {}

    private final PublicationRepository publications;
    private final TaskItemRepository items;
    private final PublishBatchRepository batches;
    private final TicketStatusHistoryRepository history;
    private final TicketHistoryRecorder recorder;
    private final PublicationLedger ledger;
    private final TransactionTemplate tx;

    // updatedAt이 staleAfter보다 오래된 진행 중 행을 최대 limit건 정리한다. 행마다 별도 트랜잭션이라 한 건의 실패가 나머지를 막지 않는다.
    public Summary recoverStale(Duration staleAfter, int limit) {
        List<Long> ids = publications.findStaleInFlight(
                Set.of(PublicationStatus.RESERVED, PublicationStatus.SENT), OffsetDateTime.now().minus(staleAfter), PageRequest.of(0, limit));
        int settled = 0, uncertain = 0, failed = 0, skipped = 0;
        for (Long id : ids) {
            Outcome outcome;
            try {
                outcome = tx.execute(status -> recoverOne(id));
            } catch (RuntimeException e) {
                log.warn("발행 기록 {} 복구 실패, 다음 주기에 다시 시도: {}", id, e.getMessage());
                continue;
            }
            switch (outcome == null ? Outcome.SKIPPED : outcome) {
                case SETTLED -> settled++;
                case UNCERTAIN -> uncertain++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        if (settled + uncertain + failed > 0)
            log.warn("멈춘 발행 기록 정리: 후보 {}건, 티켓 결과로 마무리 {}, 결과 모름 {}, 실패(미전송 확실) {}, 건너뜀 {}",
                    ids.size(), settled, uncertain, failed, skipped);
        return new Summary(ids.size(), settled, uncertain, failed, skipped);
    }

    // 한 행의 복구. 티켓 행을 잠가 사용자 동작, 결과 반영과 줄 세운 뒤 현재 상태를 다시 읽어 판단한다.
    Outcome recoverOne(Long publicationId) {
        Publication row = publications.findById(publicationId).orElse(null);
        if (row == null || !row.getStatus().inFlight()) return Outcome.SKIPPED;
        TaskItem ticket = items.findForUpdate(row.getTaskItem().getId()).orElse(null);
        if (ticket == null) return Outcome.SKIPPED;
        // 잠그는 동안 결과 반영이 끝났을 수 있어 행을 다시 읽는다 (영속성 컨텍스트의 값은 낡았을 수 있다).
        PublicationStatus current = publications.statusOfId(publicationId).orElse(null);
        if (current == null || !current.inFlight()) return Outcome.SKIPPED;

        boolean sameBatch = ticket.getPublishBatch() != null && ticket.getPublishBatch().getId().equals(row.getPublishBatchId());
        if (sameBatch && ticket.getTicketStatus() == TicketStatus.PUBLISHING) {
            // 티켓이 아직 기다린다. 결과 조회와 기한 정리의 몫이다.
            batches.findById(row.getPublishBatchId()).filter(b -> b.getStatus().terminal()).ifPresent(b ->
                    log.warn("발행 기록 {}의 묶음 {}은 끝났는데 티켓 {}이 발행 중에 남아 있음, 복구 워커는 건드리지 않음",
                            publicationId, b.getId(), ticket.getId()));
            return Outcome.SKIPPED;
        }
        if (sameBatch && RESULT_STATES.contains(ticket.getTicketStatus())) {
            // 티켓에는 이 묶음의 결과가 반영됐는데 발행 기록만 진행 중으로 남았다. 결과로 마무리한다.
            ledger.settleFromTicket(publicationId, ticket, row.getOperation() == PublishOperation.COMMENT);
            return Outcome.SETTLED;
        }

        // 이 묶음의 결과가 어디에도 없다. 외부에 쓰였는지 알 수 없으므로 자동으로 다시 보내지 않는다.
        // 보내기 전이 확실한 경우: 아직 접수 표시 전(RESERVED, sent_at 없음)이고, 접수 취소가 이력에 남아 있으며,
        // 이전 시도의 결과 모름도 없다. 접수 취소를 처리하는 abort가 같은 트랜잭션에서 행을 FAILED로 돌리므로 보통은 후보가 되지 않고, 일부만 반영된 경우의 안전망이다.
        boolean notSent = row.getStatus() == PublicationStatus.RESERVED && row.getSentAt() == null
                && !publications.outcomeUnknownOf(publicationId).orElse(false) && !ticket.mustRecheckOnPublish()
                && history.existsByTaskItem_IdAndReasonAndCreatedAtGreaterThanEqual(
                        ticket.getId(), TicketHistoryRecorder.REASON_PUBLISH_NOT_ACCEPTED, row.getReservedAt());
        if (notSent) {
            return ledger.recover(row, PublicationStatus.FAILED, PublicationLedger.CODE_NOT_ACCEPTED) ? Outcome.FAILED : Outcome.SKIPPED;
        }
        if (!ledger.recover(row, PublicationStatus.UNCERTAIN, CODE_RECOVERED_UNCERTAIN)) return Outcome.SKIPPED;
        // 티켓도 결과 모름으로 맞춰야 다시 발행할 때 GitHub는 Agent가 재대조하고 Notion/Slack은 resolve-outcome을 거친다.
        TicketStatus from = ticket.getTicketStatus();
        if (ticket.markOutcomeUnknownByRecovery(CODE_RECOVERED_UNCERTAIN, MESSAGE_UNCERTAIN))
            recorder.statusChanged(ticket, from, null, CODE_RECOVERED_UNCERTAIN);
        return Outcome.UNCERTAIN;
    }
}
