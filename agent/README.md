# agent 개발환경 가이드

회의록을 분석해 GitHub 이슈를 발행하는 LangGraph 에이전트의 **파이썬 환경 설정** 문서입니다. 이 문서는 `agent/` 폴더만 다룹니다.
`backend/`, `frontend/` 환경과 `mcp-server/` 환경은 범위 밖입니다. 서버 쪽은 `mcp-server/README.md`를 보세요.

이 폴더는 **mcp 1.x** 환경입니다. 확인된 버전(2026-10-20 기준)은 아래와 같습니다.

| 패키지 | 버전 |
|---|---|
| Python | 3.12 |
| langgraph | 1.2.16 |
| langchain-mcp-adapters | 0.3.2 |
| langchain-core | 1.6.12 |
| langchain-openai | 1.7.2 |
| mcp | 1.30.0 |
| fastapi | 0.150.0 |
| uvicorn | 0.54.0 |
| python-dotenv | 1.2.4 |
| pytest, pytest-asyncio (개발용) | 9.1.1, 1.4.0 |

## 1. 이 폴더의 파일

| 파일 | 역할 | Git에 올리나 |
|---|---|---|
| `requirements.in` | 직접 쓰는 패키지와 허용 범위. 사람이 관리한다 | 올림 |
| `requirements.txt` | `.in`을 풀어서 전체 버전을 고정한 파일. uv가 생성한다 | 올림 |
| `requirements-dev.in` | 실행용 `.in`에 테스트 도구(pytest 등)를 더한 파일 | 올림 |
| `requirements-dev.txt` | 위 파일을 고정한 것. 개발자 PC는 이걸로 설치한다 | 올림 |
| `.python-version` | 이 폴더의 Python 버전(3.12). uv가 읽는다 | 올림 |
| `.env.example` | `.env`에 어떤 값이 필요한지 보여 주는 빈 양식 | 올림 |
| `.env` | 실제 OpenAI API 키 등 비밀 값. 직접 만든다 | **안 올림** |
| `.venv/` | 가상환경 폴더. 설치된 패키지가 들어 있다 | **안 올림** |

커밋하지 않을 파일(`.env`, `.venv/` 등)의 규칙은 이 폴더가 아니라 저장소 루트의 `.gitignore`가 담당합니다.

## 2. 왜 가상환경이 2개인가

이 폴더의 `langchain-mcp-adapters`는 `mcp>=1.24.0,<2.0.0`을 요구합니다. 반면 MCP 서버(`mcp-server/`)는 mcp 2.x의 `MCPServer`를 씁니다.
그래서 두 프로그램을 한 환경에 설치할 수 없고, `agent/`와 `mcp-server/`에 가상환경을 하나씩 둡니다.
두 프로그램은 HTTP(Streamable HTTP)로 통신하므로 같은 환경에 있을 필요가 없습니다.
mcp 2.x 서버는 HTTP에서 mcp 1.x 클라이언트도 받아 줍니다. 임시 서버로 직접 확인했습니다(아래 5번).

## 3. in 파일과 txt 파일

npm의 `package.json`과 `package-lock.json` 관계와 같습니다.

| 파이썬 | npm | 설명 |
|---|---|---|
| `requirements.in` | `package.json` | 내가 직접 쓰는 패키지와 허용 범위 |
| `requirements.txt` | `package-lock.json` | 그 결과로 정해진 전체 패키지의 정확한 버전 |

**`.in`은 `.txt`를 만드는 재료이고, 설치는 누구든 항상 `.txt`로 합니다.** 그래야 6명이 같은 버전을 쓰게 됩니다.

| 파일 | 들어 있는 것 | 누가 쓰나 |
|---|---|---|
| `requirements.txt` | 실행에 필요한 패키지만 | 시연, 배포 환경 |
| `requirements-dev.txt` | 실행용 전부와 pytest 등 테스트 도구 | 팀원 개발 PC |

`.txt`는 uv가 만드는 파일이라 손으로 고치지 않습니다.

## 4. 팀원 처음 세팅

**1) uv 설치** (한 번만)

macOS, Linux:

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

Windows (PowerShell):

```powershell
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
```

설치 후 새 터미널을 열어 확인합니다.

```bash
uv --version
```

**2) 저장소 받기**

```bash
git clone git@github.com:MCP-Capstone/meet-to-ticket.git
cd meet-to-ticket/agent
```

