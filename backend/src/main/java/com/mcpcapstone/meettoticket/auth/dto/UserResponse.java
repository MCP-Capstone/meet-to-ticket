package com.mcpcapstone.meettoticket.auth.dto;

import com.mcpcapstone.meettoticket.user.User;

public record UserResponse(Long userId, String loginId, String name) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getLoginId(), user.getName());
	}
}
