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

import com.mcpcapstone.meettoticket.project.dto.ProjectDetail;
import com.mcpcapstone.meettoticket.project.dto.ProjectRoleRequest;
import com.mcpcapstone.meettoticket.project.dto.ProjectSaveRequest;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "프로젝트", description = "프로젝트 등록 / 편집과 내 프로젝트, 소속 팀원 조회")
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

    @Operation(operationId = "createProject", summary = "프로젝트 등록", description = "등록한 사용자는 관리자이자 멤버가 된다. 입력하지 않은 Notion·Slack 연결은 NOT_CONNECTED, 입력한 연결은 Agent 확인 전까지 null로 응답한다. Slack 이메일은 멤버별로 함께 저장한다.")
    @ApiResponse(responseCode = "201", description = "등록 완료", content = @Content(schema = @Schema(implementation = ProjectDetail.class),
                    examples = @ExampleObject(value = ApiExamples.PROJECT_DETAIL)))
    @ApiResponse(responseCode = "400", description = "입력값 오류 (VALIDATION_FAILED) 또는 가입하지 않은 멤버 (MEMBER_USER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "409", description = "다른 프로젝트에 등록된 GitHub 저장소", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_DUPLICATE_PROJECT_REPOSITORY)))
    @PostMapping("/api/projects")
    public ResponseEntity<ProjectDetail> create(
            @Valid @RequestBody ProjectSaveRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, userId));
    }

    @Operation(operationId = "getProject", summary = "프로젝트 상세 조회", description = "편집 화면에서 기존 정보를 불러올 때 쓴다. 관리자가 아니면 canEdit=false.")
    @ApiResponse(responseCode = "200", description = "프로젝트 상세", content = @Content(schema = @Schema(implementation = ProjectDetail.class),
                    examples = @ExampleObject(value = ApiExamples.PROJECT_DETAIL)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @GetMapping("/api/projects/{projectId}")
    public ProjectDetail detail(
            @Parameter(description = "프로젝트 ID", example = "1", required = true) @PathVariable Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.detail(projectId, userId);
    }

    @Operation(operationId = "updateProject", summary = "프로젝트 편집", description = "관리자만 할 수 있다. memberUserIds는 변경 후 전체 멤버 목록이고, 관리자는 목록에 없어도 남는다. memberSlackEmails는 프로젝트 멤버의 Slack 이메일을 사용자 계정에 저장한다.")
    @ApiResponse(responseCode = "200", description = "저장 완료", content = @Content(schema = @Schema(implementation = ProjectDetail.class),
                    examples = @ExampleObject(value = ApiExamples.PROJECT_DETAIL)))
    @ApiResponse(responseCode = "400", description = "입력값 오류 (VALIDATION_FAILED) 또는 가입하지 않은 멤버 (MEMBER_USER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님 (PROJECT_ACCESS_DENIED) 또는 관리자 아님 (PROJECT_ADMIN_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ADMIN_REQUIRED)))
    @ApiResponse(responseCode = "409", description = "다른 프로젝트에 등록된 GitHub 저장소", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_DUPLICATE_PROJECT_REPOSITORY)))
    @PutMapping("/api/projects/{projectId}")
    public ProjectDetail update(
            @Parameter(description = "프로젝트 ID", example = "1", required = true) @PathVariable Long projectId,
            @Valid @RequestBody ProjectSaveRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.update(projectId, request, userId);
    }

    @Operation(operationId = "changeProjectMemberRole", summary = "프로젝트 관리자 지정/해제", description = "관리자만 할 수 있다. 자기 자신도 해제할 수 있지만 관리자는 항상 1명 이상 남아야 한다.")
    @ApiResponse(responseCode = "200", description = "변경 완료", content = @Content(schema = @Schema(implementation = ProjectDetail.class),
                    examples = @ExampleObject(value = ApiExamples.PROJECT_DETAIL)))
    @ApiResponse(responseCode = "400", description = "역할 값 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님 (PROJECT_ACCESS_DENIED) 또는 관리자 아님 (PROJECT_ADMIN_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ADMIN_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "대상 사용자가 프로젝트 멤버가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_MEMBER_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "마지막 관리자는 해제할 수 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_LAST_PROJECT_ADMIN)))
    @PutMapping("/api/projects/{projectId}/members/{memberUserId}/role")
    public ProjectDetail changeRole(
            @Parameter(description = "프로젝트 ID", example = "1", required = true) @PathVariable Long projectId,
            @Parameter(description = "역할을 바꿀 멤버의 사용자 ID", example = "2", required = true) @PathVariable Long memberUserId,
            @Valid @RequestBody ProjectRoleRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.changeRole(projectId, memberUserId, request.role(), userId);
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
