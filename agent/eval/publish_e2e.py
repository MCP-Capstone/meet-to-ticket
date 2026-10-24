"""발행 대상별 E2E 검증과 발행 품질 지표 (#303).

실제 business-mcp(로컬 실행, business-mcp/.env의 토큰)와 실제 GitHub, Notion, Slack에 정해진 티켓을 발행하고,
각 서비스 API로 다시 조회해 결과를 확인한다. LLM은 부르지 않는다 (재대조 끔).

agent 폴더에서 (business-mcp 가상환경 필요)
    python -m eval.publish_e2e --notion-page-url <테스트 상위 페이지> --slack-channel <채널 ID>        # 계획만 출력
    python -m eval.publish_e2e --notion-page-url ... --slack-channel ... --yes                         # 실제 발행

- 실제로 쓰므로 --yes 없이는 발행하지 않는다. GitHub 저장소는 --repo(기본 MCP-Capstone/mcp-capstone)만 쓴다.
- 만든 GitHub 이슈는 닫고, Notion 페이지는 보관하고, Slack 메시지는 지운다 (--keep이면 남긴다).
- 결과는 eval/results/publish_e2e/<실행 ID>.json, .md에 남긴다 (커밋하지 않는 폴더).
- 429와 시간 초과는 실제 대상에서 만들기 어려워 다루지 않는다. 자동 테스트(test_publish_mcp_http.py)가 맡는다.
"""

from __future__ import annotations

import argparse
import asyncio
import html
import json
import re
import socket
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

import httpx
from dotenv import dotenv_values

from batch_publish import _ticket, publication_preview
from config import Settings
from publisher import build_notion_page
from runner import run_publish_batch
from schemas import Project, PublishBatchRequest, PublishItemResult, PublishTicket

AGENT_DIR = Path(__file__).resolve().parents[1]
BUSINESS_MCP = AGENT_DIR.parent / "business-mcp"
RESULTS = AGENT_DIR / "eval" / "results" / "publish_e2e"
NOT_EXISTING_LABEL = "e2e-없는-라벨"
SUCCESS = {"PUBLISHED", "LINKED", "UPDATED", "POSTED"}


@dataclass
class Case:
    case_id: str
    condition: str          # 정상 | 권한 없음 | 설정 누락
    target: str             # GITHUB | NOTION | SLACK
    kind: str = "CREATE"    # CREATE | LINK | UPDATE
    project: dict[str, Any] = field(default_factory=dict)  # 기본 프로젝트에서 바꿀 값
    expect: str = "SUCCESS"  # SUCCESS | 기대 오류 코드 접두어 (예: NOTION_, INVALID_ARGUMENT)
    note: str = ""


CASES = [
    Case("GH-CREATE", "정상", "GITHUB", "CREATE", note="이슈 생성. 없는 라벨은 빠지고 보고된다"),
    Case("GH-LINK", "정상", "GITHUB", "LINK", note="GH-CREATE 이슈에 연결 댓글"),
    Case("GH-UPDATE", "정상", "GITHUB", "UPDATE", note="GH-CREATE 이슈 본문 끝에 변경 섹션, 알림 댓글"),
    Case("NOTION-CREATE", "정상", "NOTION", note="'회의 후속 티켓' 폴더에 페이지 생성"),
    Case("SLACK-CREATE", "정상", "SLACK", note="채널에 공지 게시 (멘션 없음)"),
    Case("GH-NO-ACCESS", "권한 없음", "GITHUB", project={"github_repo": "e2e-no-access-repo"},
         expect="REPOSITORY_NOT_ALLOWED|GITHUB_NOT_FOUND|GITHUB_AUTH", note="토큰이 접근할 수 없거나 허용하지 않은 저장소"),
    Case("NOTION-NO-ACCESS", "권한 없음", "NOTION", project={"notion_page_url": "e2e00000000000000000000000000000"},
         expect="NOTION_", note="Integration에 공유되지 않은 페이지"),
    Case("SLACK-NO-ACCESS", "권한 없음", "SLACK", project={"slack_channel_id": "C0E2ENOPE00"},
         expect="SLACK_", note="봇이 접근할 수 없는 채널"),
    Case("NOTION-NO-ROOT", "설정 누락", "NOTION", project={"notion_page_url": None},
         expect="NOTION_ROOT_MISSING", note="프로젝트에 Notion 기획 페이지 URL 없음"),
    Case("SLACK-NO-CHANNEL", "설정 누락", "SLACK", project={"slack_channel_id": None},
         expect="INVALID_ARGUMENT", note="프로젝트에 Slack 채널 ID 없음"),
]


