"""프로젝트 저장 시 멤버별 저장소 권한 확인 (백엔드 → Agent → business-mcp). LLM을 쓰지 않는다."""

from __future__ import annotations

from typing import Any

from config import Settings
from publisher import ToolCallError, error_from_exception, parse_tool_result
from schemas import CheckMembersRequest, CheckMembersResponse, MemberPermissionOut
from state import MCP_UNAVAILABLE


async def check_members(settings: Settings, req: CheckMembersRequest, tools: dict[str, Any] | None = None) -> CheckMembersResponse:
    """MCP의 check_member_permissions를 호출한다.

    MCP가 전체 오류를 돌려주면(저장소 없음, 허용되지 않은 저장소, 인증 실패 등) 오류 코드를 그대로 담은
    ToolCallError를 던진다. 멤버 한 명의 확인 실패는 그 멤버의 permission=ERROR로 온다.
    `tools`는 테스트에서 MCP 도구를 바꿔 넣는 용도다.
    """
    names = [n for n in req.github_names if n and n.strip()]
    if not names:
        return CheckMembersResponse(members=[])
    if tools is None:
        from runner import github_tools

        try:
            tools = await github_tools(settings)
        except Exception as exc:  # noqa: BLE001
            raise error_from_exception(exc, MCP_UNAVAILABLE) from exc
    tool = tools.get("check_member_permissions")
    if tool is None:
        raise ToolCallError(MCP_UNAVAILABLE, "MCP 서버에 check_member_permissions 도구가 없음")
    try:
        raw = await tool.ainvoke({"owner": req.github_owner, "repo": req.github_repo, "github_names": names})
        data = parse_tool_result(raw)
    except Exception as exc:  # noqa: BLE001
        raise error_from_exception(exc) from exc
    members = data.get("members")
    if not isinstance(members, list):
        raise ToolCallError(MCP_UNAVAILABLE, f"check_member_permissions 결과 형식 오류: {str(data)[:200]}")
    return CheckMembersResponse(
        members=[MemberPermissionOut(github_name=m["github_name"], permission=m["permission"]) for m in members]
    )
