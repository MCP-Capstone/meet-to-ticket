package com.mcpcapstone.meettoticket.user;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// PostgreSQL에서 user는 예약어라 테이블 이름을 users로 쓴다.
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	private Long id;

	// 컬럼 길이는 확정 스키마(DBML) 기준. 가입 입력 제한(SignupRequest)은 더 좁게 둔다.
	@Column(nullable = false, unique = true, length = 100)
	private String loginId;

	@Column(nullable = false, length = 255)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	// 선택 입력. GitHub 로그인명. 확인되면 GitHub 기준 표기로, 확인하지 못했으면 입력값 그대로 저장한다.
	@Column(length = 100)
	private String githubName;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	public User(String loginId, String passwordHash, String name) {
		this(loginId, passwordHash, name, null);
	}

	public User(String loginId, String passwordHash, String name, String githubName) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.githubName = githubName;
	}
}