@dataclass
class DupCase:
    """한 번 발행한 티켓을 결과 모름(previous_outcome_unknown)으로 바로 다시 보내고 외부에 몇 건 생겼는지 센다."""
    case_id: str
    target: str
    kind: str = "CREATE"
    retitle: bool = False   # 다시 보내기 전에 제목을 고친다 (#320)
    note: str = ""


DUP_CASES = [
    DupCase("DUP-GH-CREATE", "GITHUB", note="결과 모름 직후 재시도. 기존 이슈를 찾아 새로 만들지 않는다"),
    DupCase("DUP-GH-RETITLE", "GITHUB", retitle=True, note="제목을 고친 뒤 재시도 (#320). 본문 끝줄로 기존 이슈를 찾는다"),
    DupCase("DUP-GH-LINK", "GITHUB", "LINK", note="같은 연결 댓글을 다시 달지 않는다"),
    DupCase("DUP-GH-UPDATE", "GITHUB", "UPDATE", note="같은 변경 섹션과 알림 댓글을 다시 붙이지 않는다"),
    DupCase("DUP-NOTION", "NOTION", note="확인 전에는 다시 만들지 않는다 (NOTION_VERIFY_REQUIRED)"),
    DupCase("DUP-SLACK", "SLACK", note="확인 전에는 다시 보내지 않는다 (SLACK_VERIFY_REQUIRED)"),
]


# ---------------------------------------------------------------- 티켓


def make_ticket(case: Case | DupCase, run_id: str, project: Project, label: str | None, target_issue: int | None) -> PublishTicket:
    title = f"[E2E #303 {run_id}] {case.case_id} 발행 검증"
    content: dict[str, Any] = {
        "work_key": f"e2e-{run_id}-{case.case_id.lower()}",
        "description": f"발행 E2E 검증({case.case_id})으로 만든 테스트 항목이다. 검증이 끝나면 정리한다.",
        "action_text": f"{case.case_id} 발행 경로를 실제 대상에서 확인한다",
        "tasks": ["실제 대상에 발행한다", "다시 조회해 필드를 비교한다"],
        "acceptance_criteria": ["발행 결과와 다시 조회한 내용이 같다"],
        "evidence_quotes": ["발행을 잘 하는가를 수치로 확인한다"],
        "labels": [x for x in (label, NOT_EXISTING_LABEL) if x],
        "assignees": {"names": ["E2E 테스트"], "github": [], "unresolved": []},
        "due": {"text": "2026-10-31"},
    }
    if case.target == "SLACK":
        content["slack"] = {"text": f"*[E2E #303 {run_id}] 발행 검증 공지*\n자동 E2E 검증이 올린 메시지입니다. 검증 뒤 지웁니다.",
                            "reviewers": []}
    return PublishTicket(
        ticket_id=f"e2e-{run_id}-{case.case_id.lower()}", title=title, meeting_id=303,
        meeting_title=f"발행 E2E 검증 {run_id}", meeting_date=date.today(), project=project, content_json=content,
        publish_target=case.target, processing_type=case.kind, target_issue_number=target_issue,
        processing_type_edited=case.kind != "CREATE", review_status="CONFIRMED",
    )


def project_for(case: Case, base: Project) -> Project:
    return base.model_copy(update=case.project)


# ---------------------------------------------------------------- business-mcp


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def start_business_mcp(port: int) -> subprocess.Popen:
    """business-mcp/.env를 읽는 실제 서버. server.py의 MCP_PORT는 .env가 덮어쓰므로 모듈을 불러 포트를 직접 준다."""
    python = BUSINESS_MCP / ".venv" / "bin" / "python"
    if not python.exists():
        sys.exit("business-mcp 가상환경이 필요합니다: cd business-mcp && uv venv --python 3.12 && "
                 "uv pip sync --python .venv/bin/python requirements-dev.txt")
    code = f"import server; server.mcp.run(transport='streamable-http', host='127.0.0.1', port={port})"
    process = subprocess.Popen([str(python), "-c", code], cwd=BUSINESS_MCP,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(200):
        if process.poll() is not None:
            sys.exit("business-mcp 기동 실패")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.1):
                return process
        except OSError:
            time.sleep(.05)
    process.kill()
    sys.exit("business-mcp 준비 시간 초과")


