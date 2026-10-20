package com.mcpcapstone.meettoticket.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.mcpcapstone.meettoticket.auth.dto.LoginRequest;
import com.mcpcapstone.meettoticket.auth.dto.SignupRequest;
import com.mcpcapstone.meettoticket.auth.dto.UserResponse;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.user.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// JWT 없이 서버 세션(JSESSIONID 쿠키)으로 로그인 상태를 유지한다.
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	// 가입하면 바로 로그인 상태가 된다.
	@PostMapping("/signup")
	public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request,
			HttpServletRequest httpRequest) {
		User user = authService.signup(request);
		startSession(httpRequest, user);
		return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
	}

	@PostMapping("/login")
	public UserResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
		User user = authService.login(request);
		startSession(httpRequest, user);
		return UserResponse.from(user);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
		HttpSession session = httpRequest.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	public UserResponse me(@SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long userId) {
		if (userId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return UserResponse.from(authService.getUser(userId));
	}

	// 세션 고정 공격을 막기 위해 기존 세션을 버리고 새로 만든다.
	private void startSession(HttpServletRequest httpRequest, User user) {
		HttpSession oldSession = httpRequest.getSession(false);
		if (oldSession != null) {
			oldSession.invalidate();
		}
		httpRequest.getSession(true).setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
	}
}
