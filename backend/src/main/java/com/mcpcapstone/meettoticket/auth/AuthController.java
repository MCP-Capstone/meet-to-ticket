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
import com.mcpcapstone.meettoticket.common.error.ErrorResponse;
import com.mcpcapstone.meettoticket.user.User;

import com.mcpcapstone.meettoticket.common.config.ApiExamples;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// JWT 없이 서버 세션(JSESSIONID 쿠키)으로 로그인 상태를 유지한다.
@Tag(name = "인증", description = "회원가입 / 로그인 / 로그아웃 (세션 쿠키)")
@SecurityRequirements
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private static final String SET_COOKIE_DESCRIPTION = "JSESSIONID=...; Path=/; HttpOnly (로그인 세션, 유지 2시간)";

	private final AuthService authService;

	// 가입하면 바로 로그인 상태가 된다.
	@Operation(summary = "회원가입", description = "가입하면 바로 로그인 상태가 되고 세션 쿠키가 발급된다.")
	@ApiResponse(responseCode = "201", description = "가입 + 로그인 성공",
			headers = @Header(name = "Set-Cookie", description = SET_COOKIE_DESCRIPTION),
            content = @Content(schema = @Schema(implementation = UserResponse.class),
                    examples = @ExampleObject(value = ApiExamples.AUTH_USER)))
	@ApiResponse(responseCode = "400", description = "입력값 오류 (VALIDATION_FAILED) 또는 GitHub에 없는 계정 (INVALID_GITHUB_ACCOUNT)",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = {
							@ExampleObject(name = "VALIDATION_FAILED", value = """
									{ "code": "VALIDATION_FAILED", "message": "아이디는 영문 / 숫자 4~20자입니다." }"""),
							@ExampleObject(name = "INVALID_GITHUB_ACCOUNT", value = """
									{ "code": "INVALID_GITHUB_ACCOUNT", "message": "GitHub 계정을 찾을 수 없습니다. 로그인명을 확인해 주세요." }""") }))
	@ApiResponse(responseCode = "409", description = "이미 사용 중인 아이디",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = @ExampleObject(value = """
							{ "code": "DUPLICATE_LOGIN_ID", "message": "이미 사용 중인 아이디입니다." }""")))
	@PostMapping("/signup")
	public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request,
			@Parameter(hidden = true) HttpServletRequest httpRequest) {
		User user = authService.signup(request);
		startSession(httpRequest, user);
		return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
	}

	@Operation(summary = "로그인", description = "성공하면 세션 쿠키가 발급된다. 아이디가 없을 때와 비밀번호가 틀릴 때 같은 에러를 준다.")
	@ApiResponse(responseCode = "200", description = "로그인 성공",
			headers = @Header(name = "Set-Cookie", description = SET_COOKIE_DESCRIPTION),
            content = @Content(schema = @Schema(implementation = UserResponse.class),
                    examples = @ExampleObject(value = ApiExamples.AUTH_USER)))
	@ApiResponse(responseCode = "400", description = "입력값 누락",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = @ExampleObject(value = """
							{ "code": "VALIDATION_FAILED", "message": "아이디를 입력해 주세요." }""")))
	@ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호 불일치",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = @ExampleObject(value = """
							{ "code": "LOGIN_FAILED", "message": "아이디 또는 비밀번호가 올바르지 않습니다." }""")))
	@PostMapping("/login")
	public UserResponse login(@Valid @RequestBody LoginRequest request,
			@Parameter(hidden = true) HttpServletRequest httpRequest) {
		User user = authService.login(request);
		startSession(httpRequest, user);
		return UserResponse.from(user);
	}

	@Operation(summary = "로그아웃", description = "세션을 지운다. 로그인하지 않은 상태에서 호출해도 204.")
	@ApiResponse(responseCode = "204", description = "로그아웃 완료")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@Parameter(hidden = true) HttpServletRequest httpRequest) {
		HttpSession session = httpRequest.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "내 정보", description = "새로고침 시 로그인 상태를 확인할 때 쓴다.")
	@ApiResponse(responseCode = "200", description = "로그인 상태",
            content = @Content(schema = @Schema(implementation = UserResponse.class),
                    examples = @ExampleObject(value = ApiExamples.AUTH_USER)))
	@ApiResponse(responseCode = "401", description = "로그인하지 않음",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class),
					examples = @ExampleObject(value = """
							{ "code": "UNAUTHORIZED", "message": "로그인이 필요합니다." }""")))
	@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "sessionCookie")
	@GetMapping("/me")
	public UserResponse me(@Parameter(hidden = true)
			@SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long userId) {
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