# ---------------------------------------------------------------- 외부 API 조회


class Services:
    """결과 확인과 정리에 쓰는 외부 API. 토큰은 business-mcp/.env에서 읽고 출력하지 않는다."""

    def __init__(self, env: dict[str, str | None], owner: str, repo: str):
        self.owner, self.repo = owner, repo
        self.github = httpx.Client(base_url="https://api.github.com", timeout=20, headers={
            "Authorization": f"Bearer {env.get('GITHUB_TOKEN')}", "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28"})
        self.notion = httpx.Client(base_url="https://api.notion.com/v1", timeout=20, headers={
            "Authorization": f"Bearer {env.get('NOTION_TOKEN')}", "Notion-Version": "2022-06-28"})
        self.slack = httpx.Client(base_url="https://slack.com/api", timeout=20, headers={
            "Authorization": f"Bearer {env.get('SLACK_BOT_TOKEN')}"})

    def first_label(self) -> str | None:
        resp = self.github.get(f"/repos/{self.owner}/{self.repo}/labels", params={"per_page": 1})
        return resp.json()[0]["name"] if resp.status_code == 200 and resp.json() else None

    def issue(self, number: int) -> dict[str, Any]:
        return self.github.get(f"/repos/{self.owner}/{self.repo}/issues/{number}").raise_for_status().json()

    def comments(self, number: int) -> list[str]:
        resp = self.github.get(f"/repos/{self.owner}/{self.repo}/issues/{number}/comments", params={"per_page": 100})
        return [c.get("body") or "" for c in resp.raise_for_status().json()]

    def issues_with(self, work_key: str, since: str) -> list[int]:
        """본문 끝줄에 이 work_key가 있는 이슈 번호. 검색 API는 색인이 늦어 목록 API로 센다."""
        resp = self.github.get(f"/repos/{self.owner}/{self.repo}/issues",
                               params={"state": "all", "since": since, "per_page": 100})
        return [i["number"] for i in resp.raise_for_status().json()
                if "pull_request" not in i and f"work_key: {work_key} " in (i.get("body") or "")]

    def search_lag(self, work_key: str, limit: float = 120) -> float | None:
        """검색 API(재시도 때 기존 이슈를 찾는 경로)가 이 work_key 이슈를 찾기까지 걸린 초. 못 찾으면 None."""
        started = time.monotonic()
        while time.monotonic() - started < limit:
            resp = self.github.get("/search/issues", params={"q": f'"{work_key}" repo:{self.owner}/{self.repo} is:issue'})
            if resp.status_code == 200 and resp.json().get("total_count"):
                return time.monotonic() - started
            time.sleep(2)
        return None

    def slack_count(self, channel: str, text: str, oldest: float) -> int | None:
        data = self.slack.get("/conversations.history", params={"channel": channel, "oldest": f"{oldest:.6f}", "limit": 200}).json()
        if not data.get("ok"):
            return None
        return sum(html.unescape(m.get("text", "")).strip() == text for m in data.get("messages") or [])

    def close_issue(self, number: int) -> None:
        self.github.patch(f"/repos/{self.owner}/{self.repo}/issues/{number}", json={"state": "closed"})

    def notion_page(self, page_id: str) -> tuple[str, list[str]]:
        page = self.notion.get(f"/pages/{page_id}").raise_for_status().json()
        title = "".join(t.get("plain_text", "") for prop in page.get("properties", {}).values()
                        if prop.get("type") == "title" for t in prop.get("title", []))
        blocks = self.notion.get(f"/blocks/{page_id}/children", params={"page_size": 100}).raise_for_status().json()
        return title, notion_lines(blocks.get("results") or [])

    def archive_page(self, page_id: str) -> None:
        self.notion.patch(f"/pages/{page_id}", json={"archived": True})

    def slack_text(self, channel: str, ts: str) -> str | None:
        """메시지 텍스트. 봇에 기록 조회 권한(channels:history)이 없으면 None."""
        data = self.slack.get("/conversations.history", params={
            "channel": channel, "latest": ts, "oldest": ts, "inclusive": "true", "limit": 1}).json()
        if not data.get("ok"):
            return None
        messages = data.get("messages") or []
        return html.unescape(messages[0].get("text", "")) if messages else ""

    def delete_message(self, channel: str, ts: str) -> None:
        self.slack.post("/chat.delete", json={"channel": channel, "ts": ts})


