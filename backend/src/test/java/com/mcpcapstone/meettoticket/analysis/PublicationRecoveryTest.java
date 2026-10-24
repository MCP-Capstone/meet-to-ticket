package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.analysis.PublicationLedger.Reservation;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 복구 워커(PublicationRecovery)가 멈춘 발행 기록만 골라 정리하고, 결과 모름을 잃지 않는지 확인한다 (#263 PR2).
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:publication-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@ActiveProfiles("test")
class PublicationRecoveryTest {
	private static final Duration STALE = Duration.ofMinutes(10);

	@Autowired PublicationRecovery recovery;
	@Autowired PublicationLedger ledger;
	@Autowired PublicationRepository publications;
	@Autowired PublishBatchRepository batches;
	@Autowired TicketStatusHistoryRepository history;
	@Autowired TransactionTemplate tx;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;
	@Autowired JdbcTemplate jdbc;

	private Project project;
	private ProjectMember member;
	private User user;

	@BeforeEach
	void setUp() {
		user = users.save(new User("pr" + UUID.randomUUID().toString().substring(0, 8), "hash", "복구"));
		project = projects.save(new Project("복구 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
		// 다른 테스트가 남긴 진행 중 행이 이 테스트의 결과 수에 섞이지 않게 모두 뒤로 미룬다.
		jdbc.update("update publications set updated_at = now() where status in ('RESERVED', 'SENT')");
	}

	private TaskItem ticket(PublishTarget target, TicketStatus status) {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 24), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "복구 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		t.changePublication(target, ProcessingType.CREATE);
		t.moveTo(status);
		return items.save(t);
	}

	private Reservation reserveIn(UUID ticketId, PublishTarget target, PublishOperation op, UUID batchId) {
		return tx.execute(s -> ledger.reserve(ticketId, target, op, batchId));
	}

	private PublishBatch batch(PublishBatchStatus status) {
		PublishBatch b = new PublishBatch(user, "LIVE");
		if (status != PublishBatchStatus.RUNNING) b.finish(status, "끝남");
		return batches.save(b);
	}

	// 발행 기록을 예약하고(batch로) 오래된 것으로 만든다. sent가 true면 접수 표시까지 한다.
	private PublishBatch reserve(TaskItem t, PublishOperation op, PublishBatchStatus batchStatus, boolean sent) {
		PublishBatch b = batch(batchStatus);
		assertThat(reserveIn(t.getId(), t.getPublishTarget(), op, b.getId())).isEqualTo(Reservation.RESERVED);
		if (sent) tx.executeWithoutResult(s -> ledger.markSent(b.getId()));
		age(t, op);
		return b;
	}

	private void age(TaskItem t, PublishOperation op) {
		jdbc.update("update publications set updated_at = ? where idempotency_key = ? and target = ?",
				OffsetDateTime.now().minusHours(1), Publication.keyOf(t.getId(), t.getPublishTarget(), op), t.getPublishTarget().name());
	}

	private Publication row(TaskItem t, PublishOperation op) {
		return publications.findByTargetAndIdempotencyKey(t.getPublishTarget(), Publication.keyOf(t.getId(), t.getPublishTarget(), op)).orElseThrow();
	}

	private Publication row(TaskItem t) { return row(t, PublishOperation.CREATE); }

	private TaskItem reload(TaskItem t) { return items.findById(t.getId()).orElseThrow(); }

	private void update(TaskItem t, java.util.function.Consumer<TaskItem> change) {
		tx.executeWithoutResult(s -> change.accept(items.findById(t.getId()).orElseThrow()));
	}

	// 티켓이 batch를 기다리는 중으로 만든다.
	private void waiting(TaskItem t, PublishBatch b) {
		update(t, i -> i.startPublishing(batches.findById(b.getId()).orElseThrow()));
	}

	@Test
	void 티켓이_묶음을_기다리는_중이면_오래됐어도_건드리지_않는다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		PublishBatch b = reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
		waiting(t, b);

		var summary = recovery.recoverStale(STALE, 100);

		assertThat(summary.scanned()).isZero();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);
		// 끝난 묶음이어도 티켓이 아직 기다리면 결과 조회/기한 정리의 몫이다 (워커는 티켓까지 건드리지 않는다).
		update(t, i -> {});
		PublishBatch done = batch(PublishBatchStatus.FAILED);
		jdbc.update("update publications set publish_batch_id = ? where publication_id = ?", done.getId(), row(t).getId());
		update(t, i -> i.startPublishing(batches.findById(done.getId()).orElseThrow()));
		assertThat(recovery.recoverStale(STALE, 100).scanned()).isZero();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);
	}

	@Test
	void 최근에_갱신된_진행_중_행은_후보가_아니다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.FAILED, true);
		jdbc.update("update publications set updated_at = now() where publication_id = ?", row(t).getId());

		assertThat(recovery.recoverStale(STALE, 100).scanned()).isZero();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);
	}

	@Test
	void 종결_상태_행은_후보가_아니다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.FAILED, true);
		jdbc.update("update publications set status = 'CONFIRMED' where publication_id = ?", row(t).getId());

		assertThat(recovery.recoverStale(STALE, 100).scanned()).isZero();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
	}

	@Test
	void 접수된_SENT가_티켓에_결과_없이_남으면_UNCERTAIN으로_보내고_티켓도_결과_모름_실패로_맞춘다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);

		var summary = recovery.recoverStale(STALE, 100);

		assertThat(summary.uncertain()).isEqualTo(1);
		var p = row(t);
		assertThat(p.getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(p.getErrorCode()).isEqualTo(PublicationRecovery.CODE_RECOVERED_UNCERTAIN);
		assertThat(p.isOutcomeUnknown()).isTrue();
		TaskItem after = reload(t);
		assertThat(after.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(after.isPublishOutcomeUnknown()).isTrue();
		assertThat(after.canRetryPublish()).isTrue();
		assertThat(history.findByTaskItem_IdOrderByCreatedAtAscIdAsc(t.getId())).anySatisfy(h -> {
			assertThat(h.getToStatus()).isEqualTo(TicketStatus.FAILED);
			assertThat(h.getReason()).isEqualTo(PublicationRecovery.CODE_RECOVERED_UNCERTAIN);
		});
	}

	@Test
	void 접수_표시_전에_멈춘_RESERVED도_보냈는지_모르므로_UNCERTAIN이다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, false);

		assertThat(recovery.recoverStale(STALE, 100).uncertain()).isEqualTo(1);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
	}

	@Test
	void 접수_취소가_이력에_남은_RESERVED만_보내기_전이_확실해_FAILED로_보낸다() {
		TaskItem cancelled = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(cancelled, PublishOperation.CREATE, PublishBatchStatus.FAILED, false);
		tx.executeWithoutResult(s -> history.save(new TicketStatusHistory(
				items.findById(cancelled.getId()).orElseThrow(), TicketStatus.PUBLISHING, TicketStatus.CONFIRMED, null,
				TicketHistoryRecorder.REASON_PUBLISH_NOT_ACCEPTED)));
		TaskItem plain = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(plain, PublishOperation.CREATE, PublishBatchStatus.FAILED, false);

		var summary = recovery.recoverStale(STALE, 100);

		assertThat(summary.failed()).isEqualTo(1);
		assertThat(summary.uncertain()).isEqualTo(1);
		assertThat(row(cancelled).getStatus()).isEqualTo(PublicationStatus.FAILED);
		assertThat(row(cancelled).getErrorCode()).isEqualTo(PublicationLedger.CODE_NOT_ACCEPTED);
		assertThat(row(plain).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		// 확실한 미전송은 티켓을 결과 모름으로 만들지 않는다.
		assertThat(reload(cancelled).isPublishOutcomeUnknown()).isFalse();
		// FAILED가 된 키는 다시 예약할 수 있다.
		assertThat(reserveIn(cancelled.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, UUID.randomUUID()))
				.isEqualTo(Reservation.RESERVED);
	}

	@Test
	void 이전_시도가_결과_모름이면_접수_취소_이력이_있어도_FAILED로_보내지_않는다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.FAILED, false);
		jdbc.update("update publications set outcome_unknown = true where publication_id = ?", row(t).getId());
		tx.executeWithoutResult(s -> history.save(new TicketStatusHistory(
				items.findById(t.getId()).orElseThrow(), TicketStatus.PUBLISHING, TicketStatus.CONFIRMED, null,
				TicketHistoryRecorder.REASON_PUBLISH_NOT_ACCEPTED)));

		recovery.recoverStale(STALE, 100);

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
	}

	@Test
	void 티켓에_이_묶음의_성공_결과가_남아_있으면_CONFIRMED로_마무리한다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		PublishBatch b = reserve(t, PublishOperation.CREATE, PublishBatchStatus.SUCCEEDED, true);
		waiting(t, b);
		update(t, i -> i.recordPublishResult(TicketStatus.PUBLISHED, ProcessingType.CREATE, 7, "https://github.com/owner/repo/issues/7", null, null, false, false));

		var summary = recovery.recoverStale(STALE, 100);

		assertThat(summary.settled()).isEqualTo(1);
		var p = row(t);
		assertThat(p.getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(p.getExternalId()).isEqualTo("7");
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
	}

	@Test
	void 티켓에_이_묶음의_확정_실패가_남아_있으면_FAILED로_마무리한다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		PublishBatch b = reserve(t, PublishOperation.CREATE, PublishBatchStatus.FAILED, true);
		waiting(t, b);
		update(t, i -> i.recordPublishResult(TicketStatus.FAILED, ProcessingType.CREATE, null, null, "GITHUB_RATE_LIMIT", "잠시 후", true, false));

		recovery.recoverStale(STALE, 100);

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.FAILED);
		assertThat(row(t).getErrorCode()).isEqualTo("GITHUB_RATE_LIMIT");
	}

	@Test
	void 티켓에_결과_모름_실패가_남아_있으면_UNCERTAIN으로_마무리한다() {
		TaskItem t = ticket(PublishTarget.NOTION, TicketStatus.CONFIRMED);
		PublishBatch b = reserve(t, PublishOperation.CREATE, PublishBatchStatus.FAILED, true);
		waiting(t, b);
		update(t, i -> i.recordPublishResult(TicketStatus.FAILED, ProcessingType.CREATE, null, null, "RESULT_TIMEOUT", "기한 초과", true, true));

		recovery.recoverStale(STALE, 100);

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(t).isOutcomeUnknown()).isTrue();
	}

	@Test
	void 티켓에_발행_생략이_남아_있으면_예약을_지운다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		PublishBatch b = reserve(t, PublishOperation.CREATE, PublishBatchStatus.SUCCEEDED, true);
		waiting(t, b);
		update(t, i -> i.recordPublishResult(TicketStatus.SKIPPED, ProcessingType.CREATE, null, null, null, "발행하지 않음", false, false));

		recovery.recoverStale(STALE, 100);

		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).isEmpty();
	}

	@Test
	void 알림_댓글_재전송_행은_티켓의_오류_코드로_마무리한다() {
		TaskItem ok = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		PublishBatch b1 = reserve(ok, PublishOperation.COMMENT, PublishBatchStatus.SUCCEEDED, true);
		waiting(ok, b1);
		update(ok, i -> i.recordPublishResult(TicketStatus.UPDATED, ProcessingType.UPDATE, 3, "https://github.com/o/r/issues/3", null, null, false, false));
		TaskItem failed = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		PublishBatch b2 = reserve(failed, PublishOperation.COMMENT, PublishBatchStatus.SUCCEEDED, true);
		waiting(failed, b2);
		update(failed, i -> i.recordPublishResult(TicketStatus.UPDATED, ProcessingType.UPDATE, 4, "https://github.com/o/r/issues/4", "COMMENT_FAILED", "댓글 실패", true, false));

		recovery.recoverStale(STALE, 100);

		assertThat(row(ok, PublishOperation.COMMENT).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(failed, PublishOperation.COMMENT).getStatus()).isEqualTo(PublicationStatus.FAILED);
	}

	@Test
	void 다시_돌려도_이미_정리한_행과_티켓은_바뀌지_않는다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
		recovery.recoverStale(STALE, 100);
		int historyCount = history.findByTaskItem_IdOrderByCreatedAtAscIdAsc(t.getId()).size();
		OffsetDateTime updatedAt = row(t).getUpdatedAt();

		var again = recovery.recoverStale(STALE, 100);

		assertThat(again.scanned()).isZero();
		assertThat(row(t).getUpdatedAt()).isEqualTo(updatedAt);
		assertThat(history.findByTaskItem_IdOrderByCreatedAtAscIdAsc(t.getId())).hasSize(historyCount);
	}

	@Test
	void 읽은_뒤_행이_다시_예약됐으면_조건부_전이가_0행이라_바꾸지_않는다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
		Publication stale = row(t); // 복구 워커가 읽은 시점의 행
		// 그 사이 다른 요청이 끝내고 같은 키로 새 시도를 시작했다 (시도 횟수가 늘어난다).
		jdbc.update("update publications set status = 'FAILED' where publication_id = ?", stale.getId());
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, UUID.randomUUID())).isEqualTo(Reservation.RESERVED);

		assertThat(ledger.recover(stale, PublicationStatus.UNCERTAIN, "X")).isFalse();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.RESERVED);
		assertThat(row(t).getAttemptCount()).isEqualTo(2);
	}

	@Test
	void 여러_인스턴스가_동시에_돌려도_행마다_한_번만_전이한다() throws Exception {
		List<TaskItem> tickets = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
			reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
			tickets.add(t);
		}
		var pool = Executors.newFixedThreadPool(3);
		var barrier = new CyclicBarrier(3);
		List<Future<PublicationRecovery.Summary>> runs = new ArrayList<>();
		for (int i = 0; i < 3; i++)
			runs.add(pool.submit(() -> {
				barrier.await();
				return recovery.recoverStale(STALE, 100);
			}));
		int uncertain = 0;
		for (var run : runs) uncertain += run.get().uncertain();
		pool.shutdown();

		assertThat(uncertain).isEqualTo(6);
		for (TaskItem t : tickets) {
			assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
			// 티켓 이력도 한 번만 남는다
			assertThat(history.findByTaskItem_IdOrderByCreatedAtAscIdAsc(t.getId()).stream()
					.filter(h -> PublicationRecovery.CODE_RECOVERED_UNCERTAIN.equals(h.getReason())).count()).isEqualTo(1);
		}
	}

	@Test
	void 한_번에_정리하는_건수는_limit로_제한한다() {
		for (int i = 0; i < 3; i++) reserve(ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED), PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);

		assertThat(recovery.recoverStale(STALE, 2).scanned()).isEqualTo(2);
		assertThat(recovery.recoverStale(STALE, 2).scanned()).isEqualTo(1);
	}

	// 대상별 후속 경로

	@Test
	void GitHub는_복구된_UNCERTAIN을_재대조와_함께_다시_예약할_수_있다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
		recovery.recoverStale(STALE, 100);

		TaskItem after = reload(t);
		assertThat(PublishResolution.of(after)).isEqualTo(PublishResolution.RETRY);
		assertThat(after.mustRecheckOnPublish()).isTrue(); // Agent가 발행 직전 재대조한다
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, UUID.randomUUID()))
				.isEqualTo(Reservation.RESERVED);
		assertThat(row(t).isOutcomeUnknown()).isTrue(); // 재예약해도 결과 모름 표시는 유지된다
	}

	@Test
	void Notion과_Slack은_복구된_UNCERTAIN을_수동_확인_전에는_다시_예약할_수_없고_확인하면_풀린다() {
		for (PublishTarget target : List.of(PublishTarget.NOTION, PublishTarget.SLACK)) {
			TaskItem t = ticket(target, TicketStatus.CONFIRMED);
			reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
			recovery.recoverStale(STALE, 100);

			TaskItem after = reload(t);
			assertThat(after.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
			assertThat(PublishResolution.of(after)).isEqualTo(PublishResolution.MANUAL_CHECK);
			assertThat(reserveIn(t.getId(), target, PublishOperation.CREATE, UUID.randomUUID()))
					.isEqualTo(Reservation.VERIFICATION_REQUIRED);

			// 담당자가 반영되지 않은 것을 확인하면 FAILED가 되고 결과 모름 표시도 풀린다.
			tx.executeWithoutResult(s -> {
				TaskItem managed = items.findById(t.getId()).orElseThrow();
				managed.resolveUnknownOutcome(false, null);
				ledger.resolveUncertain(managed, false, null);
			});
			assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.FAILED);
			assertThat(row(t).isOutcomeUnknown()).isFalse();
			assertThat(reserveIn(t.getId(), target, PublishOperation.CREATE, UUID.randomUUID()))
					.isEqualTo(Reservation.RESERVED);
		}
	}

	@Test
	void Notion_반영_확인이면_CONFIRMED로_끝난다() {
		TaskItem t = ticket(PublishTarget.NOTION, TicketStatus.CONFIRMED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);
		recovery.recoverStale(STALE, 100);

		tx.executeWithoutResult(s -> {
			TaskItem managed = items.findById(t.getId()).orElseThrow();
			managed.resolveUnknownOutcome(true, "https://www.notion.so/page");
			ledger.resolveUncertain(managed, true, "https://www.notion.so/page");
		});

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(t).isOutcomeUnknown()).isFalse();
	}

	@Test
	void 검토_대기로_되돌아간_티켓은_상태를_바꾸지_않고_지난_결과_모름만_남긴다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.PENDING_REVIEW);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.RUNNING, true);

		recovery.recoverStale(STALE, 100);

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		TaskItem after = reload(t);
		assertThat(after.getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(after.mustRecheckOnPublish()).isTrue();
	}

	// UNCERTAIN 보존 (PR1 리뷰 보완): 결과 모름이던 기록이 재예약된 뒤 확정 실패, 접수 취소, 발행 생략으로 끝나도 결과 모름이 남는다.

	private TaskItem uncertainThenReopened(PublishTarget target) {
		TaskItem t = ticket(target, TicketStatus.FAILED);
		reserve(t, PublishOperation.CREATE, PublishBatchStatus.FAILED, true);
		jdbc.update("update publications set status = 'UNCERTAIN', outcome_unknown = true where publication_id = ?", row(t).getId());
		return t;
	}

	@Test
	void 재예약된_UNCERTAIN이_확정_실패로_끝나도_UNCERTAIN을_유지한다() {
		TaskItem t = uncertainThenReopened(PublishTarget.GITHUB);
		PublishBatch b = batch(PublishBatchStatus.RUNNING);
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, b.getId())).isEqualTo(Reservation.RESERVED);
		tx.executeWithoutResult(s -> {
			TaskItem managed = items.findById(t.getId()).orElseThrow();
			managed.startPublishing(batches.findById(b.getId()).orElseThrow());
			// 티켓 쪽 결과 모름 표시가 이미 지워진 경우에도(표시가 없는 확정 실패) 발행 기록의 표시가 지켜 준다.
			managed.recordPublishResult(TicketStatus.FAILED, ProcessingType.CREATE, null, null, "GITHUB_VALIDATION", "거절", false, false);
			ledger.settle(managed, b.getId(), null, false);
		});

		var p = row(t);
		assertThat(p.getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(p.getErrorCode()).isEqualTo("GITHUB_VALIDATION");
		assertThat(p.isOutcomeUnknown()).isTrue();
	}

	@Test
	void 티켓의_지난_결과_모름_표시도_확정_실패를_UNCERTAIN으로_읽는다() {
		TaskItem t = ticket(PublishTarget.GITHUB, TicketStatus.FAILED);
		update(t, i -> i.recordPublishResult(TicketStatus.FAILED, ProcessingType.CREATE, null, null, "RESULT_TIMEOUT", "기한 초과", true, true));
		PublishBatch b = batch(PublishBatchStatus.RUNNING);
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, b.getId())).isEqualTo(Reservation.RESERVED);
		tx.executeWithoutResult(s -> {
			TaskItem managed = items.findById(t.getId()).orElseThrow();
			managed.startPublishing(batches.findById(b.getId()).orElseThrow());
			// 재시도가 확정 실패로 끝난다. 티켓은 previousOutcomeUnknown으로 지난 결과 모름을 이어 간다.
			managed.recordPublishResult(TicketStatus.FAILED, ProcessingType.CREATE, null, null, "GITHUB_VALIDATION", "거절", false, false);
			assertThat(managed.isPreviousOutcomeUnknown()).isTrue();
			ledger.settle(managed, b.getId(), null, false);
		});

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
	}

	@Test
	void 재예약된_UNCERTAIN이_접수_취소로_끝나도_UNCERTAIN을_유지한다() {
		TaskItem t = uncertainThenReopened(PublishTarget.GITHUB);
		PublishBatch b = batch(PublishBatchStatus.RUNNING);
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, b.getId())).isEqualTo(Reservation.RESERVED);

		tx.executeWithoutResult(s -> ledger.releaseBatch(b.getId()));

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(t).getErrorCode()).isEqualTo(PublicationLedger.CODE_NOT_ACCEPTED);
	}

	@Test
	void 재예약된_UNCERTAIN이_발행_생략으로_끝나도_예약을_지우지_않고_UNCERTAIN을_유지한다() {
		TaskItem t = uncertainThenReopened(PublishTarget.GITHUB);
		PublishBatch b = batch(PublishBatchStatus.RUNNING);
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, b.getId())).isEqualTo(Reservation.RESERVED);
		tx.executeWithoutResult(s -> {
			TaskItem managed = items.findById(t.getId()).orElseThrow();
			managed.startPublishing(batches.findById(b.getId()).orElseThrow());
			managed.recordPublishResult(TicketStatus.SKIPPED, ProcessingType.CREATE, null, null, null, "발행하지 않음", false, false);
			ledger.settle(managed, b.getId(), null, false);
		});

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
	}

	@Test
	void 재예약된_UNCERTAIN이_멈추면_복구_워커도_FAILED가_아니라_UNCERTAIN으로_보낸다() {
		TaskItem t = uncertainThenReopened(PublishTarget.GITHUB);
		PublishBatch b = batch(PublishBatchStatus.FAILED);
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, b.getId())).isEqualTo(Reservation.RESERVED);
		age(t, PublishOperation.CREATE);

		recovery.recoverStale(STALE, 100);

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(t).getAttemptCount()).isEqualTo(2);
	}

	@Test
	void 성공하면_결과_모름_표시가_풀린다() {
		TaskItem t = uncertainThenReopened(PublishTarget.GITHUB);
		PublishBatch b = batch(PublishBatchStatus.RUNNING);
		assertThat(reserveIn(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, b.getId())).isEqualTo(Reservation.RESERVED);
		tx.executeWithoutResult(s -> {
			TaskItem managed = items.findById(t.getId()).orElseThrow();
			managed.startPublishing(batches.findById(b.getId()).orElseThrow());
			managed.recordPublishResult(TicketStatus.PUBLISHED, ProcessingType.CREATE, 5, "https://github.com/o/r/issues/5", null, null, false, false);
			ledger.settle(managed, b.getId(), null, false);
		});

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(t).isOutcomeUnknown()).isFalse();
	}
}
