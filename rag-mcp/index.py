"""기존 이슈를 미리 색인하는 명령.

사용:
    python index.py                              # .env의 GITHUB_OWNER/GITHUB_REPO, 바뀐 이슈만
    python index.py --owner MCP-Capstone --repo mcp-capstone
    python index.py --full                       # 전체 다시 색인

GitHub 토큰과 OpenAI 키는 rag-mcp/.env에서 읽는다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from dotenv import load_dotenv
from mcp.server.mcpserver.exceptions import ToolError

import github
from config import load_settings
from embeddings import OpenAIEmbedder
from indexer import sync_issues
from store import IssueStore


def main() -> int:
    load_dotenv(Path(__file__).resolve().parent / ".env", override=True)
    settings = load_settings()

    p = argparse.ArgumentParser(description="GitHub 이슈를 Vector DB에 색인한다")
    p.add_argument("--owner", default=settings.github_owner, help="저장소 owner (기본: .env의 GITHUB_OWNER)")
    p.add_argument("--repo", default=settings.github_repo, help="저장소 이름 (기본: .env의 GITHUB_REPO)")
    p.add_argument("--full", action="store_true", help="기존 색인을 지우고 전체를 다시 색인")
    args = p.parse_args()

    try:
        embedder = OpenAIEmbedder(settings.openai_api_key, settings.embedding_model)
        with github.make_client() as client:
            result = sync_issues(
                args.owner, args.repo, client=client, embedder=embedder,
                store=IssueStore(settings.chroma_path), full=args.full,
            )
    except ToolError as exc:
        print(f"색인 실패: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
