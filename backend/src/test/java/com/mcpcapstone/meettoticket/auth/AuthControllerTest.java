package com.mcpcapstone.meettoticket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.user.User;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@Test
	void 로그인하면_세션에_사용자_ID를_저장한다() throws Exception {
		User user = new User("pm01", "hash", "김PM");
		ReflectionTestUtils.setField(user, "id", 1L);
		given(authService.login(any())).willReturn(user);

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"pm01\",\"password\":\"password1\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loginId").value("pm01"))
				.andExpect(jsonPath("$.name").value("김PM"))
				.andExpect(request().sessionAttribute(SessionConst.LOGIN_USER_ID, 1L));
	}

	@Test
	void 로그인에_실패하면_401() throws Exception {
		given(authService.login(any())).willThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"pm01\",\"password\":\"wrong\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
				.andExpect(request().sessionAttributeDoesNotExist(SessionConst.LOGIN_USER_ID));
	}

	@Test
	void 가입_입력값이_잘못되면_400() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"a\",\"password\":\"password1\",\"name\":\"김PM\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.message").value("아이디는 영문 / 숫자 4~20자입니다."));
	}

	@Test
	void GitHub_계정_형식이_잘못되면_400() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"pm01\",\"password\":\"password1\",\"name\":\"김PM\",\"githubName\":\"-bad name\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.message").value("GitHub 계정 형식을 확인해 주세요."));
	}

	@Test
	void GitHub에_없는_계정으로_가입하면_400() throws Exception {
		given(authService.verifyGithubName(any())).willThrow(new BusinessException(ErrorCode.INVALID_GITHUB_ACCOUNT));

		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"pm01\",\"password\":\"password1\",\"name\":\"김PM\",\"githubName\":\"no-such-user\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_GITHUB_ACCOUNT"))
				.andExpect(request().sessionAttributeDoesNotExist(SessionConst.LOGIN_USER_ID));
	}

	@Test
	void 로그인하지_않으면_내_정보_조회는_401() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void 로그아웃하면_세션을_지운다() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, 1L);

		mockMvc.perform(post("/api/auth/logout").session(session))
				.andExpect(status().isNoContent());

		assertThat(session.isInvalid()).isTrue();
	}
}