def notion_lines(blocks: list[dict[str, Any]]) -> list[str]:
    """business-mcp notion/blocks.py blocks_to_markdown과 같은 규칙으로 블록 하나를 한 줄로 바꾼다."""
    lines = []
    for block in blocks:
        kind = block.get("type", "")
        data = block.get(kind) or {}
        text = "".join(t.get("plain_text", "") for t in data.get("rich_text") or [])
        if kind.startswith("heading_"):
            lines.append("#" * int(kind[-1]) + " " + text)
        elif kind == "to_do":
            lines.append(f"- [{'x' if data.get('checked') else ' '}] {text}")
        elif kind == "bulleted_list_item":
            lines.append(f"- {text}")
        elif kind == "quote":
            lines.append(f"> {text}")
        elif kind == "divider":
            lines.append("---")
        elif text:
            lines.append(text)
    return lines


def markdown_lines(markdown: str) -> list[str]:
    return [line.rstrip().replace("* ", "- ", 1) if line.startswith("* ") else line.rstrip()
            for line in markdown.splitlines() if line.strip()]


def slack_ts(permalink: str | None) -> str | None:
    """https://<팀>.slack.com/archives/<채널>/p1729760000123456 → 1729760000.123456"""
    found = re.search(r"/p(\d{7,})", permalink or "")
    return f"{found.group(1)[:-6]}.{found.group(1)[-6:]}" if found else None


def notion_page_id(url: str | None) -> str | None:
    found = re.search(r"([0-9a-f]{32})(?:\?|$)", (url or "").replace("-", ""))
    return found.group(1) if found else None


# ---------------------------------------------------------------- 확인


def check(checks: list[dict[str, Any]], name: str, ok: bool, detail: str = "") -> None:
    checks.append({"name": name, "ok": bool(ok), "detail": detail[:300]})


def verify(case: Case, ticket: PublishTicket, item: PublishItemResult, services: Services) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    if case.expect != "SUCCESS":
        codes = case.expect.split("|")
        check(checks, "실패로 끝남", item.status == "FAILED", item.status)
        check(checks, "기대한 오류 코드", any((item.error_code or "").startswith(c) for c in codes), item.error_code or "-")
        check(checks, "결과 모름이 아님 (보내지 않았음)", item.status != "UNKNOWN", item.status)
        return checks
    check(checks, "성공으로 끝남", item.status in SUCCESS, f"{item.status} {item.error_code or ''}")
    if item.status not in SUCCESS:
        return checks
    content = _ticket(ticket)
    if case.target == "GITHUB":
        preview = publication_preview(ticket)
        issue = services.issue(item.github_issue_number)
        labels = {x["name"] for x in issue.get("labels") or []}
        expected_labels = [x for x in ticket.content_json["labels"] if x != NOT_EXISTING_LABEL]
        if case.kind == "CREATE":
            check(checks, "제목", issue["title"] == ticket.title, issue["title"])
            check(checks, "본문 = 미리보기", (issue.get("body") or "").strip() == preview["body"].strip(), "본문 다름")
            check(checks, "라벨 반영", set(expected_labels) <= labels, ", ".join(sorted(labels)))
            check(checks, "없는 라벨 제외와 보고", NOT_EXISTING_LABEL not in labels and NOT_EXISTING_LABEL in item.dropped_labels,
                  ", ".join(item.dropped_labels))
        elif case.kind == "LINK":
            check(checks, "연결 댓글 = 미리보기", preview["body"].strip() in [c.strip() for c in services.comments(item.github_issue_number)],
                  "댓글 없음")
        else:
            body = (issue.get("body") or "").rstrip()
            check(checks, "변경 섹션이 본문 끝에 붙음", body.endswith(preview["body"].strip()), "본문 끝 다름")
            check(checks, "알림 댓글 = 미리보기", (preview["secondaryBody"] or "").strip() in [c.strip() for c in services.comments(item.github_issue_number)],
                  "댓글 없음")
            check(checks, "라벨 추가", set(expected_labels) <= labels, ", ".join(sorted(labels)))
    elif case.target == "NOTION":
        expected = build_notion_page(content, str(ticket.meeting_id), content.get("background", ""))
        page_id = item.notion_page_id or notion_page_id(item.notion_page_url)
        title, lines = services.notion_page(page_id)
        check(checks, "제목", title == expected["title"], title)
        check(checks, "본문 블록 = 출력문", lines == markdown_lines(expected["markdown"]), f"{len(lines)}줄")
    else:
        channel = ticket.project.slack_channel_id
        ts = slack_ts(item.message_url)
        check(checks, "메시지 링크", bool(ts), item.message_url or "-")
        text = services.slack_text(channel, ts) if ts else None
        # 담당자가 저장한 description이 Slack의 단일 발행 본문이고, 없을 때만 slack.text를 쓴다 (batch_publish, #312)
        expected = (content.get("description") or content["slack"]["text"]).strip()
        if text is None:
            check(checks, "본문 = 발행 본문 (확인 불가: 봇에 channels:history 권한 없음)", True, "건너뜀")
        else:
            check(checks, "본문 = 발행 본문", text.strip() == expected, text[:80])
    return checks


