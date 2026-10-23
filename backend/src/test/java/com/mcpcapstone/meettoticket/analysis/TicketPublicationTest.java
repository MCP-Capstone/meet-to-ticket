package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import tools.jackson.databind.node.ObjectNode;

// 발행 대상과 처리 유형 변경, 중복 판단 결과 노출 (REQ-015, REQ-026, TASK-031).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketPublicationTest {
	@MockitoBean AnalysisTaskPublisher publisher;
	@Autowired AnalysisTaskListener listener;
	@Autowired AnalysisCoordinator coordinator;
	@Autowired TaskItemRepository items;
	@Autowired ProjectRepository projects;
	@Autowired UserRepository users;
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;

	private MockHttpSession me;
	private UUID ticket;

	@BeforeEach
	void setUp() throws Exception {
		me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
						.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
				.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
		Long projectId = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow().getId();
		String text = "# 발행 대상 테스트\n회의 날짜: 2026-10-22\n회의 참여자: 함주헌\n\n[구간 1] API를 내일 오후 6시까지 수정한다.\n";
		String upload = mvc.perform(multipart("/api/projects/{id}/meetings", projectId)
						.file(new MockMultipartFile("file", "p.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)))
						.session(me))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long meeting = json.read(upload).path("meetingId").longValue();
		String started = mvc.perform(post("/api/meetings/{id}/analysis-tasks", meeting).session(me))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID task = UUID.fromString(json.read(started).path("taskId").asText());
		listener.receive(task.toString());
		coordinator.pollPending();
		coordinator.pollPending();
		ticket = items.findByTaskIdOrderById(task).get(0).getId();
	}

	// 중복 판단 결과를 티켓 내용에 넣는다 (Agent 결과의 contentJson.dedupe와 같은 형식)
	private void judged(String relation, Integer number, String state) {
		TaskItem item = items.findById(ticket).orElseThrow();
		ObjectNode content = (ObjectNode) json.read(item.getContentJson());
		var dedupe = new java.util.HashMap<String, Object>();
		dedupe.put("relation", relation);
		dedupe.put("issue_number", number);
		dedupe.put("issue_state", state);
		dedupe.put("issue_title", "CSV 빈 결과 오류 수정");
		dedupe.put("reason", "같은 CSV 화면");
		dedupe.put("before", "CHANGE".equals(relation) ? "동기 처리" : null);
		dedupe.put("after", "CHANGE".equals(relation) ? "비동기 처리" : null);
		dedupe.put("skipped", null);
		content.set("dedupe", json.tree(dedupe));
		item.edit(null, json.write(content));
		items.save(item);
	}

	private ResultActions change(MockHttpSession session, String body) throws Exception {
		return mvc.perform(patch("/api/tickets/{id}/publication", ticket).session(session)
				.contentType("application/json").content(body));
	}

	@Test
	void 내_티켓에_중복_판단_결과와_겹침_경고를_보여준다() throws Exception {
		judged("RELATED", 12, "open");

		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].publishTarget".formatted(ticket)).value("GITHUB"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].dedupe.judged".formatted(ticket)).value(true))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].dedupe.relation".formatted(ticket)).value("RELATED"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].dedupe.overlap".formatted(ticket)).value(true))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].dedupe.issue.url".formatted(ticket))
						.value("https://github.com/MCP-Capstone/mcp-capstone/issues/12"));
	}

	@Test
	void 판단하지_않은_티켓은_판단_안_됨으로_표시한다() throws Exception {
		change(me, "{\"publishTarget\":\"GITHUB\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dedupe.judged").value(false))
				.andExpect(jsonPath("$.dedupe.issue").isEmpty())
				.andExpect(jsonPath("$.dedupe.overlap").value(false));
	}

	@Test
	void 열린_기존_이슈가_있으면_연결이나_갱신으로_바꾼다() throws Exception {
		judged("CHANGE", 12, "open");

		change(me, "{\"processingType\":\"UPDATE\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.processingType").value("UPDATE"))
				.andExpect(jsonPath("$.dedupe.before").value("동기 처리"))
				.andExpect(jsonPath("$.dedupe.after").value("비동기 처리"))
				.andExpect(jsonPath("$.dedupe.overlap").value(false));
		change(me, "{\"processingType\":\"CREATE\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dedupe.overlap").value(true)); // 갱신 대신 새로 만들면 겹칠 수 있다
		assertThat(items.findById(ticket).orElseThrow().getProcessingType()).isEqualTo(ProcessingType.CREATE);
	}

	@Test
	void GitHub_이외_대상은_신규_발행으로_고정한다() throws Exception {
		judged("SAME", 12, "open");
		change(me, "{\"processingType\":\"LINK\"}").andExpect(status().isOk());

		change(me, "{\"publishTarget\":\"NOTION\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.publishTarget").value("NOTION"))
				.andExpect(jsonPath("$.processingType").value("CREATE"));
		change(me, "{\"publishTarget\":\"SLACK\",\"processingType\":\"LINK\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PROCESSING_TYPE_TARGET_MISMATCH"));
		change(me, "{\"processingType\":\"LINK\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PROCESSING_TYPE_TARGET_MISMATCH"));
	}

	@Test
	void 기존_이슈가_없거나_닫혀_있으면_연결할_수_없다() throws Exception {
		change(me, "{\"processingType\":\"LINK\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXISTING_ISSUE_REQUIRED"));
		judged("SAME", 7, "closed");
		change(me, "{\"processingType\":\"LINK\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXISTING_ISSUE_REQUIRED"));
		judged("UNRELATED", null, null);
		change(me, "{\"processingType\":\"UPDATE\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXISTING_ISSUE_REQUIRED"));
	}

	@Test
	void 담당자가_아니거나_검토_대기가_아니면_바꿀_수_없다() throws Exception {
		User other = users.save(new User("pub" + UUID.randomUUID().toString().substring(0, 8), "hash", "다른 사람"));
		MockHttpSession otherSession = new MockHttpSession();
		otherSession.setAttribute(SessionConst.LOGIN_USER_ID, other.getId());

		change(otherSession, "{\"publishTarget\":\"NOTION\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TICKET_ASSIGNEE_REQUIRED"));
		change(me, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		change(me, "{\"publishTarget\":\"JIRA\"}")
				.andExpect(status().isBadRequest());

		mvc.perform(post("/api/tickets/{id}/confirm", ticket).session(me)).andExpect(status().isOk());
		change(me, "{\"publishTarget\":\"NOTION\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
	}

	@Test
	void 처리_유형_첫_값은_중복_판단의_제안을_따른다() {
		var withHandling = json.tree(Map.of("dedupe", Map.of("handling", Map.of("type", "UPDATE"))));
		assertThat(ProcessingType.from("CREATE", withHandling)).isEqualTo(ProcessingType.UPDATE);
		assertThat(ProcessingType.from("DUPLICATE", json.tree(Map.of()))).isEqualTo(ProcessingType.LINK);
		assertThat(ProcessingType.from("CREATE", null)).isEqualTo(ProcessingType.CREATE);
	}
}
