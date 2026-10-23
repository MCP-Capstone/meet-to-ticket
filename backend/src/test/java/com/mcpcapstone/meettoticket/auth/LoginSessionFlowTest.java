package com.mcpcapstone.meettoticket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mcpcapstone.meettoticket.user.UserRepository;

// 실제 서비스, BCrypt, DB로 로그인과 세션 흐름을 확인한다 (TASK-003). AuthControllerTest는 서비스를 mock으로 둔다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginSessionFlowTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository users;

	private String loginId;

	@BeforeEach
	void signup() throws Exception {
		loginId = "login" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/auth/signup").contentType("application/json")
						.content("{\"loginId\":\"%s\",\"password\":\"password1\",\"name\":\"로그인\"}".formatted(loginId)))
				.andExpect(status().isCreated());
	}

	private ResultActions login(MockHttpSession session, String id, String password) throws Exception {
		var request = post("/api/auth/login").contentType("application/json")
				.content("{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(id, password));
		return mvc.perform(session == null ? request : request.session(session));
	}

	private MockHttpSession sessionOf(ResultActions result) {
		return (MockHttpSession) result.andReturn().getRequest().getSession(false);
	}

	@Test
	void 비밀번호는_해시로_저장하고_해시로_대조한다() throws Exception {
		assertThat(users.findByLoginId(loginId).orElseThrow().getPasswordHash())
				.isNotEqualTo("password1").startsWith("$2");

		login(null, loginId, "password1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loginId").value(loginId));
	}

	@Test
	void 로그인부터_로그아웃까지_세션으로_접근을_제어한다() throws Exception {
		MockHttpSession session = sessionOf(login(null, loginId, "password1").andExpect(status().isOk()));

		mvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loginId").value(loginId));
		mvc.perform(get("/api/projects").session(session))
				.andExpect(status().isOk());

		mvc.perform(post("/api/auth/logout").session(session))
				.andExpect(status().isNoContent());
		assertThat(session.isInvalid()).isTrue();

		mvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/projects"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void 로그인하면_기존_세션을_버리고_새_세션을_만든다() throws Exception {
		MockHttpSession before = new MockHttpSession();
		before.setAttribute("anything", "x");

		MockHttpSession after = sessionOf(login(before, loginId, "password1").andExpect(status().isOk()));

		assertThat(before.isInvalid()).isTrue();
		assertThat(after.getId()).isNotEqualTo(before.getId());
		assertThat(after.getAttribute(SessionConst.LOGIN_USER_ID))
				.isEqualTo(users.findByLoginId(loginId).orElseThrow().getId());
		assertThat(after.getAttribute("anything")).isNull();
	}

	@Test
	void 없는_아이디와_틀린_비밀번호는_같은_응답이다() throws Exception {
		String wrongPassword = login(null, loginId, "wrong-password")
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
		String unknownId = login(null, "nobody" + loginId, "password1")
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		assertThat(unknownId).isEqualTo(wrongPassword).contains("LOGIN_FAILED");
	}

	@Test
	void 로그인에_실패하면_세션을_만들지_않는다() throws Exception {
		assertThat(sessionOf(login(null, loginId, "wrong-password"))).isNull();
	}
}