# ---------------------------------------------------------------- 실행


async def publish(ticket: PublishTicket, settings: Settings) -> tuple[PublishItemResult, float]:
    started = time.monotonic()
    result = await run_publish_batch(PublishBatchRequest(batch_id=f"e2e-{uuid.uuid4().hex[:8]}", tickets=[ticket]), settings)
    return result.items[0], time.monotonic() - started


async def run_duplicate(case: DupCase, ticket: PublishTicket, settings: Settings, services: Services,
                        since: str, started: float, delay: float = 0) -> dict[str, Any]:
    """처음 발행 뒤 결과 모름으로 delay초 뒤 다시 보내고, 외부에 생긴 항목 수를 센다. 1건이어야 한다."""
    first, _ = await publish(ticket, settings)
    await asyncio.sleep(delay)
    retry_ticket = ticket.model_copy(update={"previous_outcome_unknown": True})
    if case.retitle:
        retry_ticket = retry_ticket.model_copy(update={"title": ticket.title + " (제목 수정)"})
    retry, seconds = await publish(retry_ticket, settings)
    preview = publication_preview(ticket) if case.target == "GITHUB" else {}
    issues: list[int] = []
    lag = None
    if case.target == "GITHUB" and case.kind == "CREATE":
        # 목록 API도 만든 직후에는 빠질 수 있어, 두 발행이 돌려준 번호와 목록을 합쳐 센다
        lag = services.search_lag(ticket.content_json["work_key"])
        known = {n for n in (first.github_issue_number, retry.github_issue_number) if n}
        issues = sorted(known | set(services.issues_with(ticket.content_json["work_key"], since)))
        count = len(issues)
        same = retry.github_issue_number == first.github_issue_number
        expect = f"재시도가 같은 이슈 #{first.github_issue_number}를 돌려줌"
    elif case.target == "GITHUB" and case.kind == "LINK":
        count = [c.strip() for c in services.comments(ticket.target_issue_number)].count(preview["body"].strip())
        same, expect = retry.status == "LINKED", "재시도가 연결됨으로 끝남"
    elif case.target == "GITHUB":
        count = (services.issue(ticket.target_issue_number).get("body") or "").count(preview["body"].strip())
        notes = [c.strip() for c in services.comments(ticket.target_issue_number)].count((preview["secondaryBody"] or "").strip())
        count = max(count, notes)
        same, expect = retry.status == "UPDATED", "재시도가 갱신됨으로 끝남"
    elif case.target == "SLACK":
        text = (ticket.content_json.get("description") or "").strip()
        count = services.slack_count(ticket.project.slack_channel_id, text, started)
        same, expect = retry.error_code == "SLACK_VERIFY_REQUIRED", "재시도가 SLACK_VERIFY_REQUIRED로 막힘"
    else:
        # Notion은 하위 페이지 목록을 바로 셀 수단이 없어, 재시도가 페이지를 돌려주지 않았는지로 센다
        count = 1 + bool(retry.notion_page_id or retry.notion_page_url)
        same, expect = retry.error_code == "NOTION_VERIFY_REQUIRED", "재시도가 NOTION_VERIFY_REQUIRED로 막힘"
    checks: list[dict[str, Any]] = []
    check(checks, "처음 발행 성공", first.status in SUCCESS, f"{first.status} {first.error_code or ''}")
    check(checks, expect, same, f"{retry.status} {retry.error_code or ''} #{retry.github_issue_number or '-'}")
    check(checks, "외부에 1건", count == 1, f"{count}건")
    return {"case": case.case_id, "condition": "중복", "target": case.target, "kind": case.kind, "channel": ticket.project.slack_channel_id,
            "seconds": seconds, "checks": checks, "count": count, "issues": issues,
            "searchLagSeconds": None if lag is None else round(lag, 1),
            "item": first.model_dump(by_alias=True, mode="json"), "retry": retry.model_dump(by_alias=True, mode="json")}


