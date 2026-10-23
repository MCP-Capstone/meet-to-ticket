"""MCP 연결 확인 (에이전트 → MCP 서버 → GitHub).

agent 폴더에서, MCP 서버(python server.py)를 켠 상태로 실행한다.

    # 읽기만: 도구 목록 + 지정 저장소의 최근 이슈 조회
    python mcp_check.py --owner <owner> --repo <repo>

    # 쓰기까지: 지정 저장소에 연결 테스트 이슈 1개 생성
    python mcp_check.py --owner <owner> --repo <repo> --write

    # rag-mcp까지: 색인 갱신 후 중복 판단용 도구 3개(유사 검색, 키워드 검색, 원문 읽기)를 한 번씩 호출
    python mcp_check.py --owner <owner> --repo <repo> --rag "검색할 문장"

저장소는 business-mcp/.env의 GITHUB_OWNER·GITHUB_REPO(또는 GITHUB_ALLOWED_REPOS)에 허용돼 있어야 한다.
"""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime

from config import load_env, load_settings

load_env()

from publisher import error_from_exception, parse_tool_result  # noqa: E402


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--owner", required=True)
    p.add_argument("--repo", required=True)
    p.add_argument("--write", action="store_true", help="테스트 이슈를 실제로 생성")
    p.add_argument("--state", default="all", choices=["open", "closed", "all"])
    p.add_argument("--rag", metavar="QUERY", help="rag-mcp 연결과 중복 판단용 도구를 이 문장으로 확인")
    args = p.parse_args()

    from langchain_mcp_adapters.client import MultiServerMCPClient

    settings = load_settings()
    print(f"MCP 서버: {settings.mcp_server_url}")
    client = MultiServerMCPClient({"github": {"transport": "streamable_http", "url": settings.mcp_server_url}})
    try:
        tools = {t.name: t for t in await client.get_tools()}
    except Exception as exc:  # noqa: BLE001
        print(f"[실패] MCP 서버에 연결하지 못함: {exc}")
        print("  → business-mcp 폴더에서 python server.py 가 켜져 있는지, MCP_SERVER_URL이 맞는지 확인")
        return
    print(f"[연결 OK] 도구: {sorted(tools)}")

    repo = f"{args.owner}/{args.repo}"
    try:
        raw = await tools["list_issues"].ainvoke({"owner": args.owner, "repo": args.repo, "state": args.state, "limit": 5})
        issues = parse_tool_result(raw).get("issues", [])
        print(f"[읽기 OK] {repo} 최근 이슈 {len(issues)}건")
        for i in issues:
            print(f"  #{i['number']} [{i['state']}] {i['title']}  {i['url']}")
    except Exception as exc:  # noqa: BLE001
        err = error_from_exception(exc)
        print(f"[읽기 실패] [{err.code}] {err.message}")
        return

    if args.rag:
        await check_rag(args, settings, tools)
    if not args.write:
        print("쓰기 테스트는 --write 를 붙이면 실행됩니다 (실제 이슈 1개 생성).")
        return
    title = f"[MCP 연결 테스트] {datetime.now():%Y-%m-%d %H:%M:%S}"
    body = "meet-to-ticket 에이전트의 MCP 연결 확인용 이슈입니다. 확인 후 닫아도 됩니다."
    try:
        raw = await tools["create_issue"].ainvoke({"owner": args.owner, "repo": args.repo, "title": title, "body": body})
        out = parse_tool_result(raw)
        print(f"[쓰기 OK] #{out.get('number')} {out.get('url')}")
    except Exception as exc:  # noqa: BLE001
        err = error_from_exception(exc)
        print(f"[쓰기 실패] [{err.code}] {err.message}")


async def check_rag(args, settings, github: dict) -> None:
    """Agent가 중복 판단에 쓰는 것과 같은 경로(runner.make_issue_tools)로 rag-mcp를 확인한다."""
    import json

    from runner import make_issue_tools, sync_index

    settings.rag_enabled = True
    print(f"rag-mcp: {settings.rag_mcp_url}")
    tools, status = await make_issue_tools(settings, args.owner, args.repo, github)
    if tools is None:
        print(f"[rag 실패] 상태 {status}  → rag-mcp 폴더에서 python server.py 가 켜져 있는지, RAG_MCP_URL이 맞는지 확인")
        return
    print(f"[rag 연결 OK] 모델에게 줄 도구: {[t.name for t in tools.judge_tools()]}")
    print(f"[색인 갱신] {await sync_index(tools, settings)}")
    found = await tools.search_similar_issues(args.rag)
    print(f"[유사 검색] {found}")
    if "search_issues" in github:
        print(f"[키워드 검색] {await tools.search_issues(args.rag)}")
    else:
        print("[키워드 검색] business-mcp에 search_issues가 아직 없음")
    try:
        top = json.loads(found)["results"][0]["number"]
    except (ValueError, KeyError, IndexError, TypeError):
        print("[원문 읽기] 검색 결과가 없어 건너뜀")
        return
    print(f"[원문 읽기 #{top}] {(await tools.read_issue(top))[:300]}")


if __name__ == "__main__":
    asyncio.run(main())