**3) 가상환경 만들고 패키지 설치** (`agent/` 폴더 안에서)

```bash
uv venv --python 3.12
uv pip sync requirements-dev.txt
```

`uv pip sync`는 `.txt`에 적힌 그대로 맞춥니다. 목록에 없는 패키지는 지워집니다.

**4) 가상환경 켜기**

macOS, Linux:

```bash
source .venv/bin/activate
```

Windows (PowerShell):

```powershell
.venv\Scripts\Activate.ps1
```

프롬프트 앞에 `(agent)`가 보이면 켜진 것입니다. 끌 때는 `deactivate`입니다.

**5) `.env` 만들기**

macOS, Linux:

```bash
cp .env.example .env
```

Windows (PowerShell):

```powershell
Copy-Item .env.example .env
```

`.env`를 열어 `OPENAI_API_KEY`를 채웁니다. `MCP_SERVER_URL`은 기본값(`http://127.0.0.1:8000/mcp`)이 서버 기본 주소와 같습니다. 이 파일은 절대 커밋하지 않습니다.

**6) 설치 확인**

```bash
python --version
python -c "from importlib.metadata import version; print('mcp', version('mcp'), '| langgraph', version('langgraph'))"
python -c "from langchain_mcp_adapters.client import MultiServerMCPClient; import langgraph, langchain_openai, dotenv, fastapi; print('import OK')"
uv pip check
```

기대 결과는 `Python 3.12.x`, `mcp 1.x.x`, `import OK`, `All installed packages are compatible`입니다. mcp가 2.x로 나오면 잘못된 환경입니다.

## 5. 실행 방법

에이전트는 MCP 서버가 **먼저 떠 있어야** 도구를 불러올 수 있습니다. 서버는 `mcp-server/` 폴더에서 별도 터미널로 띄웁니다(`mcp-server/README.md` 5번 참고).

**에이전트가 붙을 주소: `http://127.0.0.1:8000/mcp`**

- mcp 2.3.0 서버의 기본 주소이며, 임시 서버를 실제로 띄워 확인했습니다.
- 그 임시 서버에 이 환경(mcp 1.30.0)의 `MultiServerMCPClient`가 `streamable_http`로 접속해 도구 목록 조회에 성공했습니다.
- 실제 도구 4개(`get_repo_context`, `search_issues`, `create_issue`, `get_issue_status`)가 보이는지는 `server.py`가 아직 없어서 **확인하지 못했습니다.**

접속 코드는 아래 형태입니다. 주소는 `.env`의 `MCP_SERVER_URL`에서 읽습니다.

```python
import os
from dotenv import load_dotenv
from langchain_mcp_adapters.client import MultiServerMCPClient

load_dotenv()
client = MultiServerMCPClient(
    {"github": {"transport": "streamable_http", "url": os.environ["MCP_SERVER_URL"]}}
)
tools = await client.get_tools()
```

FastAPI 서버 실행은 아래와 같습니다.

> **주의:** 에이전트의 진입점 파일이 아직 없어서 `main:app`은 **예시**입니다. 진입점이 정해지면 이 문서를 고치세요.
> MCP 서버가 기본으로 8000번 포트를 쓰므로, 에이전트는 겹치지 않게 다른 포트(예시는 8001)로 띄웁니다.

```bash
uvicorn main:app --reload --port 8001
```

테스트 실행은 아래와 같습니다.

```bash
pytest
```

## 6. 패키지 추가 규칙

패키지 추가와 compile은 **환경 담당 1명만** 합니다. `.txt`가 수백 줄이라 여러 명이 동시에 바꾸면 Git에서 충돌하기 쉽습니다. 필요한 패키지가 있으면 담당자에게 요청하세요.

담당자는 이 순서를 지킵니다.

1. 이 폴더의 `requirements.in`만 수정한다. 테스트 도구라면 `requirements-dev.in`을 수정한다.
2. 아래 두 줄을 다시 실행한다.

```bash
uv pip compile requirements.in --universal -o requirements.txt
uv pip compile requirements-dev.in -c requirements.txt --universal -o requirements-dev.txt
```

3. 내 환경에 반영한다.

```bash
uv pip sync requirements-dev.txt
```

4. `.in`과 `.txt`를 **모두** 커밋하고 팀에 공지한다.
5. 공지를 본 팀원은 `git pull` 뒤에 `uv pip sync requirements-dev.txt`를 다시 실행한다.

