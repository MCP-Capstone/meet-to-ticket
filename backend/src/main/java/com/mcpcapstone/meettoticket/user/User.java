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

	// Slack 계정은 프로젝트마다 달라지지 않는 사용자 식별자다. 실제 사용자 ID는 Agent가 이메일 조회 결과를 기록한다.
	@Column(name = "slack_email", length = 254)
	private String slackEmail;

	@Column(name = "slack_user_id", length = 30)
	private String slackUserId;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	public User(String loginId, String passwordHash, String name) {
		this(loginId, passwordHash, name, null);
	}

	public User(String loginId, String passwordHash, String name, String githubName) {
		this(loginId, passwordHash, name, githubName, null);
	}

	public User(String loginId, String passwordHash, String name, String githubName, String slackEmail) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.githubName = githubName;
		this.slackEmail = slackEmail;
	}

	public void updateSlackEmail(String slackEmail) {
		if (!java.util.Objects.equals(this.slackEmail, slackEmail)) {
			this.slackEmail = slackEmail;
			// 이메일이 바뀌면 이전 워크스페이스 사용자 ID를 재사용하면 안 된다.
			this.slackUserId = null;
		}
	}

	public void updateSlackUserId(String slackUserId) {
		this.slackUserId = slackUserId;
	}
}
