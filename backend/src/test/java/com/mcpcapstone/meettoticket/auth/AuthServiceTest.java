package com.mcpcapstone.meettoticket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mcpcapstone.meettoticket.auth.dto.LoginRequest;
import com.mcpcapstone.meettoticket.auth.dto.SignupRequest;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private GitHubAccountClient gitHubAccountClient;

	@Spy
	private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	@InjectMocks
	private AuthService authService;

	@Test
	void 가입하면_비밀번호를_해시로_저장한다() {
		given(userRepository.existsByLoginId("pm01")).willReturn(false);
		given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

		User user = authService.signup(new SignupRequest("pm01", "password1", "김PM"), null);

		assertThat(user.getPasswordHash()).isNotEqualTo("password1");
		assertThat(passwordEncoder.matches("password1", user.getPasswordHash())).isTrue();
	}

	@Test
	void 같은_아이디로_가입하면_실패한다() {
		given(userRepository.existsByLoginId("pm01")).willReturn(true);

		assertThatThrownBy(() -> authService.signup(new SignupRequest("pm01", "password1", "김PM"), null))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);
	}

	@Test
	void GitHub_계정과_함께_가입하면_넘겨받은_값을_저장한다() {
		given(userRepository.existsByLoginId("pm01")).willReturn(false);
		given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

		User user = authService.signup(new SignupRequest("pm01", "password1", "김PM", "octocat"), "Octocat");

		assertThat(user.getGithubName()).isEqualTo("Octocat");
	}

	@Test
	void GitHub_계정을_입력하지_않으면_확인하지_않는다() {
		assertThat(authService.verifyGithubName(null)).isNull();
		assertThat(authService.verifyGithubName("  ")).isNull();
		then(gitHubAccountClient).should(never()).findLogin(any());
	}

	@Test
	void GitHub_계정이_있으면_GitHub_기준_표기를_돌려준다() {
		given(gitHubAccountClient.findLogin("octocat")).willReturn(Optional.of("Octocat"));

		assertThat(authService.verifyGithubName(" octocat ")).isEqualTo("Octocat");
	}

	@Test
	void GitHub에_없는_계정이면_실패한다() {
		given(gitHubAccountClient.findLogin("no-such-user")).willReturn(Optional.empty());

		assertThatThrownBy(() -> authService.verifyGithubName("no-such-user"))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.INVALID_GITHUB_ACCOUNT);
	}

	@Test
	void GitHub를_확인하지_못하면_입력값을_돌려준다() {
		given(gitHubAccountClient.findLogin("octocat"))
				.willThrow(new GitHubAccountClient.UnavailableException("HTTP 403"));

		assertThat(authService.verifyGithubName(" octocat ")).isEqualTo("octocat");
	}

	@Test
	void 비밀번호가_틀리면_로그인에_실패한다() {
		User user = new User("pm01", passwordEncoder.encode("password1"), "김PM");
		given(userRepository.findByLoginId("pm01")).willReturn(Optional.of(user));

		assertThatThrownBy(() -> authService.login(new LoginRequest("pm01", "wrong-password")))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.LOGIN_FAILED);
	}

	@Test
	void 아이디와_비밀번호가_맞으면_로그인한다() {
		User user = new User("pm01", passwordEncoder.encode("password1"), "김PM");
		given(userRepository.findByLoginId("pm01")).willReturn(Optional.of(user));

		assertThat(authService.login(new LoginRequest("pm01", "password1"))).isSameAs(user);
	}
}