`--universal`은 macOS, Linux, Windows 팀원이 같은 `.txt`를 쓰도록 OS별 조건(예: Windows 전용 패키지)을 파일에 함께 기록하게 합니다. 빼먹으면 만든 사람의 OS에서만 맞는 `.txt`가 됩니다.

LLM 제공사를 바꾸면(예: OpenAI에서 다른 회사로) `requirements.in`의 `langchain-openai` 줄만 해당 패키지로 바꾸고 위 순서를 따릅니다.

## 7. GitHub에 올리는 것과 올리지 않는 것

| 구분 | 대상 | 이유 |
|---|---|---|
| 올림 | 소스 코드 | 공유 대상 |
| 올림 | `requirements*.in`, `requirements*.txt` | 모두가 같은 버전을 쓰기 위해 |
| 올림 | `.python-version` | Python 버전 공유 |
| 올림 | `.env.example` | 필요한 값의 목록만 있고 비밀은 없다 |
| 안 올림 | `.env` | OpenAI API 키 같은 비밀 값이 들어 있다. 한 번 올라가면 기록에 남는다 |
| 안 올림 | `.venv/` | 용량이 크고 컴퓨터마다 다르다. `.txt`로 언제든 다시 만들 수 있다 |

안 올리는 파일은 저장소 루트의 `.gitignore`가 자동으로 걸러 줍니다. 이 폴더에는 별도의 `.gitignore`를 두지 않습니다.

## 8. 자주 나는 문제

**mcp 버전 충돌 (서버 패키지를 이 환경에 설치했을 때)**
mcp 2.x가 필요한 서버 환경의 패키지를 이 폴더 환경에 설치하면 `mcp<2` 요구와 부딪혀 설치가 실패하거나 한쪽이 망가집니다. 서버 패키지는 `mcp-server/` 환경에서만 설치합니다. 이미 설치했다면 환경을 지우고 다시 만듭니다.

macOS, Linux:

```bash
deactivate
rm -rf .venv
uv venv --python 3.12
uv pip sync requirements-dev.txt
```

Windows (PowerShell):

```powershell
deactivate
Remove-Item -Recurse -Force .venv
uv venv --python 3.12
uv pip sync requirements-dev.txt
```

**다른 Python 버전으로 만든 경우**
`python --version`이 3.12가 아니면 위의 "환경을 지우고 다시 만들기"를 하고, 반드시 `--python 3.12`를 붙입니다.

**가상환경을 안 켜고 실행한 경우**
`ModuleNotFoundError: No module named 'langgraph'` 같은 오류가 납니다. 프롬프트 앞에 `(agent)`가 있는지 확인하고 4번의 활성화 명령을 실행합니다. 켜지 않고 바로 실행하려면 환경의 파이썬을 직접 지정할 수도 있습니다.

```bash
.venv/bin/python -m pytest
```

Windows에서는 `.venv\Scripts\python.exe -m pytest`입니다.

**다른 폴더의 가상환경을 켠 채 실행한 경우**
`mcp-server/` 환경을 켠 채 에이전트를 실행하면 `langgraph`를 찾지 못하는 오류가 납니다. 프롬프트가 `(mcp-server)`로 되어 있지 않은지 보고, 끈 뒤 이 폴더의 환경을 켭니다. 지금 어떤 환경인지는 아래로 확인합니다.

```bash
python -c "import sys; print(sys.prefix)"
```

경로 끝이 `agent/.venv`여야 합니다.

**서버에 연결이 안 될 때**
`Connection refused` 계열 오류는 MCP 서버가 안 떠 있거나 주소가 다른 경우입니다. 서버 터미널의 시작 로그에서 주소를 확인하고 `.env`의 `MCP_SERVER_URL`과 같은지 봅니다. 에이전트를 8000번 포트로 띄워서 서버와 겹친 경우도 있습니다.

**Windows PowerShell에서 activate가 막힐 때**
"이 시스템에서 스크립트를 실행할 수 없으므로..."라는 오류가 나면 실행 정책 때문입니다. 현재 사용자에게만 허용합니다.

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

**`.env`를 커밋해 버렸을 때**
1. **먼저** OpenAI API 키를 즉시 폐기(revoke)하고 새로 발급합니다. 기록에서 파일을 지우는 것보다 이게 우선입니다. 한 번 올라간 값은 이미 노출된 것으로 봅니다.
2. 새 키를 `.env`에 넣습니다.
3. 팀에 알립니다. 저장소 기록에서 파일을 지우는 작업은 팀과 상의해서 합니다.

