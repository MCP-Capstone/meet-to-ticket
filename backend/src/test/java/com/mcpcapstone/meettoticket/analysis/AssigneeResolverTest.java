package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.user.User;

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
	void 동일_GitHub_시드와_가입_멤버는_가입된_사용자에게_보낸다() {
		User user = new User("yunk", "hash", "최윤경");
		var seed = member(6L, "최윤경", "0unk0");
		var registered = new ProjectMember(null, user, "최윤경", "0unk0");
		ReflectionTestUtils.setField(registered, "id", 7L);
		assertThat(resolve("{\"assignees\":{\"names\":[\"최윤경\"],\"github\":[\"0unk0\"]}}", seed, registered)).isSameAs(registered);
		var unrelated = member(8L, "최윤경", "different-account");
		assertThat(resolve("{\"assignees\":{\"names\":[\"최윤경\"]}}", unrelated, registered)).isNull();
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

	@Test
	void Slack_공지와_Notion_티켓은_업로더에게_보낸다() {
		User uploader = new User("uploader", "hash", "함주헌");
		ReflectionTestUtils.setField(uploader, "id", 10L);
		ProjectMember ham = new ProjectMember(null, uploader, "함주헌", "juhun-ham");
		ReflectionTestUtils.setField(ham, "id", 5L);
		String slack = "{\"publish_target\":\"SLACK\",\"assignees\":{\"names\":[]}}";
		String notion = "{\"publish_target\":\"NOTION\",\"assignees\":{\"names\":[\"김민준\"]}}";

		assertThat(AssigneeResolver.resolve(mapper.readTree(slack), List.of(kim, ham), uploader)).containsSame(ham);
		// 회의록 담당자가 있어도 Notion 티켓은 업로더가 받는다 (REQ-010)
		assertThat(AssigneeResolver.resolve(mapper.readTree(notion), List.of(kim, ham), uploader)).containsSame(ham);
		// 업로더가 프로젝트 멤버가 아니면 담당 지정 필요로 남는다
		assertThat(AssigneeResolver.resolve(mapper.readTree(slack), List.of(kim), uploader)).isEmpty();
		// GitHub 티켓은 지금처럼 회의록 담당자 이름으로 정한다
		String github = "{\"publish_target\":\"GITHUB\",\"assignees\":{\"names\":[\"김민준\"]}}";
		assertThat(AssigneeResolver.resolve(mapper.readTree(github), List.of(kim, ham), uploader)).containsSame(kim);
		assertThat(AssigneeResolver.resolve(mapper.readTree("{\"assignees\":{\"names\":[\"김민준\"]}}"), List.of(kim, ham), uploader))
				.containsSame(kim);
	}
}
