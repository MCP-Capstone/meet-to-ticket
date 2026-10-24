package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

// 발행 기록(publications)의 예약, 동시 경쟁, 상태 전이, 삭제 연쇄 (#263). Agent와 HTTP 없이 PublicationLedger를 직접 부른다.
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:publication-ledger;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@ActiveProfiles("test")
class PublicationLedgerTest {
	@Autowired PublicationLedger ledger;
	@Autowired PublicationRepository publications;
	@Autowired TransactionTemplate tx;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;

	private Project project;
	private ProjectMember member;
	private User user;

	@BeforeEach
	void setUp() {
		user = users.save(new User("pl" + UUID.randomUUID().toString().substring(0, 8), "hash", "발행 기록"));
		project = projects.save(new Project("기록 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
	}

	private TaskItem ticket() {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 23), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "기록 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		return items.save(t);
	}

	private TaskItem notionTicket() {
		TaskItem t = ticket();
		t.changePublication(PublishTarget.NOTION, ProcessingType.CREATE);
		return items.save(t);
	}

	private int rows(java.util.function.IntSupplier call) {
		return tx.execute(s -> call.getAsInt());
	}

	private Reservation reserveIn(java.util.function.Supplier<Reservation> call) {
		return tx.execute(s -> call.get());
	}

	private Reservation reserve(TaskItem t, PublishTarget target, PublishOperation op) {
		return tx.execute(s -> ledger.reserve(t.getId(), target, op, UUID.randomUUID()));
	}

	private Publication row(TaskItem t, PublishTarget target, PublishOperation op) {
		return publications.findByTargetAndIdempotencyKey(target, Publication.keyOf(t.getId(), target, op)).orElseThrow();
	}

	private void finish(TaskItem t, PublishTarget target, PublishOperation op, PublicationStatus to) {
		var p = row(t, target, op);
		var from = java.util.Arrays.stream(PublicationStatus.values()).filter(s -> s.canMoveTo(to)).toList();
		assertThat(rows(() -> publications.finish(p.getId(), to, from, null, null, null, java.time.OffsetDateTime.now()))).isEqualTo(1);
	}

	@Test
	void 처음_예약은_RESERVED_한_줄을_만들고_같은_키의_두_번째_예약은_거절한다() {
		TaskItem t = ticket();

		assertThat(reserve(t, PublishTarget.GITHUB, PublishOperation.CREATE)).isEqualTo(Reservation.RESERVED);
		assertThat(reserve(t, PublishTarget.GITHUB, PublishOperation.CREATE)).isEqualTo(Reservation.IN_PROGRESS);

		var p = row(t, PublishTarget.GITHUB, PublishOperation.CREATE);
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
		assertThat(p.getStatus()).isEqualTo(PublicationStatus.RESERVED);
		assertThat(p.getAttemptCount()).isEqualTo(1);
		assertThat(p.getIdempotencyKey()).isEqualTo(Publication.keyOf(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE));
		assertThat(p.getReservedAt()).isNotNull();
		assertThat(p.getSentAt()).isNull();
	}

	@Test
	void 유니크_충돌_INSERT는_예외_없이_0행이고_행은_하나로_남는다() {
		TaskItem t = ticket();
		String key = Publication.keyOf(t.getId(), PublishTarget.GITHUB, PublishOperation.CREATE);
		var now = java.time.OffsetDateTime.now();

		assertThat(rows(() -> publications.insertReserved(t.getId(), "GITHUB", "CREATE", key, null, now))).isEqualTo(1);
		assertThat(rows(() -> publications.insertReserved(t.getId(), "GITHUB", "CREATE", key, null, now))).isEqualTo(0);

		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
	}

	@Test
	void 대상이나_작업이_다르면_서로_다른_키라_따로_예약한다() {
		TaskItem t = ticket();

		assertThat(reserve(t, PublishTarget.GITHUB, PublishOperation.UPDATE)).isEqualTo(Reservation.RESERVED);
		assertThat(reserve(t, PublishTarget.GITHUB, PublishOperation.COMMENT)).isEqualTo(Reservation.RESERVED);
		assertThat(reserve(t, PublishTarget.NOTION, PublishOperation.CREATE)).isEqualTo(Reservation.RESERVED);
		assertThat(reserve(ticket(), PublishTarget.GITHUB, PublishOperation.UPDATE)).isEqualTo(Reservation.RESERVED);

		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).extracting(Publication::getOperation)
				.containsExactly(PublishOperation.UPDATE, PublishOperation.COMMENT, PublishOperation.CREATE);
	}

	@Test
	void 같은_키를_동시에_예약하면_한쪽만_성공한다() throws Exception {
		for (int round = 0; round < 5; round++) {
			TaskItem t = ticket();
			assertThat(race(8, () -> reserve(t, PublishTarget.GITHUB, PublishOperation.CREATE)))
					.containsOnlyOnce(Reservation.RESERVED)
					.filteredOn(r -> r != Reservation.RESERVED).containsOnly(Reservation.IN_PROGRESS);
			assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
		}
	}