## 9. 하지 말 것

- `requirements.txt`, `requirements-dev.txt`를 손으로 수정하지 않는다. 고치려면 `.in`을 수정하고 compile한다.
- `pip install`이나 `uv pip install`로 패키지를 하나씩 설치하고 그대로 두지 않는다. `.txt`에 없으면 다음 `uv pip sync` 때 지워지고, 다른 팀원 환경에는 아예 없다.
- `.venv/`를 커밋하지 않는다.
- `agent/`와 `mcp-server/` 환경을 하나로 합치지 않는다. mcp 버전이 충돌한다.
- `.env`와 실제 API 키를 커밋하거나 메신저, 이슈에 붙여넣지 않는다.
- 패키지 추가와 compile을 환경 담당이 아닌 사람이 임의로 하지 않는다.

## 10. 1차 구현 실행

흐름: 할 일 추출·담당자·기한 정리 → 검수(보완 최대 2회) → 최종 발행 목록 → MCP로 새 이슈 생성 → 항목별 결과 보고

### 파일

| 파일 | 역할 |
|---|---|
| `main.py` | FastAPI (`POST /runs`, `GET /runs/{task_id}` 진행 단계·결과, `GET /health`) |
| `cli.py` | 백엔드 없이 회의 요약 파일로 바로 실행 |
| `runner.py` | 요청 1건 실행 (MCP 연결, 그래프 실행) |
| `graph.py` | LangGraph 그래프 (prepare → draft → review ⇄ draft → publish → finish) |
| `nodes.py` | 노드 구현, 검수 판정 규칙 |
| `parsing.py` | 회의 요약 마크다운 해석 (액션 아이템 표 등) |
| `resolve.py` | 담당자 매핑, 기한 계산, 예상 처리 분류 |
| `llm.py` | OpenAI 호출 (모델·추론 설정, 재시도) |
| `prompts/*.md`, `prompts.py` | 프롬프트 본문(md)과 읽기·버전 계산 |
| `publisher.py` | MCP 도구 호출, 이슈 본문 조립 |
| `reporter.py` | 백엔드 진행·결과 보고 |
| `mcp_check.py` | MCP 서버 연결 확인 (이슈 목록 읽기, `--write`면 테스트 이슈 생성, `--rag`면 rag-mcp와 판단용 도구 확인) |
| `issue_tools.py` | 중복 판단용 기존 이슈 조회 도구 (모델용 3개, 코드용 색인 갱신·색인) |
| `samples/` | 회의 요약 예시 3종, 팀원 명단, 요청 목데이터 |

### 모델

| 작업 | 기본 모델 | 추론 | temperature | 설정 |
|---|---|---|---|---|
| 액션 아이템 추출, 이슈 초안 | `gpt-5.4-nano` | 끔 (`none`) | 0 | `OPENAI_MODEL`, `OPENAI_REASONING_EFFORT` |
| 검수 | `gpt-5.4-mini` | 모델 기본값 | 1 | `OPENAI_REVIEW_MODEL`, `OPENAI_REVIEW_REASONING_EFFORT` |

- 초안은 같은 회의록이면 같은 결과가 나오도록 추론을 끄고 temperature 0으로 호출한다.
- 검수는 판단이 중요해 추론을 켠다. gpt-5 계열은 추론을 켜면 temperature 0을 받지 않아 기본값 1로 호출된다(langchain-openai가 자동으로 뺀다).
- 모델이 temperature나 추론 설정을 지원하지 않는다는 오류를 돌려주면, 그 설정만 빼고 바로 다시 호출한다. 이 재호출은 재시도 횟수에 넣지 않는다.

### 프롬프트

| 파일 | 쓰는 곳 | 채워 넣는 값 |
|---|---|---|
| `prompts/rows_system.md` | 표가 없을 때 본문에서 할 일 추출 | - |
| `prompts/draft_system.md`, `draft_user.md` | 이슈 초안 작성 | `{summary}`, `{items}` |
| `prompts/review_system.md`, `review_user.md` | 검수 | `{summary}`, `{source_note}`, `{excluded}`, `{drafts}` |

검수 프롬프트의 2·3·4·7·8번은 아래 "검수 판정" 표의 번호다. LLM이 판정하는 항목만 넣었고, 1·5·6·9번은 코드가 확인한다.

