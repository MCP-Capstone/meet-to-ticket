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

	@Column(nullable = false, unique = true, length = 20)
	private String loginId;

	@Column(nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 20)
	private String name;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	public User(String loginId, String passwordHash, String name) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
	}
}
