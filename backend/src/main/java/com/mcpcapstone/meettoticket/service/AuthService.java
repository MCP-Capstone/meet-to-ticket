package com.mcpcapstone.meettoticket.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcpcapstone.meettoticket.dto.LoginRequest;
import com.mcpcapstone.meettoticket.dto.SignupRequest;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.entity.User;
import com.mcpcapstone.meettoticket.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Transactional
	public User signup(SignupRequest request) {
		if (userRepository.existsByLoginId(request.loginId())) {
			throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
		}
		User user = new User(request.loginId(), passwordEncoder.encode(request.password()), request.name().trim());
		return userRepository.save(user);
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