프롬프트를 고칠 때:

1. `prompts/*.md`만 고친다. `prompts.py`와 `llm.py`는 건드리지 않는다.
2. `pytest`로 파일과 자리표시자가 맞는지 확인한다.
3. 샘플 3종을 `--dry-run`으로 다시 돌려 고치기 전 결과와 비교한다 (할 일 수, 승인·확인 필요 수, 보완 횟수).
4. 프롬프트 버전(`PROMPT_VERSION`, 다섯 파일 내용의 해시 8자리)이 바뀐다. 실행 로그 `LLM 준비: … 프롬프트 버전 …`으로 어떤 프롬프트로 만든 결과인지 구분한다.

### Harness 명세

모델이 제안만 하고 실행·한도·기록·복구는 Agent 코드가 맡는다. 요소별 구현과 값은 아래와 같다. 값을 바꾸면 이 표도 고친다.

| 요소 | 구현 | 값·설정 |
|---|---|---|
| Instructions | 프롬프트 md 분리, 구조화 출력, 작업별 모델·추론 설정 | `prompts/*.md`, `PROMPT_VERSION`, 위 "모델" 표 |
| Tools | MCP 서버 2개(mcp-server, rag-mcp) 연결, 모델용 조회 도구 3개(저장소 고정, 결과 길이 제한, 오류는 `[코드] 메시지`로 모델에게 전달), 쓰기 도구는 코드만 호출 | `MCP_SERVER_URL`, `RAG_MCP_URL`, `TOOL_RESULT_MAX_CHARS` |
| Agent Loop | LangGraph StateGraph (prepare → draft ⇄ review → publish → finish), 작업 마감, LLM 호출 시간 한도 | 보완 `MAX_REVISIONS`=2, recursion 50, `TASK_DEADLINE_SECONDS`=600, `LLM_TIMEOUT_SECONDS`=120 |
| Context | 원문과 코드가 정리한 표만 전달, 검수에 제외·보류 목록 주입, 원문 길이 한도 | `MAX_SOURCE_CHARS`=40000, `DRAFT_BATCH_SIZE`·`REVIEW_BATCH_SIZE`=10 |
| State·Memory | 노드가 끝날 때마다 상태 저장(checkpointer, thread id = taskId), 발행한 taskId 재요청은 저장된 결과로 응답, 실행 중 같은 taskId는 409, `GET /runs/{id}/history` | 메모리 저장 (재시작 시 사라짐) |
| Guardrails | 자동 검수(아래 "검수 판정"), 허용 저장소, APPROVED만 발행 | `CONDITIONAL_AS_NEEDS_CHECK`, `FALLBACK_PUBLISH`, `DRY_RUN` |
| Recovery | LLM 오류를 종류별로 처리(아래 "LLM 오류 처리"), 대체 모델, 발행 타임아웃은 UNKNOWN, 치명 오류 시 남은 발행 중단, 검수 보완 한도 초과 시 기본 초안 | `LLM_RETRIES`=2, `OPENAI_FALLBACK_MODEL` |
| Observability | LangSmith trace(작업 1건 = trace 1개), 결과의 `traceId`·`metrics`, `GET /runs/{id}` 진행 단계, 항목별 검수 이력 JSON, task·trace id가 붙은 로그 | `LANGSMITH_TRACING`, `LANGSMITH_PROJECT`, `BACKEND_BASE_URL` |

### 실행 한도

| 한도 | 기본값 | 넘으면 |
|---|---|---|
| `LLM_TIMEOUT_SECONDS` | 120초 | 그 호출을 실패로 보고 재시도 (`LLM_RETRIES`회까지) |
| `TASK_DEADLINE_SECONDS` | 600초 | 초안·검수 노드에 들어가기 전에 `TASK_TIMEOUT`으로 끝냄. 발행은 하지 않음 |
| `MAX_SOURCE_CHARS` | 40,000자 | LLM을 부르지 않고 `INPUT_TOO_LONG`으로 끝냄 |

