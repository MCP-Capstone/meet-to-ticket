package com.mcpcapstone.meettoticket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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

	@Spy
	private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	@InjectMocks
	private AuthService authService;

	@Test
	void 가입하면_비밀번호를_해시로_저장한다() {
		given(userRepository.existsByLoginId("pm01")).willReturn(false);
		given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

		User user = authService.signup(new SignupRequest("pm01", "password1", "김PM"));

		assertThat(user.getPasswordHash()).isNotEqualTo("password1");
		assertThat(passwordEncoder.matches("password1", user.getPasswordHash())).isTrue();
	}

	@Test
	void 같은_아이디로_가입하면_실패한다() {
		given(userRepository.existsByLoginId("pm01")).willReturn(true);

		assertThatThrownBy(() -> authService.signup(new SignupRequest("pm01", "password1", "김PM")))
				.isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);
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
