"""백엔드 없이 바로 실행해 보는 콘솔 진입점.

agent 폴더에서
    # 1) 백엔드가 보낼 요청 JSON(목데이터)으로 실행 — 실제 연동과 같은 입력
    python cli.py --request samples/requests/csv_check.json --dry-run
    python cli.py --request samples/requests/csv_check.json

    # 2) 회의 요약 파일로 실행 — 저장소·팀원은 옵션과 samples/members.json에서
    python cli.py samples/10min_1_summary.md --dry-run

--dry-run이면 GitHub에 발행하지 않고 추출·검수 결과만 본다 (OPENAI_API_KEY만 있으면 됨).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import uuid
from pathlib import Path

from config import load_env, load_settings, validate_settings  # noqa: E402

load_env()
from parsing import parse_summary  # noqa: E402
from runner import run  # noqa: E402
from schemas import Meeting, Project, ProjectMember, RunRequest  # noqa: E402


def request_from_summary(path: Path, members_path: Path, owner: str, repo: str) -> RunRequest:
    text = path.read_text(encoding="utf-8")
    parsed = parse_summary(text)
    raw_members = json.loads(members_path.read_text(encoding="utf-8")) if members_path.exists() else []
    members = [ProjectMember(project_member_id=i, **m) for i, m in enumerate(raw_members, start=1)]
    return RunRequest(
        task_id=str(uuid.uuid4()),
        meeting=Meeting(
            meeting_id=parsed.meeting_id or path.stem,
            title=parsed.title,
            meeting_date=parsed.meeting_date,
            original_filename=path.name,
            original_content=text,
        ),
        project=Project(github_owner=owner, github_repo=repo),
        project_members=members,
    )


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("summary", nargs="?", help="회의 요약 .md/.txt 파일 (--request를 쓰면 생략)")
    p.add_argument("--request", help="백엔드 요청 형식의 JSON 파일 (POST /runs 본문과 같음)")
    p.add_argument("--members", default="samples/members.json", help="요약 파일로 실행할 때 팀원 명단 JSON")
    p.add_argument("--owner", default="MCP-Capstone", help="요약 파일로 실행할 때 저장소 owner")
    p.add_argument("--repo", default="mcp-capstone", help="요약 파일로 실행할 때 저장소 이름")
    p.add_argument("--dry-run", action="store_true", help="GitHub에 발행하지 않음")
    p.add_argument("--out", default="", help="결과 JSON(백엔드 보고 형식)을 저장할 경로")
    args = p.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    if args.request:
        req = RunRequest.model_validate_json(Path(args.request).read_text(encoding="utf-8"))
    elif args.summary:
        req = request_from_summary(Path(args.summary), Path(args.members), args.owner, args.repo)
    else:
        p.error("회의 요약 파일 또는 --request 중 하나가 필요합니다")

    settings = load_settings(validate=False)
    if args.dry_run:
        settings.dry_run = True
    validate_settings(settings)  # --dry-run을 반영한 뒤 검증한다

    print(f"작업: {req.task_id} · 회의: {req.meeting.meeting_id} · "
          f"저장소: {req.project.github_owner}/{req.project.github_repo}{' · DRY-RUN' if settings.dry_run else ''}")
    result = asyncio.run(run(req, settings))

    print()
    print(f"상태: {result.status}  오류: {result.error_code or '-'} {result.error_message or ''}")
    print(f"요약: {result.summary}")
    print("-" * 100)
    for item in result.items:
        link = item.github_issue_url or ""
        print(f"{item.work_key or '-':<14} {item.review_status:<12} {item.publish_status:<11} {item.title[:40]:<42} {link}")
        if item.reason:
            print(f"{'':<14} 사유: {item.reason[:200]}")
        review = item.content_json.get("review", {})
        for note in review.get("notes", [])[:3]:
            print(f"{'':<14} 검수 지적: {note[:200]}")
        for w in item.content_json.get("warnings", [])[:2]:
            print(f"{'':<14} 경고: {w[:200]}")
        if item.error_message:
            print(f"{'':<14} 오류: {item.error_message[:200]}")
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(
            json.dumps(result.model_dump(by_alias=True, mode="json"), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"\n결과 저장: {args.out}")


if __name__ == "__main__":
    main()