- 0 이하로 두면 마감·길이 한도를 끈다.
- 평가 자료의 권장 작업 시간은 120초다. 60분 회의(24건)는 초안·검수를 10건씩 3번 나눠 호출하고, 추론 모델(gpt-5.4-mini) 응답이 느릴 수 있어 600초로 두었다. 실제 실행 시간을 보고 줄인다.
- 마감은 "다음 단계에 들어가지 않는다"는 기준이다. 이미 시작한 노드와 발행은 끝까지 한다(발행을 중간에 끊으면 만들어진 이슈가 결과에서 빠질 수 있다). 그래서 끝나는 시각은 마감보다 늦을 수 있다.
  - 진행 중인 노드 1개: 배치 1번에 최대 약 6분 (120초 × 3회 + 재시도 대기 4.5초)
  - 발행: 이슈마다 mcp-server의 GitHub 호출 한도 20초
- LLM 재시도는 Agent(`llm.py`)에서만 한다. OpenAI 클라이언트의 자체 재시도는 꺼서 재시도가 겹치지 않게 했다(전에는 최대 9번 호출될 수 있었다).

### LLM 오류 처리

| 오류 | 처리 |
|---|---|
| 인증 (401·403, 잘못된 키) | 즉시 중단, `LLM_ERROR` |
| 요청 오류 (400·404·422, 예: 입력이 너무 김) | 다시 보내도 같아서 재시도하지 않고 중단, `LLM_ERROR` |
| 모델이 temperature·추론 설정을 거부 (400) | 그 설정만 빼고 바로 다시 호출 (재시도 횟수에 넣지 않음) |
| 일시적 오류 (429·5xx·타임아웃·연결 오류·응답 형식 오류) | `LLM_RETRIES`회 재시도 (1.5초, 3초 대기) → 그래도 실패하면 `OPENAI_FALLBACK_MODEL`로 1회 → 그래도 실패하면 `LLM_ERROR` |

- 대체 모델은 비우면 쓰지 않는다. 인증·요청 오류에는 쓰지 않는다(모델을 바꿔도 해결되지 않음).
- 이 오류 재시도는 검수 보완 루프(`review → draft`, `MAX_REVISIONS`)와 다르다. 보완 루프는 호출은 성공했지만 검수가 지적을 낸 경우다.
- 재시도·대체 모델 사용·오류 종류별 횟수가 결과의 `metrics`에 남는다.

### 1) 추출·검수만 확인 (OpenAI 키만 필요)

```bash
python cli.py samples/10min_1_summary.md --dry-run
```

### 2) 실제 GitHub 발행까지

1. `samples/members.json`의 `github_name`에 팀원 GitHub 아이디를 채운다 (비우면 담당자 없이 발행)
2. 다른 터미널에서 MCP 서버 실행: `cd ../mcp-server && python server.py`
3. 실행:

```bash
python cli.py samples/10min_1_summary.md --owner MCP-Capstone --repo mcp-capstone --out out/result.json
```

### 3) API 서버

```bash
uvicorn main:app --port 8001
curl -X POST "http://127.0.0.1:8001/runs?wait=true" -H "Content-Type: application/json" -d @request.json
```

요청 형식은 백엔드와 같은 camelCase다. 목데이터가 `samples/requests/`에 있다.

| 파일 | 내용 |
|---|---|
| `csv_check.json` | 백엔드 예시 그대로 (액션 아이템 표 없는 짧은 회의록) |
| `10min_1.json`, `30min_1.json`, `60min_1.json` | 회의 요약 예시 3종을 같은 형식으로 감싼 것 |

```bash
curl -X POST "http://127.0.0.1:8001/runs?wait=true" -H "Content-Type: application/json" -d @samples/requests/csv_check.json
python cli.py --request samples/requests/csv_check.json --dry-run   # API 없이 같은 입력으로 실행
```

요청 주요 필드: `taskId`, `meeting.{meetingId, title, meetingDate, timezone, participantIds, originalFilename, originalContent}`, `project.{githubOwner, githubRepo}`, `projectMembers[].{projectMemberId, userId, name, githubName}`

결과(응답·백엔드 보고)도 camelCase: `taskId`, `status`, `stage`, `errorCode`, `errorMessage`, `summary`, `items[].{taskItemId, workKey, title, contentJson, action, reason, reviewStatus, publishStatus, githubIssueNumber, githubIssueUrl, errorMessage}`, `traceId`, `metrics`

- `publishStatus`: `SUCCEEDED`(발행), `FAILED`(실패), `UNKNOWN`(응답 불명), `BLOCKED`(검수 미통과로 발행 안 함), `SKIPPED`(DRY_RUN이라 발행 생략, 이슈 번호 없음)

