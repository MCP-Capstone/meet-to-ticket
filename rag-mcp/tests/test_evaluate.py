from eval.evaluate import calculate_metrics


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