def cleanup(rows: list[dict[str, Any]], services: Services) -> list[str]:
    done = []
    for row in rows:
        item = row["item"]
        if len(row.get("issues") or []) > 1:
            for number in row["issues"]:
                if number != item.get("githubIssueNumber"):
                    services.close_issue(number)
                    done.append(f"GitHub #{number} 닫음 (중복)")
        if row["target"] == "GITHUB" and row["kind"] == "CREATE" and item.get("githubIssueNumber"):
            services.close_issue(item["githubIssueNumber"])
            done.append(f"GitHub #{item['githubIssueNumber']} 닫음")
        elif row["target"] == "NOTION" and (item.get("notionPageId") or item.get("notionPageUrl")):
            services.archive_page(item.get("notionPageId") or notion_page_id(item.get("notionPageUrl")))
            done.append("Notion 페이지 보관")
        elif row["target"] == "SLACK" and slack_ts(item.get("messageUrl")):
            services.delete_message(row["channel"], slack_ts(item["messageUrl"]))
            done.append("Slack 메시지 삭제")
    return done


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    normal = [r for r in rows if r["condition"] == "정상"]
    failures = [r for r in rows if r["condition"] in {"권한 없음", "설정 누락"}]
    duplicates = [r for r in rows if r["condition"] == "중복"]
    checks = [c for r in normal for c in r["checks"] if not c["name"].startswith("성공으로")]
    seconds = sorted(r["seconds"] for r in normal)
    return {
        "발행 성공률": f"{sum(r['item']['status'] in SUCCESS for r in normal)}/{len(normal)}",
        "필드 정확도": f"{sum(c['ok'] for c in checks)}/{len(checks)}",
        "실패 조건 기대 일치": f"{sum(all(c['ok'] for c in r['checks']) for r in failures)}/{len(failures)}",
        "처리 시간 (정상, 초)": {"중앙값": round(seconds[len(seconds) // 2], 2), "최대": round(seconds[-1], 2)} if seconds else {},
        "중복 발행 수": f"{sum(max((r['count'] or 0) - 1, 0) for r in duplicates)} (재시도 {len(duplicates)}건)",
        "중복 방지 기대 일치": f"{sum(all(c['ok'] for c in r['checks']) for r in duplicates)}/{len(duplicates)}",
    }


def markdown_report(run_id: str, summary: dict[str, Any], rows: list[dict[str, Any]], cleaned: list[str]) -> str:
    lines = [f"# 발행 E2E 결과 {run_id}", "", "| 지표 | 값 |", "|---|---|"]
    lines += [f"| {k} | {v} |" for k, v in summary.items()]
    lines += ["", "| 시나리오 | 조건 | 결과 | 오류 코드 | 시간(초) | 확인 |", "|---|---|---|---|---|---|"]
    for r in rows:
        failed = [c["name"] for c in r["checks"] if not c["ok"]]
        last = r.get("retry") or r["item"]
        status = f"{r['item']['status']} -> {last['status']} (외부 {r['count']}건)" if "retry" in r else last["status"]
        lines.append(f"| {r['case']} | {r['condition']} | {status} | {last.get('errorCode') or '-'} | "
                     f"{r['seconds']:.2f} | {'통과' if not failed else '실패: ' + ', '.join(failed)} |")
    if cleaned:
        lines += ["", "정리: " + ", ".join(cleaned)]
    return "\n".join(lines) + "\n"


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", default="MCP-Capstone/mcp-capstone")
    parser.add_argument("--notion-page-url", required=True)
    parser.add_argument("--slack-channel", required=True)
    parser.add_argument("--only", help="시나리오 ID 쉼표 구분 (기본 전체)")
    parser.add_argument("--yes", action="store_true", help="실제로 발행한다")
    parser.add_argument("--retry-delay", type=float, default=0, help="중복 시나리오에서 다시 보내기 전 기다릴 초 (기본 0: 바로)")
    parser.add_argument("--keep", action="store_true", help="만든 이슈, 페이지, 메시지를 정리하지 않는다")
    args = parser.parse_args()

    owner, repo = args.repo.split("/", 1)
    cases = [c for c in CASES if not args.only or c.case_id in args.only.split(",")]
    dup_cases = [c for c in DUP_CASES if not args.only or c.case_id in args.only.split(",")]
    print("시나리오:")
    for c in cases:
        print(f"  {c.case_id:18} {c.condition:6} {c.target:7} {c.kind:7} 기대={c.expect}  {c.note}")
    for c in dup_cases:
        print(f"  {c.case_id:18} 중복   {c.target:7} {c.kind:7} 기대=외부 1건  {c.note}")
    if not args.yes:
        print("\n계획만 출력했습니다. 실제로 발행하려면 --yes를 붙이세요.")
        return

    env = dotenv_values(BUSINESS_MCP / ".env")
    services = Services(env, owner, repo)
    label = services.first_label()
    base = Project(github_owner=owner, github_repo=repo, notion_page_url=args.notion_page_url, slack_channel_id=args.slack_channel)
    run_id = datetime.now().strftime("%m%d%H%M%S")
    port = free_port()
    server = start_business_mcp(port)
    settings = Settings(mcp_server_url=f"http://127.0.0.1:{port}/mcp", rag_enabled=False, dry_run=False)
    rows: list[dict[str, Any]] = []
    base_issue: int | None = None
    since = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    started = time.time()
    try:
        for case in cases:
            target_issue = base_issue if case.kind in {"LINK", "UPDATE"} else None
            if case.kind in {"LINK", "UPDATE"} and target_issue is None:
                print(f"  {case.case_id}: GH-CREATE 이슈가 없어 건너뜀")
                continue
            ticket = make_ticket(case, run_id, project_for(case, base), label, target_issue)
            item, seconds = await publish(ticket, settings)
            if case.case_id == "GH-CREATE" and item.status == "PUBLISHED":
                base_issue = item.github_issue_number
            checks = verify(case, ticket, item, services)
            rows.append({"case": case.case_id, "condition": case.condition, "target": case.target, "kind": case.kind,
                         "channel": ticket.project.slack_channel_id, "seconds": seconds, "checks": checks,
                         "item": item.model_dump(by_alias=True, mode="json")})
            mark = "통과" if all(c["ok"] for c in checks) else "실패"
            print(f"  {case.case_id:18} {item.status:9} {item.error_code or '-':24} {seconds:6.2f}초  {mark}")
        dup_issue: int | None = None
        for dup in dup_cases:
            target_issue = dup_issue if dup.kind in {"LINK", "UPDATE"} else None
            if dup.kind in {"LINK", "UPDATE"} and target_issue is None:
                print(f"  {dup.case_id}: DUP-GH-CREATE 이슈가 없어 건너뜀")
                continue
            ticket = make_ticket(dup, run_id, base, label, target_issue)
            row = await run_duplicate(dup, ticket, settings, services, since, started, args.retry_delay)
            if dup.case_id == "DUP-GH-CREATE" and row["item"].get("status") == "PUBLISHED":
                dup_issue = row["item"]["githubIssueNumber"]
            rows.append(row)
            mark = "통과" if all(c["ok"] for c in row["checks"]) else "실패"
            lag = f"  검색 색인 {row['searchLagSeconds']}초" if row["searchLagSeconds"] is not None else ""
            print(f"  {dup.case_id:18} 재시도 {row['retry']['status']:9} {row['retry'].get('errorCode') or '-':24} "
                  f"외부 {row['count']}건  {mark}{lag}")
    except BaseException:
        # 중간에 멈춰도 이미 만든 항목은 정리한다
        if not args.keep:
            print("중단: " + ", ".join(cleanup(rows, services)))
        raise
    finally:
        server.terminate()
        server.wait(timeout=10)
    cleaned = [] if args.keep else cleanup(rows, services)
    summary = summarize(rows)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{run_id}.json").write_text(json.dumps({"runId": run_id, "summary": summary, "rows": rows, "cleaned": cleaned},
                                                       ensure_ascii=False, indent=2))
    report = markdown_report(run_id, summary, rows, cleaned)
    (RESULTS / f"{run_id}.md").write_text(report)
    print("\n" + report)


if __name__ == "__main__":
    asyncio.run(main())