- `traceId`: LangSmith run id. 성공·실패 모두 들어 있어 실패한 작업의 trace를 바로 찾을 수 있다.
- `metrics`: `durationMs`, `promptVersion`, `model`, `reviewModel`, `tracing`(trace 수집 여부), `llmCalls`, `llmRetries`, `fallbackUsed`(대체 모델 사용 수), `errors`(오류 종류별 수: transient·request·auth), `droppedSettings`(모델이 거부해 뺀 설정)

`?wait=true` 없이 보내면 202로 접수하고 백그라운드에서 처리한다. 처리 중에 `GET /runs/{taskId}`를 부르면 지금 단계를 돌려준다.

```json
{"taskId": "…", "status": "RUNNING", "stage": "REVIEWING"}
```

단계는 `EXTRACTING`(할 일 추출) → `NORMALIZING`(담당자·기한 정리, 초안 작성) → `REVIEWING`(검수, 보완 포함) → `PUBLISHING`(발행) 순서다. 끝나면 결과 전체가 나온다. `BACKEND_BASE_URL`이 있으면 같은 단계와 결과를 백엔드에도 보고한다(`/internal/tasks/{id}/progress`, `/result`).

### 같은 taskId를 다시 보낼 때

노드가 끝날 때마다 상태를 taskId 단위로 저장한다(LangGraph checkpointer). 그래서 같은 taskId가 다시 오면 이렇게 처리한다.

| 이전 작업 | 처리 |
|---|---|
| 처리 중 | 409 (백엔드는 조회로 넘어감) |
| 이슈를 발행했거나 발행 여부가 불명(UNKNOWN) | 다시 실행하지 않는다. 202로 받고 `GET /runs/{taskId}`에 저장된 결과를 돌려준다 (중복 이슈 방지) |
| 발행 전에 끝남 (LLM 오류, 발행 0건, `--dry-run`) | 이전 기록을 지우고 다시 실행한다 |

`GET /runs/{taskId}/history`는 노드별 기록을 시간 순서로 돌려준다 (노드, 다음 노드, 단계, 검수 상태별 티켓 수, 발행 수, 저장 시각). 검수 보완이 있었다면 `draft → review`가 반복돼 보인다.

저장소는 프로세스 메모리(`InMemorySaver`)라 Agent를 재시작하면 기록과 중복 방지가 사라진다. 재시작 뒤에도 유지하려면 Postgres checkpointer(새 패키지 필요)로 바꿔야 한다(2차 과제).

### LangSmith trace

`agent/.env`에 `LANGSMITH_TRACING=true`, `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT`(기본 `meet-to-ticket`)를 넣으면 작업 1건이 trace 하나로 수집된다.

- 맨 위 run `meet-to-ticket`의 id가 결과의 `traceId`다. LangSmith에서 이 id로 찾는다.
- 그 아래 `agent-graph` → 노드(prepare, draft, review, publish, finish) → LLM 호출이 붙고, Token·지연이 함께 기록된다.
- metadata에 `task_id`, `meeting_id`, `prompt_version`, `model`, `review_model`이 있어 같은 회의나 같은 프롬프트 버전끼리 모아 볼 수 있다.
- MCP 서버 연결 실패처럼 그래프 전에 끝난 작업도 `meet-to-ticket` run 하나로 남는다.
- 켜지 않으면 아무것도 보내지 않고, `traceId`는 그대로 만들어져 로그·결과에 남는다. 테스트는 항상 끈 상태로 돈다.

### 4) MCP 연결 확인

```bash
python mcp_check.py --owner MCP-Capstone --repo mcp-capstone           # 이슈 목록 읽기
python mcp_check.py --owner MCP-Capstone --repo mcp-capstone --write   # 테스트 이슈 1개 생성
python mcp_check.py --owner MCP-Capstone --repo mcp-capstone --rag "CSV 빈 결과 오류"   # rag-mcp 확인
```

### 중복 판단용 도구 (rag-mcp)

`RAG_ENABLED=true`면 mcp-server(8000)와 함께 rag-mcp(8002)에도 연결한다. 도구 명세는 이슈 #83에 있다.

