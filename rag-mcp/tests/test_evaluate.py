from eval.evaluate import calculate_metrics, results_search


def test_calculate_metrics_reports_recall_mrr_and_score_distribution():
    cases = [
        {"query": "로그인", "expected_issue": 1},
        {"query": "CSV", "expected_issue": 2},
    ]
    answers = {
        "로그인": [{"number": 1, "score": 0.9}],
        "CSV": [{"number": 3, "score": 0.8}, {"number": 2, "score": 0.5}],
    }

    report = calculate_metrics(cases, lambda query: answers[query])

    assert report["recall_at_k"] == 1.0
    assert report["mrr"] == 0.75
    assert report["score_distribution"] == {
        "count": 3,
        "min": 0.5,
        "median": 0.8,
        "mean": 0.7333,
        "max": 0.9,
    }


def test_results_search_unwraps_tool_response():
    # 실제 도구 응답은 dict이므로 results만 꺼내 평가에 넘긴다
    tool = lambda owner, repo, query, k: {"repository": f"{owner}/{repo}", "index": {}, "results": [{"number": 7, "score": 0.9}]}
    cases = [{"query": "q", "expected_issue": 7}]
    report = calculate_metrics(cases, results_search(tool, "o", "r", 5), 5)
    assert report["recall_at_k"] == 1.0
