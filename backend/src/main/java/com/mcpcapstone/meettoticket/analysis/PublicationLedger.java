package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.PublishGateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 외부 발행의 멱등키 예약과 상태 기록 (#263, docs/publications.md).
// 호출한 쪽의 트랜잭션에 참여한다. PublishService가 티켓 상태를 바꾸는 같은 트랜잭션에서 예약하므로,
// 티켓이 발행 중으로 저장될 때만 예약도 함께 남는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationLedger {
    // 예약을 만들지 못한 이유. RESERVED만 Agent에 보내도 된다.
    public enum Reservation {
        RESERVED,
        ALREADY_CONFIRMED, // 같은 키가 이미 확정됐다. 다시 쓰면 외부에 두 번 반영된다
        IN_PROGRESS, // 같은 키의 다른 요청이 진행 중이다 (RESERVED, SENT)
        VERIFICATION_REQUIRED // 결과 모름이고 재대조할 수 없는 대상이다. 확인 결과(resolve-outcome)를 먼저 남겨야 한다
    }

    private static final Set<PublicationStatus> IN_FLIGHT = Set.of(PublicationStatus.RESERVED, PublicationStatus.SENT);
    private static final Set<PublicationStatus> UNCERTAIN_ONLY = Set.of(PublicationStatus.UNCERTAIN);
    // 티켓에는 outcomeUnknown=false인 실패로 저장되지만 대상에 이미 반영됐을 수 있는 코드다.
    // *_VERIFY_REQUIRED는 Agent가 결과 모름 뒤 재발행을 막은 것이고, MARKER_CHECK_INCOMPLETE는 표식을 끝까지 확인하지 못한 것이다.
    private static final Set<String> UNCERTAIN_CODES =
            Set.of("NOTION_VERIFY_REQUIRED", "SLACK_VERIFY_REQUIRED", "MARKER_CHECK_INCOMPLETE");

    public static final String CODE_NOT_ACCEPTED = "PUBLISH_NOT_ACCEPTED";

    private final PublicationRepository publications;

    // Agent에 보내기 전에 호출한다. RESERVED를 돌려받은 쪽만 외부 쓰기를 진행한다.
    // 키가 없으면 새로 예약하고, 있으면 끝난 행(FAILED, 재대조할 수 있는 대상의 UNCERTAIN)만 조건부 UPDATE로 다시 예약한다.
    // 동시에 같은 키를 예약하면 INSERT 충돌이나 UPDATE 조건 불일치로 한쪽만 RESERVED를 받는다.
    @Transactional
    public Reservation reserve(UUID ticketId, PublishTarget target, PublishOperation operation, UUID batchId) {
        String key = Publication.keyOf(ticketId, target, operation);
        Set<PublicationStatus> reopenable = target.recheckable()
                ? Set.of(PublicationStatus.FAILED, PublicationStatus.UNCERTAIN)
                : Set.of(PublicationStatus.FAILED);
        // 판단과 갱신 사이에 다른 요청이 행을 지우거나 상태를 바꿀 수 있어 몇 번 다시 시도한다.
        for (int attempt = 0; attempt < 3; attempt++) {
            OffsetDateTime now = OffsetDateTime.now();
            if (publications.insertReserved(ticketId, target.name(), operation.name(), key, batchId, now) == 1)
                return Reservation.RESERVED;
            if (publications.reopen(target, key, reopenable, batchId, now) == 1) return Reservation.RESERVED;
            Optional<PublicationStatus> current = publications.statusOf(target, key);
            if (current.isEmpty()) continue; // 그 사이 지워졌다
            switch (current.get()) {
                case CONFIRMED -> { return Reservation.ALREADY_CONFIRMED; }
                case RESERVED, SENT -> { return Reservation.IN_PROGRESS; }
                case UNCERTAIN -> { return Reservation.VERIFICATION_REQUIRED; }
                case FAILED -> { } // 그 사이 실패로 끝났다. 다시 예약한다
            }
        }
        return Reservation.IN_PROGRESS;
    }

    // Agent가 묶음을 접수했다(접수 확인에 실패해 접수 여부를 모르는 경우 포함). 보고가 이미 도착한 행은 바꾸지 않는다.
    @Transactional
    public void markSent(UUID batchId) {
        publications.markSent(batchId, OffsetDateTime.now());
    }

    // Agent가 접수하지 않은 것이 확실한 묶음의 예약을 실패로 돌려 다시 시도할 수 있게 한다.
    @Transactional
    public void releaseBatch(UUID batchId) {
        publications.failBatch(batchId, IN_FLIGHT, CODE_NOT_ACCEPTED, OffsetDateTime.now());
    }

    // 티켓에 발행 결과를 반영한 직후 부른다. item이 null이면 Agent 보고에 빠진 티켓이다.
    // 이 묶음으로 예약한 진행 중 행이 없으면(V5 이전에 시작된 묶음) 아무것도 하지 않는다.
    @Transactional
    public void settle(TaskItem ticket, UUID batchId, PublishGateway.Item item, boolean commentOnly) {
        Optional<Long> id = publications.findIdByBatch(ticket.getId(), batchId, IN_FLIGHT);
        if (id.isEmpty()) return;
        TicketStatus status = ticket.getTicketStatus();
        String code = ticket.getPublishErrorCode();
        OffsetDateTime now = OffsetDateTime.now();
        if (status == TicketStatus.SKIPPED) {
            // 발행 생략은 외부에 쓰지 않았다. 확정으로 남기지 않고 예약을 지운다.
            publications.discard(id.get(), IN_FLIGHT);
            return;
        }
        PublicationStatus to = commentOnly ? commentOutcome(item, code) : outcome(ticket, status, code);
        String externalId = null;
        String externalUrl = null;
        if (to == PublicationStatus.CONFIRMED) {
            externalId = item != null && item.notionPageId() != null ? item.notionPageId()
                    : ticket.getGithubIssueNumber() == null ? null : String.valueOf(ticket.getGithubIssueNumber());
            externalUrl = commentOnly && item != null && item.commentUrl() != null ? item.commentUrl() : ticket.getGithubIssueUrl();
        }
        finish(id.get(), to, IN_FLIGHT, code, externalId, externalUrl, now);
    }

    // 본문 발행(CREATE, LINK, UPDATE)의 티켓 결과를 발행 기록 상태로 옮긴다.
    private static PublicationStatus outcome(TaskItem ticket, TicketStatus status, String code) {
        return switch (status) {
            // 본문은 반영됐고 알림 댓글만 실패한 UPDATED도 본문 작업은 확정이다. 댓글은 COMMENT 작업이 따로 맡는다.
            case PUBLISHED, LINKED, UPDATED -> PublicationStatus.CONFIRMED;
            default -> ticket.isPublishOutcomeUnknown() || UNCERTAIN_CODES.contains(code)
                    ? PublicationStatus.UNCERTAIN : PublicationStatus.FAILED;
        };
    }

    // 알림 댓글 재전송의 결과. 댓글이 이미 있는지 끝까지 확인하지 못했거나 결과를 모르면 결과 모름이다.
    private static PublicationStatus commentOutcome(PublishGateway.Item item, String code) {
        if (item == null || "UNKNOWN".equals(item.status())) return PublicationStatus.UNCERTAIN;
        if (code == null) return PublicationStatus.CONFIRMED;
        return "COMMENT_FAILED".equals(code) ? PublicationStatus.FAILED : PublicationStatus.UNCERTAIN;
    }

    // 담당자가 결과 모름을 대상 도구에서 확인했다 (resolve-outcome). 반영됐으면 CONFIRMED, 아니면 FAILED로 끝내 다시 시도할 수 있게 한다.
    @Transactional
    public void resolveUncertain(TaskItem ticket, boolean applied, String url) {
        for (Long id : publications.findIds(ticket.getId(), ticket.getPublishTarget(), PublicationStatus.UNCERTAIN)) {
            if (applied) finish(id, PublicationStatus.CONFIRMED, UNCERTAIN_ONLY, null, null, url, OffsetDateTime.now());
            else finish(id, PublicationStatus.FAILED, UNCERTAIN_ONLY, TaskItem.VERIFIED_NOT_APPLIED, null, null, OffsetDateTime.now());
        }
    }

    // 전이 표(PublicationStatus.next)에 없는 전이는 코드 오류이므로 조건부 UPDATE를 보내기 전에 막는다.
    private void finish(Long id, PublicationStatus to, Set<PublicationStatus> from,
                        String errorCode, String externalId, String externalUrl, OffsetDateTime now) {
        for (PublicationStatus source : from)
            if (!source.canMoveTo(to)) throw new IllegalStateException("허용되지 않는 발행 상태 전이: " + source + " -> " + to);
        if (publications.finish(id, to, from, errorCode, externalId, externalUrl, now) == 0)
            log.warn("발행 기록 {}을 {}로 바꾸지 못함: 다른 요청이 먼저 상태를 바꿨습니다", id, to);
    }
}
