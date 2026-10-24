"""분석 전에 가져오는 프로젝트 맥락(GitHub 이슈·Notion 문서)."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from publisher import parse_tool_result

log = logging.getLogger("agent.project_context")
# 기존 조회 범위는 유지하되, 문서별 제한 안에서 읽은 결과만 분석에 사용한다.
MAX_NOTION_PAGES = 10
NOTION_LIST_TIMEOUT_SECONDS = 30
NOTION_PAGE_TIMEOUT_SECONDS = 30


class ProjectContextTools:
    """읽기 전용 MCP 도구를 감싼다. 실패해도 회의록 분석은 계속한다."""

    def __init__(self, list_issues: Any | None, list_pages: Any | None, read_page: Any | None, *, max_chars: int):
        self._list_issues = list_issues
        self._list_pages = list_pages
        self._read_page = read_page
        self._max_chars = max(1, min(max_chars, 12_000))

    @staticmethod
    async def _call(tool: Any | None, args: dict[str, Any], name: str) -> dict[str, Any]:
        if tool is None:
            raise RuntimeError(f"{name} MCP 도구를 찾지 못했습니다")
        return parse_tool_result(await tool.ainvoke(args))

    async def collect(self, owner: str, repo: str, notion_page_url: str | None,
                      into: dict[str, Any] | None = None) -> dict[str, Any]:
        """`into`를 주면 그 dict를 채워 간다. 호출자가 시간 초과로 중단해도 읽은 만큼은 남는다."""
        result_ctx = into if into is not None else {}
        issues: list[dict[str, Any]] = result_ctx.setdefault("issues", [])
        notion_docs: list[dict[str, Any]] = result_ctx.setdefault("notionDocs", [])
        warnings: list[str] = result_ctx.setdefault("warnings", [])
        try:
            result = await self._call(self._list_issues, {"owner": owner, "repo": repo, "state": "all", "limit": 20}, "list_issues")
            raw = result.get("issues")
            if not isinstance(raw, list):
                raise ValueError("list_issues 응답에 issues 목록이 없습니다")
            issues.extend({key: item.get(key) for key in ("number", "title", "state", "url", "closed_at")}
                          for item in raw if isinstance(item, dict))
        except Exception as exc:  # noqa: BLE001
            warning = f"GitHub 기존 이슈 조회 실패: {exc}"
            log.warning(warning)
            warnings.append(warning)

        if notion_page_url:
            try:
                result = await asyncio.wait_for(
                    self._call(self._list_pages, {"root_page_url_or_id": notion_page_url}, "list_pages"),
                    timeout=NOTION_LIST_TIMEOUT_SECONDS,
                )
                pages = result.get("pages")
                if not isinstance(pages, list):
                    raise ValueError("list_pages 응답에 pages 목록이 없습니다")
                async def read_document(page: Any) -> None:
                    if not isinstance(page, dict) or not page.get("page_id"):
                        return
                    try:
                        read = await asyncio.wait_for(
                            self._call(self._read_page, {"page_url_or_id": page["page_id"], "max_chars": self._max_chars}, "read_page"),
                            timeout=NOTION_PAGE_TIMEOUT_SECONDS,
                        )
                    except asyncio.TimeoutError:
                        warning = f"Notion 문서 '{page.get('title') or page['page_id']}' 조회가 {NOTION_PAGE_TIMEOUT_SECONDS}초를 넘어 생략됨"
                        log.warning(warning)
                        warnings.append(warning)
                        return
                    except Exception as exc:  # noqa: BLE001
                        warning = f"Notion 문서 '{page.get('title') or page['page_id']}' 조회 실패: {exc}"
                        log.warning(warning)
                        warnings.append(warning)
                        return
                    notion_docs.append({
                        "page_id": read.get("page_id", page["page_id"]),
                        "title": read.get("title", page.get("title", "")),
                        "url": read.get("url"),
                        "markdown": read.get("markdown", ""),
                        "truncated": bool(read.get("truncated", False)),
                    })
                await asyncio.gather(*(read_document(page) for page in pages[:MAX_NOTION_PAGES]))
                if len(pages) > MAX_NOTION_PAGES:
                    warnings.append(f"Notion 문서 {len(pages) - MAX_NOTION_PAGES}건은 분석 맥락에서 생략됨")
            except asyncio.TimeoutError:
                warning = f"Notion 문서 목록 조회가 {NOTION_LIST_TIMEOUT_SECONDS}초를 넘어 생략됨"
                log.warning(warning)
                warnings.append(warning)
            except Exception as exc:  # noqa: BLE001
                warning = f"Notion 문서 조회 실패: {exc}"
                log.warning(warning)
                warnings.append(warning)
        return result_ctx
