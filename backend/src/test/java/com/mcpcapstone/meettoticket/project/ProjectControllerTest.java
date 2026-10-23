package com.mcpcapstone.meettoticket.project;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.mcpcapstone.meettoticket.common.JsonCodec;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectControllerTest {
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;

	private String suffix;
	private MockHttpSession admin;
	private long adminId;
	private MockHttpSession member;
	private long memberId;
	private MockHttpSession outsider;

	@BeforeEach
	void signup() throws Exception {
		suffix = UUID.randomUUID().toString().substring(0, 8);
		admin = signup("adm" + suffix, "관리자" + suffix);
		adminId = me(admin);
		member = signup("mem" + suffix, "멤버" + suffix);
		memberId = me(member);
		outsider = signup("out" + suffix, "외부" + suffix);
	}

	private MockHttpSession signup(String loginId, String name) throws Exception {
		return (MockHttpSession) mvc.perform(post("/api/auth/signup").contentType("application/json")
						.content("{\"loginId\":\"%s\",\"password\":\"password1\",\"name\":\"%s\"}".formatted(loginId, name)))
				.andExpect(status().isCreated())
				.andReturn().getRequest().getSession(false);
	}

	private long me(MockHttpSession session) throws Exception {
		String body = mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getContentAsString();
		return json.read(body).path("userId").longValue();
	}

	private String body(String name, String repo, String notion, long... memberIds) {
		StringBuilder ids = new StringBuilder();
		for (long id : memberIds) ids.append(ids.isEmpty() ? "" : ",").append(id);
		return """
				{"name":"%s","description":"  ","githubOwner":" MCP-Capstone ","githubRepo":"%s",
				 "notionPageUrl":%s,"slackChannelId":null,"memberUserIds":[%s]}"""
				.formatted(name, repo, notion == null ? "null" : "\"" + notion + "\"", ids);
	}

	private long create(String repo, long... memberIds) throws Exception {
		String res = mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("CSV 내보내기", repo, null, memberIds)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return json.read(res).path("projectId").longValue();
	}

	@Test
	void 등록하면_등록자가_관리자이자_멤버가_되고_확인전_권한은_null이다() throws Exception {
		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("CSV 내보내기", "repo-" + suffix, null, memberId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.githubOwner").value("MCP-Capstone"))
				.andExpect(jsonPath("$.description").value(nullValue()))
				.andExpect(jsonPath("$.canEdit").value(true))
				.andExpect(jsonPath("$.members.length()").value(2))
				.andExpect(jsonPath("$.members[0].userId").value(adminId))
				.andExpect(jsonPath("$.members[0].admin").value(true))
				.andExpect(jsonPath("$.members[0].repositoryPermission").value("NO_ACCOUNT"))
				.andExpect(jsonPath("$.members[1].userId").value(memberId))
				.andExpect(jsonPath("$.members[1].admin").value(false))
				.andExpect(jsonPath("$.connections.github").value(nullValue()))
				.andExpect(jsonPath("$.connections.notion").value("NOT_CONNECTED"));

		mvc.perform(get("/api/projects").session(member))
				.andExpect(jsonPath("$.projects[*].name", hasItem("CSV 내보내기")));
	}

	@Test
	void 멤버는_조회만_하고_편집은_관리자만_한다() throws Exception {
		long id = create("repo-" + suffix, memberId);

		mvc.perform(get("/api/projects/{id}", id).session(member))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.canEdit").value(false));
		mvc.perform(put("/api/projects/{id}", id).session(member).contentType("application/json")
						.content(body("바꾼 이름", "repo-" + suffix, null, memberId)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ADMIN_REQUIRED"));
		mvc.perform(get("/api/projects/{id}", id).session(outsider))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
		mvc.perform(put("/api/projects/{id}", id).session(outsider).contentType("application/json")
						.content(body("바꾼 이름", "repo-" + suffix, null)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
	}

	@Test
	void 편집은_전체_멤버_목록으로_바꾸고_관리자는_빠져도_남는다() throws Exception {
		long id = create("repo-" + suffix, memberId);

		mvc.perform(put("/api/projects/{id}", id).session(admin).contentType("application/json")
						.content(body("바꾼 이름", "repo2-" + suffix, "https://www.notion.so/plan")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("바꾼 이름"))
				.andExpect(jsonPath("$.githubRepo").value("repo2-" + suffix))
				.andExpect(jsonPath("$.notionPageUrl").value("https://www.notion.so/plan"))
				.andExpect(jsonPath("$.connections.notion").value(nullValue()))
				.andExpect(jsonPath("$.members.length()").value(1))
				.andExpect(jsonPath("$.members[0].userId").value(adminId));

		mvc.perform(get("/api/projects/{id}", id).session(member))
				.andExpect(status().isForbidden());
	}

	@Test
	void 멤버의_Slack_이메일을_저장하고_입력없는_연결은_미연결로_돌려준다() throws Exception {
		long id = create("repo-" + suffix, memberId);
		String request = """
				{"name":"Slack 연결","description":null,"githubOwner":"MCP-Capstone","githubRepo":"repo-%s",
				 "notionPageUrl":null,"slackChannelId":"C0123456789","memberUserIds":[%d],
				 "memberSlackEmails":{"%d":"member@example.com"}}""".formatted(suffix, memberId, memberId);

		mvc.perform(put("/api/projects/{id}", id).session(admin).contentType("application/json").content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.connections.notion").value("NOT_CONNECTED"))
				.andExpect(jsonPath("$.connections.slack").value(nullValue()))
				.andExpect(jsonPath("$.members[1].slackEmail").value("member@example.com"))
				.andExpect(jsonPath("$.members[1].slackUserId").value(nullValue()));
	}

	@Test
	void 이미_등록된_저장소는_대소문자가_달라도_409다() throws Exception {
		create("repo-" + suffix);

		mvc.perform(post("/api/projects").session(member).contentType("application/json")
						.content(body("다른 프로젝트", "REPO-" + suffix, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PROJECT_REPOSITORY"));
	}

	@Test
	void 가입하지_않은_멤버나_잘못된_입력은_400이다() throws Exception {
		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("CSV 내보내기", "repo-" + suffix, null, 999_999_999L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEMBER_USER_NOT_FOUND"));
		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body(" ", "repo-" + suffix, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("프로젝트 이름을 입력해 주세요."));
		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("CSV 내보내기", "repo/" + suffix, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("CSV 내보내기", "repo-" + suffix, "http://notion.so")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Notion 페이지 URL은 https://로 시작해야 합니다."));
	}

	private org.springframework.test.web.servlet.ResultActions role(MockHttpSession session, long projectId, long target, String role) throws Exception {
		return mvc.perform(put("/api/projects/{id}/members/{userId}/role", projectId, target).session(session)
				.contentType("application/json").content("{\"role\":\"%s\"}".formatted(role)));
	}

	@Test
	void 관리자는_멤버를_관리자로_지정하고_해제한다() throws Exception {
		long id = create("repo-" + suffix, memberId);

		role(admin, id, memberId, "ADMIN")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members[1].userId").value(memberId))
				.andExpect(jsonPath("$.members[1].admin").value(true));
		mvc.perform(get("/api/projects/{id}", id).session(member))
				.andExpect(jsonPath("$.canEdit").value(true));

		role(member, id, adminId, "MEMBER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members[0].admin").value(false));
		mvc.perform(put("/api/projects/{id}", id).session(admin).contentType("application/json")
						.content(body("바꾼 이름", "repo-" + suffix, null, adminId, memberId)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ADMIN_REQUIRED"));
	}

	@Test
	void 마지막_관리자는_해제할_수_없다() throws Exception {
		long id = create("repo-" + suffix, memberId);

		role(admin, id, adminId, "MEMBER")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("LAST_PROJECT_ADMIN"));
		role(admin, id, memberId, "MEMBER")
				.andExpect(status().isOk());
		role(admin, id, memberId, "ADMIN")
				.andExpect(status().isOk());
		role(admin, id, adminId, "MEMBER")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.canEdit").value(false));
	}

	@Test
	void 관리자가_아니거나_멤버가_아닌_대상은_거절한다() throws Exception {
		long id = create("repo-" + suffix, memberId);
		long outsiderId = me(outsider);

		role(member, id, memberId, "ADMIN")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ADMIN_REQUIRED"));
		role(outsider, id, outsiderId, "ADMIN")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
		role(admin, id, outsiderId, "ADMIN")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PROJECT_MEMBER_NOT_FOUND"));
		role(admin, id, memberId, "OWNER")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 가입_사용자를_이름으로_검색한다() throws Exception {
		mvc.perform(get("/api/users").param("keyword", "멤버" + suffix).session(admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.users.length()").value(1))
				.andExpect(jsonPath("$.users[0].userId").value(memberId))
				.andExpect(jsonPath("$.users[0].githubName").value(nullValue()));
		mvc.perform(get("/api/users").param("keyword", "멤버" + suffix))
				.andExpect(status().isUnauthorized());
	}
}
