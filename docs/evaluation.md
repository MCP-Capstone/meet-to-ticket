# 평가셋과 회귀 평가

프롬프트나 모델을 바꾸기 전과 후를 같은 평가셋, 같은 채점 기준으로 비교하기 위한 체계다. 코드는 `agent/eval/`에 있다.
이 문서는 목표 지표 설정 가이드, 평가셋 데이터 생성 가이드, baseline 측정 결과(`phase1-end`)를 저장소 문서로 정리한 것이다. (이슈 #266)

## 1. 한눈에 보기

| 항목 | 위치 |
|---|---|
| 평가셋 v2.1 입력 요약본 (에이전트에 넣는 것) | `agent/eval/dataset/v2.1/inputs/` |
| 정답 reference (에이전트에 넣지 않는 것) | `agent/eval/dataset/v2.1/references/` |
| 평가용 명단 | `agent/eval/dataset/v2.1/members.json` |
| 중복 판단 시드 이슈 목록, 문항별 기대 판정 | `agent/eval/dataset/v2.1/dedupe_seed_issues.md`, `manifest.json` |
| 문항 목록, 회의 ID 대응표, 버전 기록 | `questions.csv`, `meeting_id_map.md`, `version_history.md` (같은 폴더) |
| 추출 채점 / 중복 판단 채점 | `agent/eval/score_extract.py` / `agent/eval/score_dedupe.py` |
| 지표표 출력 (결과 폴더 채점) | `python -m eval.score` |
| 한 명령 실행기 (결과 JSON 생성 + 채점표) | `python -m eval.run_eval` |
| 목표값 (코드가 읽는 값) | `agent/eval/targets.json` |
| 단위 테스트 (mock/저장된 결과 JSON) | `agent/tests/test_eval_score.py`, `agent/tests/test_eval_run.py` |

정답 reference는 입력 폴더와 분리해 둔다. 실행기는 `inputs/`의 파일만 에이전트에 넘기고, `references/`는 채점기만 읽는다.

## 2. 결정 기록 (목표 지표)

| 구분 | 지표 | baseline | 목표 |
|---|---|---|---|
| 주 지표 1 | 추출 정밀도 | 46.9% | 80% 이상 |
| 주 지표 2 | 중복 판단 정확도 (판정+대상) | 77.8% | 90% 이상 |
| 보조 | 기한 정확도 | 0% | 90% 이상 |
| 보조 | 담당자/기한 오류율 | 담당자 0%, 기한 100% | 5% 이하 |
| 보조 | 중복 처리 충족률 | 61.1% | 90% 이상 |
| 운영 | 중복 발행률 | 미측정 | 1% 미만 |
| 운영 | 오발행률 | 미측정 | 3% 이하 (초과 시 자동 발행 수동 복귀) |
| 지키는 선 | 업무 재현율 | 100% | 90% 이상 유지 |
| 지키는 선 | 처리 시간 | 24.9초 / 13.5초 | baseline의 1.5배 이하 |

- 고신뢰 항목 자동 발행은 항목 정밀도 80% 이상, 담당자/기한 오류율 5% 이하가 2주 연속 유지될 때만 검토한다. 그 전에는 항상 사람 확인 후 발행한다.
- 평가셋 버전이 다르면 점수를 직접 비교하지 않는다. v2.1 기준 baseline은 `phase1-end`로 문항마다 3회 다시 측정한다.

위 baseline 숫자는 v1 평가셋(18문항) 기준이다. v2.1 baseline 재측정 전까지는 참고값이며, 재측정 후 이 표의 baseline 열을 v2.1 값으로 바꾼다. 이 표는 설계서 STEP 02 성공 지표와 같은 숫자를 쓴다. 목표값을 바꾸면 `agent/eval/targets.json`, 이 표, 설계서를 함께 바꾼다.

운영 지표(중복 발행률, 오발행률)는 실제 발행 이력이 있어야 잴 수 있어 평가셋 dry-run으로는 측정하지 않는다. 지표표에는 "운영 측정"으로만 표시한다.

### 지표 정의

| 지표 | 정의 | 계산 |
|---|---|---|
| 추출 정밀도 | 생성한 티켓 중 정답 업무와 연결된 비율. 같은 업무를 쪼갠 두 번째 이후 티켓은 오답 | 연결된 티켓 / 전체 추출 티켓 |
| 업무 재현율 | 정답 업무 중 찾은 비율 | 연결된 정답 업무 / 전체 정답 업무 |
| 기한 정확도 | 연결된 티켓 중 날짜와 시각이 모두 맞은 비율. 정답이 오전/오후/퇴근 전/없음이면 시각은 비어 있어야 정답 | 일치 / 연결된 티켓 |
| 담당자 오류율, 기한 오류율 | 1 - 담당자 정확도, 1 - 기한 정확도. "확인 필요"가 정답이면 멤버 중 아무도 지정하지 않아야 정답, 외부 담당자는 원문 이름이고 GitHub assignee가 비어야 정답 | 위와 같음 |
| 건수 일치율 | 추출 건수가 기대 건수와 같은 실행의 비율 | 실행 단위 |
| 문항 충족률 | 건수, 담당자, 기한이 모두 맞고 "하면 안 되는 것"이 없는 실행의 비율 | 실행 단위 |
| 중복 판단 정확도 | 판정(SAME/CHANGE/RELATED/UNRELATED)과 대상 이슈 번호가 모두 맞은 비율 | 실행 단위 |
| 중복 처리 충족률 | 판정에 따른 처리 유형(연결, 갱신, 신규 + 기존 번호 표기, 신규)이 기대와 같고 대상도 맞은 비율 | 실행 단위 |
| 처리 시간 | 결과 JSON `metrics.durationMs` 평균 | 추출 문항과 중복 판단 문항을 따로 평균 |

문항마다 3회 실행한 결과를 합산한다(같은 입력에도 결과가 달라서 1회 값은 믿기 어렵다). 표본이 작아서(중복 판단 18회, 업무 수십 건) 1~2건 차이로 몇 %p가 움직이므로, 목표를 볼 때 "몇 건 이하 오답"도 함께 본다.

### 채점 연결 방식과 한계

- 티켓의 제목, 할 일 원문, 근거 인용에 정답 업무 키(`CSV-01` 등)가 있으면 그 키로 정답과 연결한다.
- 키가 없는 정답(`EV-11` 등)은 제목 유사도와 담당자 일치로 남은 티켓과 짝짓는다. 근사 방식이므로 프롬프트를 크게 바꾼 뒤에는 `scores.json`의 `detail`에서 연결 결과를 한 번 훑어본다. 애매하면 사람 2명이 내용 기준으로 판정한다.
- 정답 업무와 연결되지 않은 티켓은 모두 "초과"로 센다. 기대 0건 회의에서 나온 티켓도 초과다.
- 자동 판정하지 않는 "하면 안 되는 것"(예: 요약에 없는 내용을 지어냄)은 사람이 확인한다. 자동으로 보는 것은 `manifest.json`의 `forbidden_text`(ev30의 가짜 이메일/전화번호)뿐이다. 발행 본문 필드(제목, 설명, 작업, 완료 조건, 미결 질문)에 나오면 문항 불충족이고, 근거 인용/경고/이슈 본문의 근거 칸에만 남으면 충족을 깎지 않고 지표표에 경고로 따로 표시한다(근거 칸은 이슈 본문에 실리므로 사람이 확인한다).
- 채점 대상은 `publish_target`이 GITHUB/NOTION인 실제 티켓뿐이다. draft 모드가 덧붙이는 Slack 회의 알림 항목(`publish_target`이 SLACK, work_key `<회의ID>-notice`)은 추출/중복 판단 채점에서 뺀다. `publish_target`이 없는 phase1-end 결과는 GitHub 티켓으로 본다.
- 키가 없는 정답의 유사도 연결은 제목 유사도가 하한(0.3) 이상일 때만 담당자 일치 보너스를 더한다. 담당자만 같은 무관한 티켓은 연결하지 않는다.
- 중복 판단 처리 유형은 결과의 `dedupe.handling`을 읽는다. 이 필드가 없는 phase1-end 결과는 `linked_existing`, `relation`으로 같은 기준에 맞춰 읽는다.

## 3. 평가셋 구성과 버전 규칙

v2.1은 채점 단위 37문항(`manifest.json`의 `scored_unit_count`)이며 에이전트에 넣는 회의 요약본은 30개(`meeting_input_count`)다. 기존 3개 회의는 회의 1개에서 업무 단위로 10문항을 센다. 두 숫자는 테스트가 평가셋 파일과 대조한다.

| 묶음 | 문항 | 세는 단위 | 확인하는 것 |
|---|---|---|---|
| 기존 3개 회의 | 10min_1 (7), 30min_1 (1), 60min_1 (2) | 업무 단위 10문항 | 서술형 요약에서 업무, 담당자, 기한 추출 |
| 신규 추출 회의 | ev11 ~ ev30 (ev28 없음) 19개 | 회의 단위 1문항씩 | 상대 기한, 연도/월 경계, 호칭 모호, 외부 담당자, 0건 회의, 철회, 프롬프트 인젝션, 개인정보 등 |
| 기존 추가 추출 | ev31, ev32 | 회의 단위 1문항씩 | 상대 기한("내일"), 기존 업무 변경을 신규로 만들지 않음 |
| 중복 판단 | ev41 ~ ev46 | 회의 단위 1문항씩 | SAME/CHANGE(열림, 닫힘), RELATED, UNRELATED와 처리 유형 |

버전 규칙

- 평가셋은 `agent/eval/dataset/<버전>/` 폴더 하나가 한 버전이다. 한 번 정한 버전의 파일은 오타 수정 외에 고치지 않는다.
- 문항 추가, 삭제, 정답 변경, 명단 변경, 요약본 본문 변경은 새 버전(`v2.2` 등)으로 올리고 `version_history.md`에 변경 내용을 적는다.
- 점수는 같은 버전끼리만 비교한다. 버전이 바뀌면 `phase1-end`로 baseline을 다시 잰다. 채점기는 `--baseline`의 버전이 다르면 비교를 거절한다.
- 요약본 안의 회의 ID는 정답 단서를 막으려고 `MTG-번호`로 되어 있다. 파일 이름이 문항 ID이고 채점은 파일 이름 기준이다(`meeting_id_map.md`).
- 이관하면서 원본 팀 자료의 em dash는 `-`로, 가운뎃점은 `/`로 바꿨다. 문항의 의미와 정답은 그대로다.
- 평가셋의 이메일, 전화번호, 로그인명은 모두 가짜 값이다(예: `example.test`, `010-0000-0000`, `테스트용-로그인-N`). 실제 개인정보를 넣지 않는다.
- 원본 회의록(요약본의 근거 대화 편집본)은 저장소에 두지 않고 팀 내부 자료로 보관한다.

## 4. 실행 방법

사전 조건

- `agent/` 가상환경을 켠다. (`agent/README.md`)
- `agent/.env`에 `OPENAI_API_KEY`를 둔다. 키는 저장소에 올리지 않는다.
- 중복 판단 문항(ev41 ~ ev46)을 돌릴 때는 mcp-server(8000)와 rag-mcp(8002)를 로컬에서 띄운다. 대조 저장소는 `MCP-Capstone/meet-to-ticket-eval`이다(5절).

**dry-run은 필수다.** 실행기는 항상 `cli.py --dry-run`으로만 에이전트를 부르고 GitHub에 발행하지 않는다. 이를 끄는 옵션은 없다. `agent/.env`의 `DRY_RUN`이 `false`여도 실행기가 `--dry-run`과 환경 변수 `DRY_RUN=true`를 함께 준다. 직접 `cli.py`를 부를 때도 `--dry-run`을 빼지 않는다.

```bash
cd agent && source .venv/bin/activate

# 1) 계획만 보기: 어떤 문항을 몇 번 돌리는지, 캐시 적중이 몇 건인지. LLM 호출 없음
python -m eval.run_eval --label after --plan

# 2) 전체 평가셋을 문항당 3회 실행하고 채점표 출력 (LLM 비용 발생)
python -m eval.run_eval --label after --runs 3

# 3) 전과 후 비교: 이전 측정의 scores.json을 넘긴다 (같은 평가셋 버전만 비교)
#    baseline 파일은 6절 재측정 후에 생긴다. 그 전에는 직접 측정한 이전 결과의 scores.json을 쓴다
python -m eval.run_eval --label after --baseline eval/baselines/v2.1/phase1-end.json
```

| 옵션 | 설명 |
|---|---|
| `--label` | 결과 폴더 이름. 결과는 `agent/eval/results/<버전>/<label>/{문항ID}_r{N}.json`과 `scores.json`에 저장된다(커밋하지 않음) |
| `--runs` | 문항당 반복 횟수. baseline은 3 |
| `--group extract\|dedupe` | 추출 문항만 또는 중복 판단 문항만 |
| `--questions a,b` | 지정한 문항만 |
| `--smoke` | 소수 스모크 문항(ev11, ev16, ev21, ev29)만 1회. 결과는 전체 실행과 섞이지 않게 `<label>-smoke` 폴더에 저장된다 |
| `--plan` | 실행하지 않고 계획만 출력 |
| `--score-only` | 실행 없이 기존 결과 폴더만 채점 |
| `--baseline` | 비교할 이전 `scores.json` |
| `--no-cache`, `--force` | 캐시를 읽지 않음 / 이미 있는 결과 파일도 다시 실행 |
| `--agent-dir` | 다른 체크아웃의 `agent/`에서 `cli.py`를 실행(baseline 재측정용, 6절) |

이미 결과 파일이 있는 회차는 JSON이 온전하고, status가 SUCCEEDED이고, 같은 캐시 키로 만든 것이고(결과 옆의 `.key` 파일), 중복 판단이 건너뛰어지지 않았을 때만 건너뛴다. 아니면 다시 실행하므로, 중간에 끊겨도 같은 명령을 다시 실행하면 이어서 돈다. 실행기 밖에서 만든 결과는 `--score-only`로 채점한다. `--questions`는 쉼표 뒤 공백을 무시한다. 결과 폴더만 따로 채점하려면 `python -m eval.score results/v2.1/after`.

출력되는 지표표의 목표는 `agent/eval/targets.json`에서 읽으며 2절의 결정 기록과 같다. 처리 시간 "지키는 선"은 `--baseline`이 있어야 판정된다.

수동으로 한 문항만 돌리려면 다음과 같이 한다.

```bash
python cli.py eval/dataset/v2.1/inputs/ev11_relative_due_summary.md \
  --members eval/dataset/v2.1/members.json --dry-run --out results/v2.1/manual/ev11_relative_due_r1.json
# ev41 ~ ev46은 --repo meet-to-ticket-eval 추가
```

명단은 반드시 평가셋의 `members.json`을 쓴다. 이름만 보고 로그인명을 추정하지 않는다. 상대 기한("내일" 등)은 각 요약본의 회의 날짜 기준으로 해석한다.

## 5. 평가용 저장소 초기화 절차

중복 판단 문항은 대조 저장소 `MCP-Capstone/meet-to-ticket-eval`(Private)의 시드 이슈 #1 ~ #8이 정해진 상태여야 정답이 맞는다. 목록과 문항별 기대 판정은 `agent/eval/dataset/v2.1/dedupe_seed_issues.md`에 있다.

| 번호 | 상태 | 쓰는 문항 |
|---|---|---|
| #1 | 열림 | ev42 CHANGE |
| #2 | 열림 | ev41 SAME |
| #3 | 닫힘 | ev43 SAME(닫힘) |
| #4 | 열림 | ev45 RELATED |
| #5 | 닫힘 | ev44 CHANGE(닫힘) |
| #6, #7, #8 | 열림 | 방해 이슈 |

규칙

- 시드 이슈를 수정하거나, 닫기/다시 열기 하지 않는다. 바뀌면 정답이 달라진다.
- 평가 실행은 dry-run이라 GitHub에 쓰지 않으므로 보통 초기화가 필요 없다. 실제 발행 테스트를 했거나 시드를 실수로 건드렸을 때만 아래 절차를 따른다.

초기화 절차

1. 현재 상태를 확인한다. 이슈가 정확히 8개(#1 ~ #8)이고 #3, #5만 닫혀 있어야 한다.
   ```bash
   gh issue list -R MCP-Capstone/meet-to-ticket-eval --state all --json number,state,title
   ```
2. 실행으로 새로 생긴 이슈(#9 이상)를 지운다. (저장소 관리자 권한 필요)
   ```bash
   gh issue delete <번호> -R MCP-Capstone/meet-to-ticket-eval --yes
   ```
3. 시드 이슈에 달린 댓글을 지운다. 시드 이슈의 댓글은 0개여야 한다.
   ```bash
   gh api repos/MCP-Capstone/meet-to-ticket-eval/issues/<이슈번호>/comments --jq '.[].id'
   gh api -X DELETE repos/MCP-Capstone/meet-to-ticket-eval/issues/comments/<댓글ID>
   ```
4. 시드 이슈의 제목, 본문, 상태가 `dedupe_seed_issues.md`와 다르면 되돌린다. 되돌릴 수 없으면 저장소를 새로 만들고 시드 8개를 같은 번호 순서로 다시 만든다.
5. 중복 판단 문항을 한 번 돌려 rag-mcp가 색인 8건을 동기화하는지 확인한다.

평가용 저장소 접근 토큰은 `agent/.env` 등 로컬 비밀 설정에만 둔다. 토큰이 만료되기 전에 최종 측정을 끝내거나 토큰을 갱신한다. 만료 시점은 팀 내부 문서로 관리하고 저장소에는 적지 않는다.

## 6. baseline 재측정 절차 (미수행)

v2.1 평가셋 기준 baseline은 아직 재측정하지 않았다. 이 이슈의 코드와 문서는 재측정을 한 명령으로 할 수 있게 해 둔 것이다. 재측정 절차

1. `phase1-end` 태그를 별도 worktree로 받는다. 현재 작업 트리를 건드리지 않는다.
   ```bash
   git worktree add ../phase1-end-checkout phase1-end
   ```
2. 그 체크아웃의 `agent/`에 가상환경을 만들고(같은 의존성), 현재 저장소의 실행기로 측정한다. 모델은 환경 변수로 명시한다. (`OPENAI_MODEL`, `OPENAI_REVIEW_MODEL`, `OPENAI_DEDUPE_MODEL`)
   ```bash
   cd agent
   python -m eval.run_eval --agent-dir ../../phase1-end-checkout/agent --label phase1-end --runs 3
   ```
3. 5절 사전 조건(mcp-server, rag-mcp, 시드 이슈)을 만족한 상태에서 중복 판단 문항까지 포함해 37문항 전체를 돌린다.
4. `results/v2.1/phase1-end/scores.json`을 `agent/eval/baselines/v2.1/phase1-end.json`으로 복사해 커밋하고, 2절 결정 기록의 baseline 열을 v2.1 값으로 갱신한다. 처리 시간 baseline도 이 파일에서 나온다.
5. 이후 Phase 2 코드 측정은 `--baseline eval/baselines/v2.1/phase1-end.json`으로 비교한다.

측정 전 확인

- 명단은 평가셋의 `members.json`이다. 이전 v1 baseline은 `github_name`이 모두 null인 명단이라 직접 비교하지 않는다.
- 검수 Agent를 뺀 뒤의 측정은 "담당자 확인 전 초안"을 기준으로 한다.
- 결과 파일은 반드시 `{문항ID}_r{N}.json` 이름이어야 채점기가 찾는다.

### 참고: v1 baseline (phase1-end, 평가셋 v1 18문항)

v2.1 값과 직접 비교하지 않는다. 이식한 채점기를 기존 v1 baseline 결과 JSON에 다시 돌려 중복 판단 77.8% / 61.1%와 추출 연결 건수가 원래 채점 결과와 같은 것만 확인했다. 이는 채점 집계 방식의 이식 검증일 뿐 baseline을 다시 측정한 것이 아니며, v2.1 baseline은 아직 측정하지 않았다.

| 지표 | 값 |
|---|---|
| 업무 재현율 | 100% (30/30, 기존 3개 회의) |
| 추출 정밀도 | 46.9% (30/64) |
| 기한 정확도 | 0% (0/30) |
| 건수 일치 | 33% (3/9회) |
| 중복 판단 (판정+대상) | 77.8% (14/18) |
| 중복 처리 | 61.1% (11/18) |
| 평균 처리 시간 | 24.9초 (추출) / 13.5초 (중복 판단) |

주요 실패 원인: 월 없는 날짜("20일까지")를 해석하지 못함, 업무 하나를 여러 티켓으로 쪼갬, 이슈 갱신 기능 없음(CHANGE + 열림), RELATED를 못 잡음, 같은 화면이면 CHANGE로 오판.

## 7. CI 비용 통제

LLM 호출은 비용이 들고 결과가 실행마다 달라서 PR마다 전체 평가를 돌리지 않는다.

| 상황 | 하는 일 |
|---|---|
| 모든 PR | 기존 CI(`agent` pytest)가 mock 단위 테스트만 실행한다. 채점기와 실행기도 저장된 결과 JSON/가짜 실행기로 테스트하므로 API 키와 비용이 없다 |
| 프롬프트/모델을 바꾼 PR | 작성자가 `--smoke`(4문항, 1회)로 빠르게 확인하고, 필요하면 `--group extract`나 전체 평가를 수동으로 돌려 전후 표를 PR 본문에 붙인다 |
| 정기 | 전체 평가(37문항, 문항당 3회)는 수동 또는 야간 실행. CI에서 자동으로 돌리지 않는다 |
| 최종 측정 | 중복 판단 문항까지 포함해 3회 실행한 값을 기준으로 삼는다 |

결과 캐시

- 같은 키면 LLM을 다시 부르지 않고 `agent/eval/.cache/`의 저장된 결과를 복사한다. (커밋하지 않음)
- 키는 (입력, 프롬프트 해시, 모델 ID)에 더해 명단, 대조 저장소와 시드 이슈 목록 해시, 결과에 영향을 주는 설정 환경 변수(`RAG_ENABLED`, `DEDUPE_MAX_TOOL_CALLS`, `DEDUPE_TIMEOUT_SECONDS`, 추론 강도, 보완 횟수 등 `run_eval.py`의 `SETTING_ENV`), agent 코드 해시, 회차 번호를 포함한다. 코드가 바뀌면 결과가 달라질 수 있어서(예: 기한 해석 규칙) 코드 해시도 넣었다.
- 프롬프트 파일(`agent/prompts/*.md`)이나 모델 ID(`OPENAI_*`), 입력이 바뀌면 키가 달라져 다시 호출한다. 회차마다 키가 달라 3회 반복은 각각 독립 표본이다.
- 실패한 실행(`status`가 `SUCCEEDED`가 아닌 것)과, 중복 판단 문항에서 판단이 건너뛰어진 실행(`dedupe.skipped`가 있거나 `relation`이 없음: 시간 초과, 오류, rag-mcp 불가 등)은 캐시하지 않는다.
- 새 결과를 강제로 받고 싶으면 `--no-cache`를 쓴다.

## 8. 지표와 설계서의 관계

- 설계서 STEP 02 성공 지표와 검증 결과서에는 2절의 숫자를 그대로 옮긴다. 주 지표는 추출 정밀도(80% 이상)와 중복 판단 정확도(90% 이상)이고, 나머지는 보조/운영/지키는 선이다.
- 업무 재현율은 baseline에서 이미 100%라 주 지표로 쓰지 않고 지키는 선(90% 이상 유지)으로 둔다.
- 기한 정확도는 코드 규칙(`agent/resolve.py`) 개선으로 크게 오르는 지표라, AI 개선을 보여 주는 주 지표 대신 보조 지표로 둔다.
