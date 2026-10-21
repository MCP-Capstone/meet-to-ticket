package com.mcpcapstone.meettoticket.common.config;

import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.*;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements CommandLineRunner {
    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final PasswordEncoder encoder;

    @Value("${app.demo.login-id:demo1}")
    private String loginId;

    @Value("${app.demo.password:password1}")
    private String password;

    @Value("${app.demo.github-logins:}")
    private String logins;

    @Override
    @Transactional
    public void run(String... args) {
        User user =
                users.findByLoginId(loginId)
                        .orElseGet(
                                () ->
                                        users.save(
                                                new User(
                                                        loginId, encoder.encode(password), "함주헌")));
        Project project =
                projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone")
                        .orElseGet(
                                () ->
                                        projects.save(
                                                new Project(
                                                        "기업용 문서 협업 서비스 - 관리자 사용자 목록 CSV 내보내기",
                                                        "MCP-Capstone",
                                                        "mcp-capstone")));
        String[] names = {"강민규", "박종민", "임나윤", "최윤경", "함주헌", "허민규"};
        String[] accounts = logins.split(",", -1);
        for (int i = 0; i < names.length; i++)
            if (!members.existsByProjectIdAndName(project.getId(), names[i])) {
                String account =
                        i < accounts.length && !accounts[i].isBlank() ? accounts[i].trim() : null;
                members.save(
                        new ProjectMember(
                                project, names[i].equals("함주헌") ? user : null, names[i], account));
            }
    }
}
