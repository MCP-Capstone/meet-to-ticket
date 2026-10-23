"""분석 전에 가져오는 프로젝트 맥락(GitHub 이슈·Notion 문서)."""

from __future__ import annotations

import logging
from typing import Any

from publisher import error_from_exception, parse_tool_result

log = logging.getLogger("agent.project_context")

# 문서 전체를 무제한으로 프롬프트에 넣지 않는다. 목록은 Notion 루트 바로 아래 문서다.
MAX_NOTION_PAGES = 10


class ProjectContextTools:
    """읽기 전용 business-mcp 도구를 감싼다.

    맥락을 못 읽어도 회의록 분석 자체는 계속한다. ``warnings``는 진행 화면과 최종 result
    metrics에 남겨, 초안이 어떤 자료를 바탕으로 작성됐는지 구분할 수 있게 한다.
    """

    def __init__(self, list_issues: Any | None, list_pages: Any | None, read_page: Any | None, *, max_chars: int):
        self._list_issues = list_issues
        self._list_pages = list_pages
        self._read_page = read_page
        self._max_chars = max(1, min(max_chars, 12_000))

    @staticmethod
    async def _call(tool: Any | None, args: dict[str, Any], name: str) -> dict[str, Any]:
        if tool is None:
            raise RuntimeError(f"{name} MCP 도구를 찾지 못했습니다")
        try:
            return parse_tool_result(await tool.ainvoke(args))
        except Exception as exc:  # noqa: BLE001 - 맥락 조회 실패는 경고로만 남긴다
            raise error_from_exception(exc) from exc

    async def collect(self, owner: str, repo: str, notion_page_url: str | None) -> dict[str, Any]:
        issues: list[dict[str, Any]] = []
        notion_docs: list[dict[str, Any]] = []
        warnings: list[str] = []

        try:
            result = await self._call(
                self._list_issues, {"owner": owner, "repo": repo, "state": "all", "limit": 20}, "list_issues"
            )
            raw_issues = result.get("issues")
            if not isinstance(raw_issues, list):
                raise ValueError("list_issues 응답에 issues 목록이 없습니다")
            issues = [
                {key: issue.get(key) for key in ("number", "title", "state", "url", "closed_at")}
                for issue in raw_issues if isinstance(issue, dict)
            ]
        except Exception as exc:  # noqa: BLE001
            warning = f"GitHub 기존 이슈 조회 실패: {getattr(exc, 'message', str(exc))}"
            log.warning(warning)
            warnings.append(warning)

        if notion_page_url:
            try:
                result = await self._call(self._list_pages, {"root_page_url_or_id": notion_page_url}, "list_pages")
                pages = result.get("pages")
                if not isinstance(pages, list):
                    raise ValueError("list_pages 응답에 pages 목록이 없습니다")
                for page in pages[:MAX_NOTION_PAGES]:
                    if not isinstance(page, dict) or not page.get("page_id"):
                        continue
                    read = await self._call(
                        self._read_page,
                        {"page_url_or_id": page["page_id"], "max_chars": self._max_chars},
                        "read_page",
                    )
                    notion_docs.append({
                        "page_id": read.get("page_id", page["page_id"]),
                        "title": read.get("title", page.get("title", "")),
                        "url": read.get("url"),
                        "markdown": read.get("markdown", ""),
                        "truncated": bool(read.get("truncated", False)),
                    })
                if len(pages) > MAX_NOTION_PAGES:
                    warnings.append(f"Notion 문서 {len(pages) - MAX_NOTION_PAGES}건은 분석 맥락에서 생략됨")
            except Exception as exc:  # noqa: BLE001
                warning = f"Notion 문서 조회 실패: {getattr(exc, 'message', str(exc))}"
                log.warning(warning)
                warnings.append(warning)

        return {"issues": issues, "notionDocs": notion_docs, "warnings": warnings}
