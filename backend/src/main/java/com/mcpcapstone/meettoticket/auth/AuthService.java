package com.mcpcapstone.meettoticket.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mcpcapstone.meettoticket.auth.dto.LoginRequest;
import com.mcpcapstone.meettoticket.auth.dto.SignupRequest;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final GitHubAccountClient gitHubAccountClient;

	@Transactional
	public User signup(SignupRequest request, String githubName) {
		if (userRepository.existsByLoginId(request.loginId())) {
			throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
		}
		User user = new User(request.loginId(), passwordEncoder.encode(request.password()), request.name().trim(),
				githubName, blankToNull(request.slackEmail()));
		return userRepository.save(user);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	// 빈 값은 입력하지 않은 것으로 본다. GitHub에 없는 계정이면 가입을 막는다.
	// 호출 한도나 장애로 확인하지 못하면 입력값을 그대로 저장한다 (존재하는 계정을 입력한다고 가정).
	// 외부 호출 동안 DB 커넥션을 잡지 않도록 signup 트랜잭션 밖에서 먼저 호출한다.
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public String verifyGithubName(String githubName) {
		if (githubName == null || githubName.isBlank()) {
			return null;
		}
		String input = githubName.trim();
		try {
			return gitHubAccountClient.findLogin(input)
					.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_GITHUB_ACCOUNT));
		} catch (GitHubAccountClient.UnavailableException e) {
			log.warn("GitHub 계정을 확인하지 못해 입력값으로 저장합니다: {} ({})", input, e.getMessage());
			return input;
		}
	}

	// 아이디가 없을 때와 비밀번호가 틀릴 때 같은 에러를 돌려준다.
	public User login(LoginRequest request) {
		return userRepository.findByLoginId(request.loginId())
				.filter(user -> passwordEncoder.matches(request.password(), user.getPasswordHash()))
				.orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
	}

	public User getUser(Long userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
	}
}