	@Test
	void 실패한_키를_동시에_다시_예약해도_한쪽만_새_시도를_시작한다() throws Exception {
		for (PublishTarget target : List.of(PublishTarget.GITHUB, PublishTarget.NOTION)) {
			TaskItem t = ticket();
			reserve(t, target, PublishOperation.CREATE);
			finish(t, target, PublishOperation.CREATE, PublicationStatus.FAILED);

			assertThat(race(8, () -> reserve(t, target, PublishOperation.CREATE)))
					.containsOnlyOnce(Reservation.RESERVED)
					.filteredOn(r -> r != Reservation.RESERVED).containsOnly(Reservation.IN_PROGRESS);
			assertThat(row(t, target, PublishOperation.CREATE).getAttemptCount()).isEqualTo(2);
		}
	}

	private List<Reservation> race(int threads, java.util.concurrent.Callable<Reservation> call) throws Exception {
		var pool = Executors.newFixedThreadPool(threads);
		var barrier = new CyclicBarrier(threads);
		try {
			var futures = new ArrayList<java.util.concurrent.Future<Reservation>>();
			for (int i = 0; i < threads; i++)
				futures.add(pool.submit(() -> { barrier.await(); return call.call(); }));
			var results = new ArrayList<Reservation>();
			for (var f : futures) results.add(f.get());
			return results;
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void 확정은_같은_키에서_종결이라_다시_예약할_수_없다() {
		TaskItem t = ticket();
		reserve(t, PublishTarget.GITHUB, PublishOperation.CREATE);
		finish(t, PublishTarget.GITHUB, PublishOperation.CREATE, PublicationStatus.CONFIRMED);

		for (PublishTarget target : List.of(PublishTarget.GITHUB)) {
			assertThat(reserve(t, target, PublishOperation.CREATE)).isEqualTo(Reservation.ALREADY_CONFIRMED);
		}
		assertThat(row(t, PublishTarget.GITHUB, PublishOperation.CREATE).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(t, PublishTarget.GITHUB, PublishOperation.CREATE).getAttemptCount()).isEqualTo(1);
	}

	@Test
	void 실패한_키는_같은_키로_다시_예약하고_시도_횟수가_늘어난다() {
		TaskItem t = ticket();
		reserve(t, PublishTarget.NOTION, PublishOperation.CREATE);
		finish(t, PublishTarget.NOTION, PublishOperation.CREATE, PublicationStatus.FAILED);
		var batch = UUID.randomUUID();

		assertThat(reserveIn(() -> ledger.reserve(t.getId(), PublishTarget.NOTION, PublishOperation.CREATE, batch))).isEqualTo(Reservation.RESERVED);

		var p = row(t, PublishTarget.NOTION, PublishOperation.CREATE);
		assertThat(p.getStatus()).isEqualTo(PublicationStatus.RESERVED);
		assertThat(p.getAttemptCount()).isEqualTo(2);
		assertThat(p.getPublishBatchId()).isEqualTo(batch);
		assertThat(p.getErrorCode()).isNull();
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
	}

	@Test
	void 결과_모름은_재대조할_수_있는_GitHub만_다시_예약하고_Notion_Slack은_확인_전에_막는다() {
		for (PublishTarget target : List.of(PublishTarget.NOTION, PublishTarget.SLACK)) {
			TaskItem t = ticket();
			reserve(t, target, PublishOperation.CREATE);
			finish(t, target, PublishOperation.CREATE, PublicationStatus.UNCERTAIN);

			assertThat(reserve(t, target, PublishOperation.CREATE)).isEqualTo(Reservation.VERIFICATION_REQUIRED);
			assertThat(row(t, target, PublishOperation.CREATE).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
			assertThat(row(t, target, PublishOperation.CREATE).getAttemptCount()).isEqualTo(1);
		}
		TaskItem github = ticket();
		reserve(github, PublishTarget.GITHUB, PublishOperation.CREATE);
		finish(github, PublishTarget.GITHUB, PublishOperation.CREATE, PublicationStatus.UNCERTAIN);

		assertThat(reserve(github, PublishTarget.GITHUB, PublishOperation.CREATE)).isEqualTo(Reservation.RESERVED);
		assertThat(row(github, PublishTarget.GITHUB, PublishOperation.CREATE).getAttemptCount()).isEqualTo(2);
	}

	@Test
	void 확인_결과로_결과_모름을_끝내면_반영은_확정으로_미반영은_실패로_다시_시도할_수_있다() {
		TaskItem applied = notionTicket();
		reserve(applied, PublishTarget.NOTION, PublishOperation.CREATE);
		finish(applied, PublishTarget.NOTION, PublishOperation.CREATE, PublicationStatus.UNCERTAIN);
		TaskItem notApplied = notionTicket();
		reserve(notApplied, PublishTarget.NOTION, PublishOperation.CREATE);
		finish(notApplied, PublishTarget.NOTION, PublishOperation.CREATE, PublicationStatus.UNCERTAIN);

		tx.executeWithoutResult(s -> ledger.resolveUncertain(applied, true, "https://app.notion.com/p/page"));
		tx.executeWithoutResult(s -> ledger.resolveUncertain(notApplied, false, null));

		var confirmed = row(applied, PublishTarget.NOTION, PublishOperation.CREATE);
		assertThat(confirmed.getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(confirmed.getExternalUrl()).isEqualTo("https://app.notion.com/p/page");
		assertThat(reserve(applied, PublishTarget.NOTION, PublishOperation.CREATE)).isEqualTo(Reservation.ALREADY_CONFIRMED);
		assertThat(row(notApplied, PublishTarget.NOTION, PublishOperation.CREATE).getStatus()).isEqualTo(PublicationStatus.FAILED);
		assertThat(row(notApplied, PublishTarget.NOTION, PublishOperation.CREATE).getErrorCode()).isEqualTo(TaskItem.VERIFIED_NOT_APPLIED);
		assertThat(reserve(notApplied, PublishTarget.NOTION, PublishOperation.CREATE)).isEqualTo(Reservation.RESERVED);
	}

	@Test
	void 불허_전이는_행을_바꾸지_않는다() {
		TaskItem t = ticket();
		reserve(t, PublishTarget.GITHUB, PublishOperation.CREATE);
		finish(t, PublishTarget.GITHUB, PublishOperation.CREATE, PublicationStatus.CONFIRMED);
		var p = row(t, PublishTarget.GITHUB, PublishOperation.CREATE);
		var now = java.time.OffsetDateTime.now();

		// 확정 -> 실패/예약/접수/결과 모름은 조건(현재 상태)이 맞지 않아 0행이다.
		for (PublicationStatus to : List.of(PublicationStatus.FAILED, PublicationStatus.UNCERTAIN, PublicationStatus.SENT, PublicationStatus.RESERVED)) {
			var from = java.util.Arrays.stream(PublicationStatus.values()).filter(s -> s != PublicationStatus.CONFIRMED && s.canMoveTo(to)).toList();
			assertThat(rows(() -> publications.finish(p.getId(), to, from, "X", null, null, now))).isZero();
		}
		assertThat(rows(() -> publications.markSent(p.getPublishBatchId() == null ? UUID.randomUUID() : p.getPublishBatchId(), now))).isZero();
		assertThat(rows(() -> publications.reopen(PublishTarget.GITHUB, p.getIdempotencyKey(), List.of(PublicationStatus.FAILED, PublicationStatus.UNCERTAIN), UUID.randomUUID(), now))).isZero();
		assertThat(rows(() -> publications.discard(p.getId(), List.of(PublicationStatus.RESERVED, PublicationStatus.SENT)))).isZero();

		assertThat(row(t, PublishTarget.GITHUB, PublishOperation.CREATE).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(t, PublishTarget.GITHUB, PublishOperation.CREATE).getErrorCode()).isNull();
	}

	@Test
	void 접수_표시는_예약만_SENT로_바꾸고_이미_끝난_행은_두지_않는다() {
		TaskItem reserved = ticket();
		TaskItem done = ticket();
		var batch = UUID.randomUUID();
		tx.executeWithoutResult(s -> {
			ledger.reserve(reserved.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, batch);
			ledger.reserve(done.getId(), PublishTarget.GITHUB, PublishOperation.CREATE, batch);
		});
		finish(done, PublishTarget.GITHUB, PublishOperation.CREATE, PublicationStatus.CONFIRMED);

		tx.executeWithoutResult(s -> ledger.markSent(batch));

		var sent = row(reserved, PublishTarget.GITHUB, PublishOperation.CREATE);
		assertThat(sent.getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(sent.getSentAt()).isNotNull();
		assertThat(row(done, PublishTarget.GITHUB, PublishOperation.CREATE).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(done, PublishTarget.GITHUB, PublishOperation.CREATE).getSentAt()).isNull();
		// 접수된 행은 진행 중이라 같은 키를 다시 예약할 수 없다
		assertThat(reserve(reserved, PublishTarget.GITHUB, PublishOperation.CREATE)).isEqualTo(Reservation.IN_PROGRESS);
	}

	@Test
	void 티켓을_지우면_발행_기록도_함께_지워진다() {
		TaskItem t = ticket();
		reserve(t, PublishTarget.GITHUB, PublishOperation.CREATE);
		reserve(t, PublishTarget.GITHUB, PublishOperation.COMMENT);
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(2);

		// 프로젝트 삭제와 같은 일괄 삭제(JPQL)다. DB의 ON DELETE CASCADE가 발행 기록을 지운다.
		tx.executeWithoutResult(s -> items.deleteByProjectId(project.getId()));

		assertThat(items.findById(t.getId())).isEmpty();
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).isEmpty();
	}
}
