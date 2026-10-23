"""테스트용 가짜 GitHub과 가짜 임베딩. 실제 GitHub·OpenAI를 부르지 않는다."""

from __future__ import annotations

import hashlib

import httpx

import github


def gh_item(number, title, body="", state="open", updated_at="2026-10-01T00:00:00Z", pr=False, closed_at=None):
    """GitHub 이슈 API 응답 한 건과 같은 모양의 dict."""
    item = {
        "number": number,
        "title": title,
        "body": body,
        "state": state,
        "html_url": f"https://github.com/o/r/issues/{number}",
        "updated_at": updated_at,
        "closed_at": closed_at,
    }
    if pr:
        item["pull_request"] = {"url": "..."}
    return item


class FakeGitHub:
    """저장소별 이슈 목록을 들고 있다가 GitHub REST처럼 응답한다 (since 필터, 페이지 나누기 포함)."""

    def __init__(self, repos: dict[str, list[dict]], per_page: int = 2):
        self.repos = repos  # {"o/r": [gh_item, ...]}
        self.comments: dict[tuple[str, str], list[dict]] = {}
        self.per_page = per_page
        self.requests: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        parts = request.url.path.strip("/").split("/")  # repos/{o}/{r}/issues[/{n}]
        key = f"{parts[1]}/{parts[2]}"
        if key not in self.repos:
            return httpx.Response(404, json={"message": "Not Found"})
        items = self.repos[key]
        if len(parts) == 6 and parts[-1] == "comments":
            return httpx.Response(200, json=self.comments.get((key, parts[4]), []))
        if len(parts) == 5:
            found = [i for i in items if str(i["number"]) == parts[4]]
            return httpx.Response(200, json=found[0]) if found else httpx.Response(404, json={"message": "Not Found"})
        since = request.url.params.get("since")
        if since:
            items = [i for i in items if i["updated_at"] >= since]
        page = int(request.url.params.get("page", "1"))
        chunk = items[(page - 1) * self.per_page : page * self.per_page]
        headers = {}
        if page * self.per_page < len(items):
            nxt = request.url.copy_set_param("page", str(page + 1))
            headers["link"] = f'<{nxt}>; rel="next"'
        return httpx.Response(200, json=chunk, headers=headers)

    def client(self) -> httpx.Client:
        return github.make_client(transport=httpx.MockTransport(self.handler))


class FakeEmbedder:
    """문장마다 항상 같은 4차원 벡터를 돌려준다. 몇 번, 몇 문장을 임베딩했는지 기록한다."""

    def __init__(self, model: str = "fake-embed"):
        self.model = model
        self.calls: list[list[str]] = []

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(list(texts))
        out = []
        for t in texts:
            h = hashlib.sha256(t.encode()).digest()
            out.append([b / 255 + 0.01 for b in h[:4]])
        return out

    @property
    def embedded(self) -> int:
        return sum(len(c) for c in self.calls)


class KeywordEmbedder(FakeEmbedder):
    """단어가 겹칠수록 벡터가 가까워지는 가짜 임베딩. 검색 순서를 예측할 수 있게 한다.

    벡터 = 사전 단어마다 문장에 나온 횟수 (+ 모든 벡터가 0이 되지 않도록 작은 값)
    """

    VOCAB = ["로그인", "버튼", "세모", "네모", "csv", "내보내기", "권한", "감사", "로그", "결제"]

    def embed(self, texts):
        self.calls.append(list(texts))
        return [[t.lower().count(w) + 0.01 for w in self.VOCAB] for t in texts]
