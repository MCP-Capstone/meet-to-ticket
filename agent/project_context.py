"""분석 전에 가져오는 프로젝트 맥락(GitHub 이슈·Notion 문서)."""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from typing import Any

import config
from publisher import parse_tool_result

log = logging.getLogger("agent.project_context")
# 기존 조회 범위는 유지하되, 문서별 제한 안에서 읽은 결과만 분석에 사용한다.
MAX_NOTION_PAGES = 10
NOTION_LIST_TIMEOUT_SECONDS = 30
NOTION_PAGE_TIMEOUT_SECONDS = 30
# 문서마다 MCP 세션을 열고 블록을 재귀 조회하므로, Notion 요청 한도(약 3회/초)를 넘지 않게 동시에 읽는 수를 묶는다.
NOTION_READ_CONCURRENCY = 3
# 호출자(nodes.prepare)의 전체 한도보다 조금 먼저 스스로 멈춰, 바깥에서 끊기기 전에 생략한 문서를 안내한다.
DEADLINE_MARGIN_SECONDS = 2.0


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
                      into: dict[str, Any] | None = None, budget_seconds: float | None = None) -> dict[str, Any]:
        """`into`를 주면 그 dict를 채워 간다. 호출자가 시간 초과로 중단해도 읽은 만큼은 남는다.

        `budget_seconds`(기본은 CONTEXT_TIMEOUT_SECONDS) 안에 끝나도록 공통 마감을 두고,
        문서마다 min(문서별 한도, 남은 시간)만 기다린다. 마감이 지나면 새 문서를 읽지 않는다.
        """
        loop = asyncio.get_running_loop()
        budget = float(config.context_timeout_seconds() if budget_seconds is None else budget_seconds)
        deadline = loop.time() + budget - min(DEADLINE_MARGIN_SECONDS, budget / 10)
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
            list_timeout = max(0.0, min(NOTION_LIST_TIMEOUT_SECONDS, deadline - loop.time()))
            try:
                result = await asyncio.wait_for(
                    self._call(self._list_pages, {"root_page_url_or_id": notion_page_url}, "list_pages"),
                    timeout=list_timeout,
                )
                pages = result.get("pages")
                if not isinstance(pages, list):
                    raise ValueError("list_pages 응답에 pages 목록이 없습니다")
                limit = asyncio.Semaphore(NOTION_READ_CONCURRENCY)
                # 끝난 순서와 관계없이 목록 순서로 넣어, 실행마다 프롬프트의 문서 순서가 같게 한다.
                # 끝날 때마다 다시 정렬해 두므로 호출자가 중간에 끊어도 읽은 문서는 남는다.
                base = len(notion_docs)
                done: dict[int, dict[str, Any]] = {}
                # 전체 한도에 걸려 못 읽은 문서. 끝난 뒤 목록 순서대로 한 번에 안내한다.
                out_of_time: dict[int, str] = {}

                async def read_document(index: int, page: Any) -> None:
                    if not isinstance(page, dict) or not page.get("page_id"):
                        return
                    name = page.get("title") or page["page_id"]
                    timeout = 0.0
                    try:
                        async with limit:
                            remaining = deadline - loop.time()
                            if remaining <= 0:
                                out_of_time[index] = name
                                return
                            timeout = min(NOTION_PAGE_TIMEOUT_SECONDS, remaining)
                            read = await asyncio.wait_for(
                                self._call(self._read_page, {"page_url_or_id": page["page_id"], "max_chars": self._max_chars}, "read_page"),
                                timeout=timeout,
                            )
                    except asyncio.TimeoutError:
                        if timeout < NOTION_PAGE_TIMEOUT_SECONDS:
                            out_of_time[index] = name
                            return
                        warning = f"Notion 문서 '{name}' 조회가 {NOTION_PAGE_TIMEOUT_SECONDS}초를 넘어 생략됨"
                        log.warning(warning)
                        warnings.append(warning)
                        return
                    except Exception as exc:  # noqa: BLE001
                        warning = f"Notion 문서 '{name}' 조회 실패: {exc}"
                        log.warning(warning)
                        warnings.append(warning)
                        return
                    done[index] = {
                        "page_id": read.get("page_id", page["page_id"]),
                        "title": read.get("title", page.get("title", "")),
                        "url": read.get("url"),
                        "markdown": read.get("markdown", ""),
                        "truncated": bool(read.get("truncated", False)),
                    }
                    notion_docs[base:] = [done[i] for i in sorted(done)]
                await asyncio.gather(*(read_document(index, page) for index, page in enumerate(pages[:MAX_NOTION_PAGES])))
                if out_of_time:
                    names = ", ".join(f"'{out_of_time[i]}'" for i in sorted(out_of_time))
                    warning = f"Notion 문서 {len(out_of_time)}건({names})은 맥락 조회 한도 {budget:g}초 안에 읽지 못해 생략됨"
                    log.warning(warning)
                    warnings.append(warning)
                if len(pages) > MAX_NOTION_PAGES:
                    warnings.append(f"Notion 문서 {len(pages) - MAX_NOTION_PAGES}건은 분석 맥락에서 생략됨")
            except asyncio.TimeoutError:
                warning = f"Notion 문서 목록 조회가 {list_timeout:g}초를 넘어 생략됨"
                log.warning(warning)
                warnings.append(warning)
            except Exception as exc:  # noqa: BLE001
                warning = f"Notion 문서 조회 실패: {exc}"
                log.warning(warning)
                warnings.append(warning)
        return result_ctx


# --context로 맥락을 넣을 때 Notion 루트 페이지 자리에 두는 값. 가짜 list_pages는 이 값을 보지 않는다
STATIC_CONTEXT_ROOT = "static-context"


class StaticTool:
    """고정 값을 돌려주는 읽기 도구. MCP 도구의 ainvoke만 흉내 낸다."""

    def __init__(self, answer: Any):
        self._answer = answer

    async def ainvoke(self, args: dict[str, Any]) -> str:
        return json.dumps(self._answer(args), ensure_ascii=False)


def static_context_tools(path: Path) -> tuple[dict[str, Any], bool]:
    """맥락 JSON({"issues": [...], "notionDocs": [{page_id, title, url, markdown}]})을 맥락 조회 도구로 바꾼다.

    MCP 서버와 Notion 없이 같은 맥락으로 여러 번 실행해 맥락 반영을 평가하려고 쓴다.
    두 번째 값은 Notion 문서가 있는지(있어야 요청에 Notion 페이지를 지정해 문서를 읽는다).
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    docs = {d["page_id"]: d for d in data.get("notionDocs", [])}
    if len(docs) != len(data.get("notionDocs", [])):
        raise ValueError(f"맥락 JSON에 같은 page_id가 두 번 이상 있습니다: {path}")

    def read_page(args: dict[str, Any]) -> dict[str, Any]:
        # 실제 read_page처럼 max_chars로 자르고 잘렸는지 표시한다
        doc = docs[args["page_url_or_id"]]
        limit = int(args.get("max_chars") or 0)
        text = doc.get("markdown", "")
        cut = 0 < limit < len(text)
        return {**doc, "markdown": text[:limit] if cut else text, "truncated": cut or bool(doc.get("truncated"))}

    tools = {
        "list_issues": StaticTool(lambda args: {"issues": data.get("issues", [])[:int(args.get("limit") or 20)]}),
        "list_pages": StaticTool(lambda _args: {"pages": [{"page_id": d["page_id"], "title": d["title"]}
                                                          for d in docs.values()]}),
        "read_page": StaticTool(read_page),
    }
    return tools, bool(docs)
