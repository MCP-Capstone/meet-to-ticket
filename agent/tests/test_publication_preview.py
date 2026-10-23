"""저장된 초안의 미리보기와 실제 쓰기 인자 일치 및 조회 무부작용."""
import pytest
from fastapi.testclient import TestClient
from batch_publish import publication_preview, publish_one
from test_batch_publish import BatchPublisher, ticket


@pytest.mark.anyio
@pytest.mark.parametrize("kind,number", [("CREATE", None), ("LINK", 7), ("UPDATE", 8)])
async def test_preview_matches_actual_publisher_body(kind, number):
    source = ticket("preview", kind, number)
    source.content_json.update({
        "action_text": "회의에서 요청한 원문", "description": "담당자가 저장한 최종 설명",
        "tasks": ["API 작성", "오류 흐름 확인"], "acceptance_criteria": ["오류를 안내한다"],
        "evidence_quotes": ["회의에서 요청한 원문"], "open_questions": ["응답 형식 확인"],
        "assignees": {"names": ["나윤"], "github": ["jadoolim"], "unresolved": []},
        "due": {"text": "금요일", "date": "2026-10-30", "time": "14:00"},
        "issue_body": "사용하면 안 되는 이전 본문",
    })
    preview = publication_preview(source)
    publisher = BatchPublisher()
    await publish_one(source, publisher)
    args = publisher.calls[0][1]
    assert preview["body"] == args["append_section" if kind == "UPDATE" else "body"]
    assert "담당자가 저장한 최종 설명" in preview["body"]
    assert "사용하면 안 되는 이전 본문" not in preview["body"]
    if kind == "CREATE":
        assert "API 작성" in preview["body"] and "오류를 안내한다" in preview["body"]
        assert "2026-10-30" in preview["body"] and "jadoolim" in preview["body"]
        assert preview["githubAssignees"] == args["assignees"]
    elif kind == "UPDATE":
        assert preview["secondaryBody"] == args["note"]
        assert preview["labels"] == args["add_labels"]
        assert preview["githubAssignees"] == []
    else:
        assert preview["labels"] == [] and preview["githubAssignees"] == []


def test_endpoint_is_read_only_and_validates_target(monkeypatch):
    import main
    before = dict(main._jobs)
    def forbidden(*args, **kwargs):
        raise AssertionError("미리보기에서 작업/LLM/MCP를 호출하면 안 됨")
    monkeypatch.setattr(main, "run_publish_batch", forbidden)
    with TestClient(main.app) as client:
        source = ticket("preview", "CREATE")
        payload = source.model_dump(by_alias=True, mode="json")
        response = client.post("/publication-preview", json=payload)
        assert response.status_code == 200
        assert response.json() == publication_preview(source)
        payload["publishTarget"] = "SLACK"
        assert client.post("/publication-preview", json=payload).status_code == 422
        assert client.post("/publication-preview", json={}).status_code == 422
    assert main._jobs == before
