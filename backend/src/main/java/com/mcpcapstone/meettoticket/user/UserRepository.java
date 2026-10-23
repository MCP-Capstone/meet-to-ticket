package com.mcpcapstone.meettoticket.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByLoginId(String loginId);

	Optional<User> findByLoginId(String loginId);

	// 이름 또는 GitHub 계정에 검색어가 들어간 사용자. 검색어가 빈 문자열이면 전체.
	@Query("""
			select u from User u
			where lower(u.name) like lower(concat('%', :keyword, '%'))
			   or lower(coalesce(u.githubName, '')) like lower(concat('%', :keyword, '%'))
			order by u.name, u.id""")
	List<User> search(String keyword, Pageable pageable);
}
