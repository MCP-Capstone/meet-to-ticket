package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mcpcapstone.meettoticket.project.ProjectMember;

import tools.jackson.databind.json.JsonMapper;

class AssigneeResolverTest {
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final ProjectMember kim = member(1L, "김민준", "minjun-k");
	private final ProjectMember park = member(2L, "박서연", null); // GitHub 계정 없음
	private final ProjectMember park2 = member(3L, "박서연", "seoyeon-2"); // 동명이인
	private final ProjectMember lee = member(4L, "이도윤", "doyun-l");

	private static ProjectMember member(long id, String name, String githubName) {
		ProjectMember m = new ProjectMember(null, null, name, githubName);
		ReflectionTestUtils.setField(m, "id", id);
		return m;
	}

	private ProjectMember resolve(String json, ProjectMember... members) {
		return AssigneeResolver.resolve(mapper.readTree(json), List.of(members)).orElse(null);
	}

	@Test
	void 이름이_멤버_1명과_맞으면_그_멤버가_담당이다() {
		assertThat(resolve("{\"assignees\":{\"names\":[\"김민준\"],\"github\":[\"minjun-k\"],\"unresolved\":[]}}", kim, lee)).isSameAs(kim);
	}

	@Test
	void GitHub_계정이_없는_멤버도_담당으로_연결한다() {
		assertThat(resolve("{\"assignees\":{\"names\":[\"박서연\"],\"github\":[],\"unresolved\":[\"박서연\"]}}", kim, park)).isSameAs(park);
	}

	@Test
	void 담당자가_없거나_여러_명이거나_멤버와_맞지_않으면_정하지_않는다() {
		assertThat(resolve("{\"assignees\":{\"names\":[],\"github\":[],\"unresolved\":[]}}", kim)).isNull();
		assertThat(resolve("{\"assignees\":{\"names\":[\"김민준\",\"이도윤\"],\"github\":[],\"unresolved\":[]}}", kim, lee)).isNull();
		assertThat(resolve("{\"assignees\":{\"names\":[\"없는사람\"],\"github\":[],\"unresolved\":[]}}", kim)).isNull();
		assertThat(resolve("{\"assignees\":{\"names\":[\"박서연\"],\"github\":[],\"unresolved\":[]}}", park, park2)).isNull();
		assertThat(resolve("{}", kim)).isNull();
	}

	@Test
	void 같은_사람이_두_번_나와도_1명이면_담당이다() {
		assertThat(resolve("{\"assignees\":{\"names\":[\"김민준\",\"김민준\"]}}", kim, lee)).isSameAs(kim);
	}

	@Test
	void 배열_형식은_projectMemberId로_연결한다() {
		assertThat(resolve("{\"assignees\":[{\"projectMemberId\":4,\"mentionedName\":\"이도윤\"}]}", kim, lee)).isSameAs(lee);
		assertThat(resolve("{\"assignees\":[{\"projectMemberId\":99}]}", kim, lee)).isNull();
	}
}
