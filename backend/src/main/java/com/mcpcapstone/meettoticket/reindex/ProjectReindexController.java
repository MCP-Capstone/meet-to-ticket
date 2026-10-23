package com.mcpcapstone.meettoticket.reindex;

import com.mcpcapstone.meettoticket.auth.SessionConst;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "프로젝트 색인", description = "프로젝트 GitHub 이슈 RAG 색인 재생성·상태 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/reindex")
public class ProjectReindexController {
    private final ProjectReindexService service;

    @Operation(operationId = "requestProjectReindex", summary = "프로젝트 이슈 전체 재색인", description = "관리자만 시작할 수 있다. 이미 진행 중이면 같은 실행 상태를 반환한다.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectReindexService.ReindexData request(
            @Parameter(description = "프로젝트 ID", required = true) @PathVariable Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.request(projectId, userId);
    }

    @Operation(operationId = "getProjectReindexStatus", summary = "프로젝트 재색인 상태 조회", description = "프로젝트 멤버는 실행 상태·마지막 완료 결과를 조회할 수 있다.")
    @GetMapping
    public ProjectReindexService.ReindexData status(
            @Parameter(description = "프로젝트 ID", required = true) @PathVariable Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.status(projectId, userId);
    }
}
