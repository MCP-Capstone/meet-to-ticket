package com.mcpcapstone.meettoticket.project;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.RepositoryPermissionGateway;
import com.mcpcapstone.meettoticket.agent.RepositoryPermissionGateway.MemberPermission;
import com.mcpcapstone.meettoticket.auth.GitHubAccountClient;
import com.mcpcapstone.meettoticket.common.JsonCodec;

// 프로젝트 POST/PUT 저장 시 멤버 저장소 권한 확인 (#165 후속). GET은 저장된 값만 반환한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectPermissionCheckTest {
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;
	@MockitoBean GitHubAccountClient github;
	@MockitoBean RepositoryPermissionGateway gateway;

	private String suffix;
	private MockHttpSession admin;
	private MockHttpSession granted;
	private long grantedId;
	private long deniedId;
	private long errorId;
	private long noAccountId;

	@BeforeEach
	void setUp() throws Exception {
		suffix = UUID.randomUUID().toString().substring(0, 8);
		when(github.findLogin(anyString())).thenAnswer(i -> Optional.of(i.getArgument(0)));
		admin = signup("adm" + suffix, "관리자" + suffix, null);
		granted = signup("gra" + suffix, "허용" + suffix, "gra-" + suffix);
		grantedId = me(granted);
		deniedId = me(signup("den" + suffix, "거부" + suffix, "den-" + suffix));
		errorId = me(signup("err" + suffix, "오류" + suffix, "err-" + suffix));
		noAccountId = me(signup("noa" + suffix, "미연결" + suffix, null));
		when(gateway.check(anyString(), anyString(), anyList())).thenAnswer(i -> {
			List<String> names = i.getArgument(2);
			return names.stream()
					.map(n -> new MemberPermission(n, n.startsWith("den-") ? "DENIED" : n.startsWith("err-") ? "ERROR" : "GRANTED"))
					.toList();
		});
	}

	private MockHttpSession signup(String loginId, String name, String githubName) throws Exception {
		String gh = githubName == null ? "" : ",\"githubName\":\"" + githubName + "\"";
		return (MockHttpSession) mvc.perform(post("/api/auth/signup").contentType("application/json")
						.content("{\"loginId\":\"%s\",\"password\":\"password1\",\"name\":\"%s\"%s}".formatted(loginId, name, gh)))
				.andExpect(status().isCreated())
				.andReturn().getRequest().getSession(false);
	}

	private long me(MockHttpSession session) throws Exception {
		String body = mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getContentAsString();
		return json.read(body).path("userId").longValue();
	}

	private String body(String repo, long... memberIds) {
		StringBuilder ids = new StringBuilder();
		for (long id : memberIds) ids.append(ids.isEmpty() ? "" : ",").append(id);
		return """
				{"name":"권한 확인","githubOwner":"MCP-Capstone","githubRepo":"%s","memberUserIds":[%s]}"""
				.formatted(repo, ids);
	}

	@Test
	void 등록하면_멤버별_권한을_저장하고_일부_ERROR면_github_연결도_ERROR다() throws Exception {
		String res = mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("repo-" + suffix, grantedId, deniedId, errorId, noAccountId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.members[?(@.userId==" + grantedId + ")].repositoryPermission").value("GRANTED"))
				.andExpect(jsonPath("$.members[?(@.userId==" + deniedId + ")].repositoryPermission").value("DENIED"))
				.andExpect(jsonPath("$.members[?(@.userId==" + errorId + ")].repositoryPermission").value(contains(nullValue())))
				.andExpect(jsonPath("$.members[?(@.userId==" + noAccountId + ")].repositoryPermission").value("NO_ACCOUNT"))
				.andExpect(jsonPath("$.connections.github").value("ERROR"))
				.andReturn().getResponse().getContentAsString();
		long id = json.read(res).path("projectId").longValue();

		// GitHub 계정이 없는 멤버는 호출 대상이 아니다.
		verify(gateway).check(eq("MCP-Capstone"), eq("repo-" + suffix),
				argThat((List<String> n) -> n.size() == 3 && n.containsAll(List.of("gra-" + suffix, "den-" + suffix, "err-" + suffix))));

		// 상세 조회는 외부 호출 없이 저장된 값을 반환한다.
		clearInvocations(gateway);
		mvc.perform(get("/api/projects/{id}", id).session(admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members[?(@.userId==" + deniedId + ")].repositoryPermission").value("DENIED"))
				.andExpect(jsonPath("$.connections.github").value("ERROR"));
		verifyNoInteractions(gateway);
	}

	@Test
	void 전부_성공하면_CONNECTED이고_편집하면_다시_확인한다() throws Exception {
		String res = mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("repo-" + suffix, grantedId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.connections.github").value("CONNECTED"))
				.andReturn().getResponse().getContentAsString();
		long id = json.read(res).path("projectId").longValue();

		// 저장소와 멤버 구성이 바뀌면 이전 결과 대신 새로 확인한 결과를 쓴다.
		mvc.perform(put("/api/projects/{id}", id).session(admin).contentType("application/json")
						.content(body("other-" + suffix, grantedId, deniedId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.members[?(@.userId==" + deniedId + ")].repositoryPermission").value("DENIED"))
				.andExpect(jsonPath("$.connections.github").value("CONNECTED"));
		verify(gateway).check(eq("MCP-Capstone"), eq("other-" + suffix), anyList());
	}

	@Test
	void Agent_전체_실패면_저장은_하되_권한은_null이고_github_연결은_ERROR다() throws Exception {
		when(gateway.check(anyString(), anyString(), anyList())).thenThrow(new AgentException(true));

		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("repo-" + suffix, grantedId, noAccountId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.members[?(@.userId==" + grantedId + ")].repositoryPermission").value(contains(nullValue())))
				.andExpect(jsonPath("$.members[?(@.userId==" + noAccountId + ")].repositoryPermission").value("NO_ACCOUNT"))
				.andExpect(jsonPath("$.connections.github").value("ERROR"));
	}

	@Test
	void 확인할_GitHub_계정이_없으면_Agent를_호출하지_않는다() throws Exception {
		mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content(body("repo-" + suffix, noAccountId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.connections.github").value(nullValue()));
		verifyNoInteractions(gateway);
	}
}
