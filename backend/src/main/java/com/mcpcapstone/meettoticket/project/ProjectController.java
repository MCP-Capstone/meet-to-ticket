package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.common.error.ErrorResponse;
import com.mcpcapstone.meettoticket.common.config.ApiExamples;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.ExecutionSettings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "프로젝트", description = "내 프로젝트와 프로젝트 소속 팀원 조회")
@RestController
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService service;
    private final ExecutionSettings executionSettings;

    @Operation(operationId = "listProjects", summary = "내 프로젝트 조회", description = "로그인 사용자가 소속된 프로젝트와 백엔드 실행 모드를 반환한다. MOCK는 실제 Agent·GitHub 호출을 하지 않는다.")
    @ApiResponse(responseCode = "200", description = "프로젝트 목록", content = @Content(schema = @Schema(implementation = ProjectList.class),
                    examples = @ExampleObject(value = ApiExamples.PROJECTS)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @GetMapping("/api/projects")
    public ProjectList list(@Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return new ProjectList(service.list(userId), executionSettings.mode());
    }

    @Operation(operationId = "listProjectMembers", summary = "프로젝트 팀원 조회", description = "참여자 ID를 이름으로 표시할 때 사용한다. userId와 githubName은 연결되지 않았다면 null이다.")
    @ApiResponse(responseCode = "200", description = "팀원 목록", content = @Content(schema = @Schema(implementation = MemberList.class),
                    examples = @ExampleObject(value = ApiExamples.MEMBERS)))
    @ApiResponse(responseCode = "400", description = "경로 변수·요청 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @GetMapping("/api/projects/{projectId}/members")
    public MemberList members(
            @Parameter(description = "프로젝트 ID", example = "1", required = true) @PathVariable Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return new MemberList(projectId, service.members(projectId, userId));
    }

    public record ProjectList(
            @Schema(description = "소속 프로젝트 목록. 미소속이면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
            List<ProjectService.ProjectData> projects,
            @Schema(description = "백엔드 실행 모드", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MOCK", "LIVE"}, example = "MOCK")
            String executionMode) {}

    public record MemberList(
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
            Long projectId,
            @Schema(description = "프로젝트 전체 팀원 목록", requiredMode = Schema.RequiredMode.REQUIRED)
            List<ProjectService.MemberData> members) {}
}
