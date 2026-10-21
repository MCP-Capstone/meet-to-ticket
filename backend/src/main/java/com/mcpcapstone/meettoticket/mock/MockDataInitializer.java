package com.mcpcapstone.meettoticket.mock;

import com.mcpcapstone.meettoticket.entity.User;
import com.mcpcapstone.meettoticket.entity.Project;
import com.mcpcapstone.meettoticket.entity.ProjectMember;
import com.mcpcapstone.meettoticket.entity.Meeting;
import com.mcpcapstone.meettoticket.entity.AnalysisTask;
import com.mcpcapstone.meettoticket.entity.TaskItem;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("mock")
public class MockDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MockDataInitializer.class);
    private static final String PASSWORD_HASH = "$2y$10$MFxhNv81dXAspdMlZn.6tu2bS6ogY/5PcqLpZKdjPt3ysrUQ47qcG";
    private static final String PROJECT_NAME = "기업용 문서 협업 서비스 — 관리자 사용자 목록 CSV 내보내기";
    private static final List<MemberFixture> MEMBERS = List.of(
        new MemberFixture("강민규", "mingyukang5420"),
        new MemberFixture("박종민", "jongmiiin"),
        new MemberFixture("임나윤", "jadoolim"),
        new MemberFixture("최윤경", "0unk0"),
        new MemberFixture("함주헌", "juhun-ham"),
        new MemberFixture("허민규", "minkyu-huh")
    );
    private static final List<MeetingFixture> MEETINGS = List.of(
        new MeetingFixture("10min_1_summary.txt", "CSV 내보내기 개발 착수", LocalDate.of(2026, 10, 19)),
        new MeetingFixture("30min_1_summary.txt", "빈 결과 CSV 생성 오류 수정", LocalDate.of(2026, 10, 22)),
        new MeetingFixture("60min_1_summary.txt", "URL 만료 결함과 단계적 활성화 준비", LocalDate.of(2026, 10, 26))
    );
    private final EntityManager em;

    public MockDataInitializer(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        // 리소스를 먼저 읽어 파일 누락 시 데이터를 넣기 전에 실패한다.
        Map<String, String> contents = new java.util.HashMap<>();
        for (MeetingFixture fixture : MEETINGS) {
            contents.put(fixture.filename(), new ClassPathResource("mock/meetings/" + fixture.filename())
                .getContentAsString(StandardCharsets.UTF_8));
        }
        var users = em.createQuery("select u from User u", User.class).getResultList();
        var projects = em.createQuery("select p from Project p", Project.class).getResultList();
        var members = em.createQuery("select m from ProjectMember m", ProjectMember.class).getResultList();
        var meetings = em.createQuery("select m from Meeting m", Meeting.class).getResultList();
        if (!users.isEmpty() || !projects.isEmpty() || !members.isEmpty() || !meetings.isEmpty()) {
            validateExisting(users, projects, members, meetings, contents);
            log.info("목업 데이터가 이미 있어 추가 입력을 생략합니다.");
            return;
        }

        var user = new User("demo", PASSWORD_HASH, "임나윤");
        em.persist(user);
        var project = Project.builder().name(PROJECT_NAME)
            .githubOwner("MCP-Capstone").githubRepo("mcp-capstone").build();
        em.persist(project);
        var participantIds = new java.util.ArrayList<Long>();
        for (MemberFixture fixture : MEMBERS) {
            var member = ProjectMember.builder().project(project).name(fixture.name())
                .githubName(fixture.githubName()).user(fixture.name().equals("임나윤") ? user : null).build();
            em.persist(member);
            participantIds.add(member.getId());
        }
        for (MeetingFixture fixture : MEETINGS) {
            em.persist(Meeting.builder().project(project).createdBy(user).title(fixture.title())
                .meetingDate(fixture.date()).participants(List.copyOf(participantIds))
                .originalFilename(fixture.filename()).originalContent(contents.get(fixture.filename())).build());
        }
        em.flush();
        log.info("목업 데이터 생성 완료: 계정 1, 프로젝트 1, 멤버 6, 회의 3. 분석 결과는 비워둡니다.");
    }

    private void validateExisting(List<User> users, List<Project> projects, List<ProjectMember> members,
            List<Meeting> meetings, Map<String, String> contents) {
        require(users.size() == 1 && projects.size() == 1 && members.size() == 6 && meetings.size() == 3);
        User user = users.getFirst();
        Project project = projects.getFirst();
        require(user.getLoginId().equals("demo") && user.getName().equals("임나윤")
            && user.getPasswordHash().equals(PASSWORD_HASH));
        require(project.getName().equals(PROJECT_NAME) && project.getGithubOwner().equals("MCP-Capstone")
            && project.getGithubRepo().equals("mcp-capstone"));
        for (MemberFixture fixture : MEMBERS) {
            var matching = members.stream().filter(m -> m.getName().equals(fixture.name())).toList();
            require(matching.size() == 1);
            var member = matching.getFirst();
            require(member.getProject().getId().equals(project.getId())
                && Objects.equals(member.getGithubName(), fixture.githubName()));
            require(fixture.name().equals("임나윤")
                ? member.getUser() != null && member.getUser().getId().equals(user.getId())
                : member.getUser() == null);
        }
        var memberIds = members.stream().map(ProjectMember::getId).collect(Collectors.toSet());
        for (MeetingFixture fixture : MEETINGS) {
            var matching = meetings.stream().filter(m -> m.getOriginalFilename().equals(fixture.filename())).toList();
            require(matching.size() == 1);
            var meeting = matching.getFirst();
            require(meeting.getProject().getId().equals(project.getId())
                && meeting.getCreatedBy().getId().equals(user.getId())
                && meeting.getTitle().equals(fixture.title()) && meeting.getMeetingDate().equals(fixture.date())
                && meeting.getOriginalContent().equals(contents.get(fixture.filename()))
                && meeting.getParticipants().size() == 6
                && new java.util.HashSet<>(meeting.getParticipants()).equals(memberIds));
        }
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("기존 데이터가 목업과 다릅니다. 덮어쓰지 않습니다. 전용 빈 개발 DB를 사용하거나 mock 프로필을 해제하세요.");
        }
    }
    private record MemberFixture(String name, String githubName) { }
    private record MeetingFixture(String filename, String title, LocalDate date) { }
}
