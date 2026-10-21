package com.mcpcapstone.meettoticket.common.interceptor;

import org.springframework.web.servlet.HandlerInterceptor;

import com.mcpcapstone.meettoticket.common.constant.SessionConst;

import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

// 로그인하지 않은 요청은 401 UNAUTHORIZED로 막는다. 적용 경로는 WebConfig에서 정한다.
public class LoginCheckInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		HttpSession session = request.getSession(false);
		if (session == null || session.getAttribute(SessionConst.LOGIN_USER_ID) == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return true;
	}
}