| 도구 | 서버 | 누가 부르나 | 하는 일 |
|---|---|---|---|
| `search_similar_issues` | rag-mcp | 모델 | 의미가 비슷한 기존 이슈 찾기 (k 최대 10) |
| `search_issues` | mcp-server | 모델 | 이슈 키(예: AUTH-112)나 정확한 단어로 찾기 |
| `read_issue` | rag-mcp Resource `issue://{owner}/{repo}/{number}` | 모델 | 후보 이슈 원문 읽기 (모델은 Tool만 부를 수 있어 Agent가 Resource를 도구로 감쌈) |
| `sync_issues` | rag-mcp | 코드 | 실행마다 1회, 바뀐 이슈만 다시 색인 (`RAG_SYNC_TIMEOUT_SECONDS` 안에 안 끝나면 건너뜀) |
| `index_issue` | rag-mcp | 코드 | 새로 발행한 이슈를 색인에 추가 |
| `create_issue` | mcp-server | 코드 | 이슈 발행 (모델에게 주지 않음) |

- 저장소(owner/repo)는 백엔드 요청 값으로 고정한다. 모델용 도구에는 저장소 인자가 없어서 모델이 바꿀 수 없다.
- 도구 결과는 `TOOL_RESULT_MAX_CHARS`(2000자)로 자르고, 오류는 예외 대신 `[코드] 메시지`로 모델에게 돌려준다. `INDEX_EMPTY`(색인 없음)는 "후보 없음"으로 준다.
- rag-mcp에 연결하지 못하면 경고만 남기고 중복 확인 없이 기존처럼 발행한다. 결과 `metrics.rag.status`는 `disabled` / `unavailable` / `ready`이고, `metrics.rag.sync`에 색인 갱신 결과가 남는다.
- 중복 판단 단계(모델이 위 도구를 골라 호출)는 #93, 판단 결과에 따른 발행은 #94에서 추가한다.

### 검수 판정

| # | 점검 | 판정 | 실패 시 |
|---|---|---|---|
| 1 | 근거 액션 아이템 존재 | 코드 | 반려 (유일한 반려 사유) |
| 2 | 제외·보류 항목이 아님 | LLM (+코드 검증) | 보완 |
| 3 | 할 일이 맞음 | LLM (+코드 검증) | 보완 |
| 4 | 원문에 없는 내용 없음 | LLM (+코드 검증) | 보완 |
| 5·6 | 담당자·기한 | 코드가 원문에서 직접 계산하므로 LLM이 바꾸지 않음 | - |
| 7 | 제목 명확 | LLM | 메모만 남김 (발행을 막지 않음) |
| 8 | 확인 가능한 완료 조건 | LLM | 메모만 남김 (발행을 막지 않음) |
| 9 | 본문 필수 칸 (제목·설명·완료 조건) | 코드 | 보완 |

- 검수 LLM 한 번의 판단으로는 반려하지 않는다. LLM 지적은 코드로 다시 확인해 확실한 것만 인정한다.
  - 지적 문구(`quote`)가 초안에 실제로 있어야 한다
  - 4번은 그 문구가 원문에 있으면 무시한다
  - 2번은 회의에 제외·보류 항목이 없으면 무시한다
- 근거 인용 중 원문에 없는 것은 초안 단계에서 뺀다 (모두 없으면 액션 아이템 문구로 대체).
- 보완은 최대 `MAX_REVISIONS`(기본 2)회. 넘어도 지적이 남으면
  - 액션 아이템 표에서 온 티켓 → 원문 기반 기본 초안으로 승인 (`FALLBACK_PUBLISH=true`, 본문에 검수 메모)
  - 표 없이 LLM이 뽑은 티켓에 2·3번 지적이 남음 → `NEEDS_CHECK`
- 회의록에 "발행 보류"면 `NEEDS_CHECK`, "조건부 기존 이슈 갱신"은 `CONDITIONAL_AS_NEEDS_CHECK=true`(기본)일 때 `NEEDS_CHECK`
- `APPROVED`만 발행 단계로 넘어간다.

### 1차 한계
- 기존 GitHub 이슈를 조회하지 않는다. 같은 taskId는 다시 발행하지 않지만(위 표), 같은 회의를 새 taskId로 다시 보내면 이슈가 다시 생성된다.
- "기존 이슈 갱신(AUTH-112)" 같은 표기는 새 이슈 본문에 관련 키로만 남긴다.

### 테스트

```bash
pytest
```

가짜 LLM·가짜 도구를 쓰므로 OpenAI와 GitHub를 호출하지 않는다.

## Spring·Agent Swagger 명세 (#58)

[공개·내부 API 구분, Swagger 접속 및 전체 흐름 테스트 안내](../docs/api/README.md)를 참고한다.
