# agent 개발환경 가이드

회의록을 분석해 GitHub 이슈를 발행하는 LangGraph 에이전트의 **파이썬 환경 설정** 문서입니다. 이 문서는 `agent/` 폴더만 다룹니다.
`backend/`, `frontend/` 환경과 `business-mcp/` 환경은 범위 밖입니다. 서버 쪽은 `business-mcp/README.md`를 보세요.

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

이 폴더의 `langchain-mcp-adapters`는 `mcp>=1.24.0,<2.0.0`을 요구합니다. 반면 MCP 서버(`business-mcp/`)는 mcp 2.x의 `MCPServer`를 씁니다.
그래서 두 프로그램을 한 환경에 설치할 수 없고, `agent/`와 `business-mcp/`에 가상환경을 하나씩 둡니다.
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

에이전트는 MCP 서버가 **먼저 떠 있어야** 도구를 불러올 수 있습니다. 서버는 `business-mcp/` 폴더에서 별도 터미널로 띄웁니다(`business-mcp/README.md` 5번 참고).

**에이전트가 붙을 주소: `http://127.0.0.1:8000/mcp`**

- mcp 2.3.0 서버의 기본 주소이며, 임시 서버를 실제로 띄워 확인했습니다.
- 그 임시 서버에 이 환경(mcp 1.30.0)의 `MultiServerMCPClient`가 `streamable_http`로 접속해 도구 목록 조회에 성공했습니다.
- 실제 도구는 `business-mcp/server.py`가 제공하는 3개(`create_issue`, `list_issues`, `search_issues`)입니다.

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
mcp 2.x가 필요한 서버 환경의 패키지를 이 폴더 환경에 설치하면 `mcp<2` 요구와 부딪혀 설치가 실패하거나 한쪽이 망가집니다. 서버 패키지는 `business-mcp/` 환경에서만 설치합니다. 이미 설치했다면 환경을 지우고 다시 만듭니다.

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
`business-mcp/` 환경을 켠 채 에이전트를 실행하면 `langgraph`를 찾지 못하는 오류가 납니다. 프롬프트가 `(business-mcp)`로 되어 있지 않은지 보고, 끈 뒤 이 폴더의 환경을 켭니다. 지금 어떤 환경인지는 아래로 확인합니다.

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
- `agent/`와 `business-mcp/` 환경을 하나로 합치지 않는다. mcp 버전이 충돌한다.
- `.env`와 실제 API 키를 커밋하거나 메신저, 이슈에 붙여넣지 않는다.
- 패키지 추가와 compile을 환경 담당이 아닌 사람이 임의로 하지 않는다.

## 10. 1차 구현 실행

흐름: 할 일 추출·담당자·기한 정리 → 검수(보완 최대 2회) → 최종 발행 목록 → MCP로 새 이슈 생성 → 항목별 결과 보고

### 파일

| 파일 | 역할 |
|---|---|
| `main.py` | FastAPI (`POST /runs`, 확인된 티켓용 `POST /publish`, `GET /runs/{task_id}` 진행 단계·결과, `POST /projects/check-members`, `GET /health`) |
| `permissions.py` | 프로젝트 저장 시 멤버별 저장소 권한 확인. LLM 없이 MCP `check_member_permissions`를 호출 |
| `cli.py` | 백엔드 없이 회의 요약 파일로 바로 실행 |
| `runner.py` | 요청 1건 실행 (MCP 연결, 그래프 실행) |
| `graph.py` | LangGraph 그래프 (prepare → draft → review ⇄ draft → dedupe → finish). `PUBLISH_ON_RUN`이면 dedupe 뒤에 publish |
| `nodes.py` | 노드 구현, 검수 판정 규칙 |
| `parsing.py` | 회의 요약 마크다운 해석 (액션 아이템 표 등) |
| `extraction.py` | 추출 결과 조립 (표·LLM 추출을 같은 형태로, 근거 인용 원문 대조) |
| `resolve.py` | 담당자 매핑, 기한 계산, 예상 처리 분류 |
| `llm.py` | OpenAI 호출 (모델·추론 설정, 재시도) |
| `prompts/*.md`, `prompts.py` | 프롬프트 본문(md)과 읽기·버전 계산 |
| `publisher.py` | MCP 도구 호출, 이슈 본문 조립 |
| `reporter.py` | 백엔드 진행·결과 보고 |
| `mcp_check.py` | MCP 서버 연결 확인 (이슈 목록 읽기, `--write`면 테스트 이슈 생성, `--rag`면 rag-mcp와 판단용 도구 확인) |
| `issue_tools.py` | 중복 판단용 기존 이슈 조회 도구 (모델용 3개, 코드용 색인 갱신·색인) |
| `dedupe.py` | 중복 판단 루프 (모델이 조회 도구를 골라 부르고 관계를 제출) |
| `samples/` | 회의 요약 예시 3종, 팀원 명단, 요청 목데이터 |

### 모델

| 작업 | 기본 모델 | 추론 | temperature | 설정 |
|---|---|---|---|---|
| 회의록 추출, 이슈 초안 | `gpt-5.4-nano` | 끔 (`none`) | 0 | `OPENAI_MODEL`, `OPENAI_REASONING_EFFORT` |
| 검수 | `gpt-5.4-mini` | 모델 기본값 | 1 | `OPENAI_REVIEW_MODEL`, `OPENAI_REVIEW_REASONING_EFFORT` |
| 중복 판단 (도구 선택) | `gpt-5.4-mini` | 검수와 같음 | 1 | `OPENAI_DEDUPE_MODEL` |

- 초안은 같은 회의록이면 같은 결과가 나오도록 추론을 끄고 temperature 0으로 호출한다.
- 검수는 판단이 중요해 추론을 켠다. gpt-5 계열은 추론을 켜면 temperature 0을 받지 않아 기본값 1로 호출된다(langchain-openai가 자동으로 뺀다).
- 모델이 temperature나 추론 설정을 지원하지 않는다는 오류를 돌려주면, 그 설정만 빼고 바로 다시 호출한다. 이 재호출은 재시도 횟수에 넣지 않는다.

### 프롬프트

| 파일 | 쓰는 곳 | 채워 넣는 값 |
|---|---|---|
| `prompts/extract_system.md` | 표가 없을 때 본문에서 할 일·결정·미결 질문·제외 항목 추출 | - |
| `prompts/draft_system.md`, `draft_user.md` | 이슈 초안 작성 | `{summary}`, `{decisions}`, `{open_questions}`, `{labels}`, `{items}` |
| `prompts/review_system.md`, `review_user.md` | 검수 | `{summary}`, `{source_note}`, `{excluded}`, `{drafts}` |
| `prompts/dedupe_system.md`, `dedupe_user.md` | 중복 판단 (도구 고르기, 관계 판단) | `{title}`, `{action}`, `{evidence}`, `{keys}`, `{expected}` |

검수 프롬프트의 2·3·4·7·8번은 아래 "검수 판정" 표의 번호다. LLM이 판정하는 항목만 넣었고, 1·5·6·9번은 코드가 확인한다.

프롬프트를 고칠 때:

1. `prompts/*.md`만 고친다. `prompts.py`와 `llm.py`는 건드리지 않는다.
2. `pytest`로 파일과 자리표시자가 맞는지 확인한다.
3. 샘플 3종을 `--dry-run`으로 다시 돌려 고치기 전 결과와 비교한다 (할 일 수, 승인·확인 필요 수, 보완 횟수).
4. 프롬프트 버전(`PROMPT_VERSION`, 프롬프트 파일 내용의 해시 8자리)이 바뀐다. 실행 로그 `LLM 준비: … 프롬프트 버전 …`으로 어떤 프롬프트로 만든 결과인지 구분한다.

### 추출 결과 스키마

회의록에서 뽑은 할 일, 결정, 미결 질문을 `schemas.Extraction` 하나로 모은다 (기획_v16 REQ-008). 액션 아이템 표가 있으면 표에서 읽고(`source: "table"`), 없으면 LLM이 뽑은 행으로 만든다(`source: "llm"`). 어느 쪽이든 필드는 같다. 이후 초안 작성과 담당자별 저장은 이 결과를 입력으로 쓴다.

| 필드 | 내용 |
|---|---|
| `todos[].work_key` | 원문 추적 키. 표의 ID, 없으면 `<회의 ID>-auto<n>` |
| `todos[].action` | 할 일 한 문장 |
| `todos[].assignees` | 담당자 이름 원문 목록. 멤버 연결은 `resolve.map_assignees`가 한다 |
| `todos[].due` | 기한 원문 표현. 날짜 계산은 `resolve.parse_due`가 회의 날짜 기준으로 한다 |
| `todos[].evidence_quotes` | 근거 원문 문장 1~3개. 원문에 없는 문장은 코드가 뺀다 |
| `todos[].expected` | 예상 티켓 처리 원문 (표에만 있음) |
| `todos[].warnings` | 코드가 붙인 확인 메모 (원문과 다른 기한 등) |
| `decisions[]` | 결정 사항 `content`와 근거 `evidence_quotes` |
| `open_questions[]` | 미결 질문 원문 |
| `excluded[]` | 제외·보류 항목 원문 |

근거 인용 규칙: 표의 액션 칸과 결정 사항 줄은 원문 그대로라 그 문장을 근거로 쓴다. LLM 추출은 할 일·결정마다 근거 문장을 원문 그대로 복사하게 하고, 코드가 원문에 없는 인용을 빼면서 확인 메모를 남긴다. 할 일의 근거가 모두 빠지면 할 일 문장이 원문에 있을 때 그것을 쓰고, 그것도 없으면 "근거를 찾지 못해 확인 필요" 메모를 남긴다 (초안 작성에서 다시 근거를 찾는다). 원문 대조는 공백을 정규화해서 비교한다.

표가 없는 회의록(자유 형식 메모 등)은 LLM이 결정·미결 질문·제외 항목도 함께 뽑는다(`llm.extract`). 회의록에 "핵심 결정", "미결 질문", "제외·보류" 섹션이 있으면 섹션별로 코드가 읽은 값을 쓰고(`- 없음`뿐이어도 섹션이 있으면 비어 있는 그대로 쓴다), 섹션이 없을 때만 LLM 값을 쓴다. LLM 값도 원문과 대조한다. 결정은 원문 근거가 있는 것만 남기고(근거가 모두 빠지면 결정 문장이 원문에 있을 때 그것을 근거로 쓴다), 미결 질문과 제외 항목은 원문에 그대로 있는 문구만 남긴다. LLM이 뽑은 제외 항목은 검수 2번(제외·보류 항목 아님)의 기준으로도 쓰인다.

```json
{
  "source": "table",
  "todos": [
    {
      "work_key": "10m1-a7",
      "action": "CSV 내보내기 통합 QA 실행",
      "assignees": ["김민준", "박서연", "이도윤", "최유진", "정하늘", "오지훈"],
      "due": "2026-10-22 14:00",
      "evidence_quotes": ["CSV 내보내기 통합 QA 실행"],
      "expected": "신규 티켓",
      "warnings": []
    }
  ],
  "decisions": [
    {
      "content": "1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다.",
      "evidence_quotes": ["1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다."]
    }
  ],
  "open_questions": ["IAM-48이 실제 권한 세분화 이슈인지"],
  "excluded": ["알림 센터 연동"]
}
```

### 이슈 출력문

이슈 본문은 Agent가 완성하고 MCP는 받은 그대로 올린다 (기획_v16 REQ-009). 초안(`schemas.DraftTicket`)이 내용을 만들고 `publisher.build_issue_body`가 아래 순서로 조립한다. 담당과 기한은 항상 쓰고, 나머지 칸은 값이 없으면 생략한다.

| 칸 | 내용 | 출처 |
|---|---|---|
| 작업 내용 | 무엇을 왜 하는지 2~4문장 | 초안 `description` |
| 상세 작업 | 체크리스트 1~5개 (`- [ ]`) | 초안 `tasks` |
| 완료 조건 | 확인 가능한 조건 1~5개 | 초안 `acceptance_criteria` |
| 근거 | 회의록 원문 인용 1~3개 | 초안 `evidence_quotes` (원문 대조 후). 모두 빠지면 추출 단계 근거 |
| 담당 | 회의록 담당자 이름, GitHub 계정. 계정이 없으면 이름만 | 추출 + 멤버 매핑 |
| 기한 | 계산한 날짜와 원문 표현 | 추출 + `resolve.parse_due` |
| 관련 이슈 | 회의록 예상 처리, 관련 키, 중복 판단으로 찾은 기존 이슈 | 추출 + 중복 판단 |
| 상위 이슈 | 예상 처리가 "하위 티켓"일 때의 키 | 추출 |
| 확인 필요 | 이 할 일과 관련된 미결 질문 | 초안 `open_questions` (추출된 미결 질문 안에서만) |
| 검수 메모 | 기본 초안으로 바뀌었을 때 남은 검수 지적 | 검수 |
| 회의 배경 | 접힌 블록(`<details>`)으로 배경 요약 | 회의록 배경 |

라벨은 본문이 아니라 이슈 생성 인자로 넘긴다. 초안이 라벨 후보 중에서 티켓마다 0~3개를 고르고, 코드가 후보에 없는 값을 빼서 카드 경고(`warnings`)에 남긴다(`publisher.pick_labels`). 후보(`label_candidates`)는 Agent가 MCP `list_labels`로 저장소 라벨을 받아 채운다(도구가 생기면 연결). 비어 있으면 설정 `ISSUE_LABELS`를 쓰고, 그것도 없으면 라벨 없이 발행한다.

초안 작성(`nodes.draft`)은 추출 결과를 입력으로 쓴다. 할 일마다 추출 단계에서 원문과 대조한 근거(`evidence_quotes`)를 넘기고, 회의의 결정 사항과 미결 질문을 프롬프트에 함께 넣는다. 코드가 초안을 다시 확인한다.

- 근거: 원문에 없는 인용은 뺀다. 모두 빠지면 추출 단계의 근거를 그대로 두고, 원문에 없는 문장(할 일 문장 포함)은 근거로 쓰지 않는다. 근거가 채워지면 추출 단계의 "근거를 찾지 못해 확인 필요" 경고를 지운다.
- 미결 질문: 추출된 미결 질문 목록에 있는 것만 남긴다 (공백만 다르면 원문 표현으로 맞춤).
- 빠진 초안: LLM이 어떤 할 일의 초안을 돌려주지 않으면 검수로 보내지 않고 그 할 일만 다시 작성한다. 보완 중이던 티켓은 같은 피드백으로 다시 쓴다. 재작성은 보완 한도(`MAX_REVISIONS`)를 같이 써서 티켓당 LLM 호출 수를 묶는다. 한도까지 빠지면 검수가 처리한다 (빈 초안은 9번 점검, 이전 초안은 다시 검수).
- 카드 경고: 초안 단계 경고(뺀 라벨, 원문과 다른 초안 근거)는 초안을 다시 쓸 때마다 새로 계산해 쌓이지 않는다. 문구로 추출 단계 경고와 구분한다.

결과의 `content_json.issue_body`에는 발행할 때와 같은 방법으로 조립한 이슈 본문이 들어간다. 발행 전에 초안을 저장하거나 미리보기를 보여줄 때 쓴다. 본문에는 회의록 원문(근거, 배경)과 `<details>` HTML이 들어 있으므로, 미리보기에서 HTML로 그릴 때는 반드시 sanitize한다 (스크립트, 이벤트 속성 제거).

### 발행 대상 분류와 Slack·Notion 출력문 (TASK-020, TASK-021)

초안 작성 LLM이 할 일마다 발행 대상과 한 줄 사유를 함께 낸다 (LLM 호출 추가 없음, 기획 1.7.1).

- 코드 변경이 한 부분이라도 필요한 업무(구현, 버그 수정, 리팩터링, 배포, 테스트 코드 작성) → `GITHUB`
- 코드 변경이 필요 없는 업무(디자인, 기획, 문서 작성, 조사) → `NOTION`. 라벨은 쓰지 않는다
- 담당자의 직군은 기준으로 쓰지 않는다. 분류는 제안이고 담당자가 카드에서 바꿀 수 있다 (TASK-031)
- 중복 판단은 `GITHUB` 티켓만 한다 (기획 1.7.2)
- `PUBLISH_ON_RUN=true`(Phase 1 baseline)는 분류와 상관없이 GitHub로만 발행하고 공지를 만들지 않는다

결과 `contentJson`에는 `publish_target`(`GITHUB`/`NOTION`/`SLACK`)과 `target_reason`이 들어가고, 대상별 출력문이 붙는다.

| 대상 | 출력문 | 만드는 곳 |
|---|---|---|
| `GITHUB` | `issue_body` (위 "이슈 출력문") | `publisher.build_issue_body` |
| `NOTION` | `notion_page.{title, markdown}`. `create_page(root, title, markdown)`에 그대로 넘긴다. GitHub 전용 칸(라벨, 관련·상위 이슈, @계정)은 없다 | `publisher.build_notion_page` |
| `SLACK` | 회의 하나당 공지 티켓 하나(`work_key`: `<회의 ID>-notice`). `slack.text`와 `slack.reviewers` | `publisher.build_slack_notice` |

Slack 공지는 코드가 만든다 (LLM 없음). 결정 사항, 새 작업 수(GitHub 이슈·Notion 페이지)와 기존 이슈 연결 수, 검토 요청을 담는다. 검토 요청은 실제로 티켓을 받는 사람과 맞춘다: GitHub 티켓은 회의록 담당자가 프로젝트 멤버 1명과 정확히 연결될 때만 "@이름 N건"(백엔드 `AssigneeResolver`와 같은 규칙), 나머지는 "담당 지정 필요 N건", Notion 티켓은 "Notion 페이지 N건 (업로더 확인)"이다. 게시할 때(#151) Slack 연결이 확인된 담당자만 `post_message`의 `mentions`로 넘겨 멘션되고, 나머지는 이름 텍스트로 남는다. 할 일이 없어도 결정 사항이 있으면 공지를 만든다. 백엔드는 `publish_target`을 티켓의 발행 대상 칸에 저장하고(GitHub 이외는 처리 유형이 항상 신규 발행), Slack 공지와 Notion 티켓을 업로더의 내 티켓으로 보낸다 (REQ-012).

### Harness 명세

모델이 제안만 하고 실행·한도·기록·복구는 Agent 코드가 맡는다. 요소별 구현과 값은 아래와 같다. 값을 바꾸면 이 표도 고친다.

| 요소 | 구현 | 값·설정 |
|---|---|---|
| Instructions | 프롬프트 md 분리, 구조화 출력, 작업별 모델·추론 설정 | `prompts/*.md`, `PROMPT_VERSION`, 위 "모델" 표 |
| Tools | MCP 서버 2개(business-mcp, rag-mcp) 연결, 모델용 조회 도구 3개(저장소 고정, 결과 길이 제한, 오류는 `[코드] 메시지`로 모델에게 전달), 쓰기 도구는 코드만 호출 | `MCP_SERVER_URL`, `RAG_MCP_URL`, `TOOL_RESULT_MAX_CHARS` |
| Agent Loop | LangGraph StateGraph (prepare → draft ⇄ review → dedupe → finish, `PUBLISH_ON_RUN`이면 dedupe → publish → finish), 중복 판단은 티켓마다 model ⇄ tools 루프(모델이 도구 선택), 작업 마감, LLM 호출 시간 한도 | 보완 `MAX_REVISIONS`=2, recursion 50, `TASK_DEADLINE_SECONDS`=600, `LLM_TIMEOUT_SECONDS`=120, 판단 `DEDUPE_MAX_TOOL_CALLS`=4·`DEDUPE_MAX_SAME_TOOL`=3·`DEDUPE_TIMEOUT_SECONDS`=30·`DEDUPE_CONCURRENCY`=4 |
| Context | 원문과 코드가 정리한 표만 전달, 초안에 추출 결과(할 일별 근거, 결정 사항, 미결 질문) 주입, 검수에 제외·보류 목록 주입, 원문 길이 한도 | `MAX_SOURCE_CHARS`=40000, `DRAFT_BATCH_SIZE`·`REVIEW_BATCH_SIZE`=10 |
| State·Memory | 노드가 끝날 때마다 상태 저장(checkpointer, thread id = taskId), 발행한 taskId 재요청은 저장된 결과로 응답, 실행 중 같은 taskId는 409, `GET /runs/{id}/history` | 메모리 저장 (재시작 시 사라짐) |
| Guardrails | 자동 검수(아래 "검수 판정"), 회의록 처리는 발행하지 않음(초안 저장), 허용 저장소, APPROVED만 발행(`PUBLISH_ON_RUN`), 중복 판단 도구 호출 수·같은 도구 수·시간 한도, 도구 결과에서 본 이슈 번호만 인정, 기존 이슈는 연결만 하고 수정·닫기·댓글 안 함 | `PUBLISH_ON_RUN`, `CONDITIONAL_AS_NEEDS_CHECK`, `FALLBACK_PUBLISH`, `DRY_RUN` |
| Recovery | LLM 오류를 종류별로 처리(아래 "LLM 오류 처리"), 대체 모델, 발행 타임아웃은 UNKNOWN, 치명 오류 시 남은 발행 중단, 검수 보완 한도 초과 시 기본 초안, 중복 판단·색인 추가가 실패해도 새로 발행 | `LLM_RETRIES`=2, `OPENAI_FALLBACK_MODEL` |
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
  - 발행: 이슈마다 business-mcp의 GitHub 호출 한도 20초
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
2. 다른 터미널에서 MCP 서버 실행: `cd ../business-mcp && python server.py`
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

- 기본(초안 저장 모드): 모든 항목이 `reviewStatus: PENDING`(담당자 확인 대기), `publishStatus: DRAFT`(발행 전 초안)다. 검수 지적은 `contentJson.warnings`와 `contentJson.review.notes`에 남고, `summary.drafts`에 초안 수가 들어간다. 발행은 담당자가 확인한 뒤 일괄 발행(TASK-034)이 한다.
- `PUBLISH_ON_RUN=true`(Phase 1 baseline)일 때 `publishStatus`: `SUCCEEDED`(발행, 또는 같은 작업의 열린 기존 이슈로 연결 — 아래 "중복 판단"), `FAILED`(실패), `UNKNOWN`(응답 불명), `BLOCKED`(검수 미통과로 발행 안 함), `SKIPPED`(DRY_RUN이라 발행 생략, 이슈 번호 없음)

- `traceId`: LangSmith run id. 성공·실패 모두 들어 있어 실패한 작업의 trace를 바로 찾을 수 있다.
- `metrics`: `durationMs`, `promptVersion`, `model`, `reviewModel`, `tracing`(trace 수집 여부), `llmCalls`, `llmRetries`, `fallbackUsed`(대체 모델 사용 수), `errors`(오류 종류별 수: transient·request·auth), `droppedSettings`(모델이 거부해 뺀 설정)

`?wait=true` 없이 보내면 202로 접수하고 백그라운드에서 처리한다. 처리 중에 `GET /runs/{taskId}`를 부르면 지금 단계를 돌려준다.

```json
{"taskId": "…", "status": "RUNNING", "stage": "REVIEWING",
 "progress": {"step": "DEDUPING",
              "context": {"members": 6, "issues": null, "notionDocs": null},
              "extraction": {"todos": 8, "decisions": 6, "openQuestions": 2},
              "dedupe": {"done": 5, "total": 7, "linked": 1}}}
```

`stage`는 백엔드 `TaskStage` 값이다: `EXTRACTING`(할 일 추출) → `NORMALIZING`(담당자·기한 정리, 초안 작성) → `REVIEWING`(검수, 보완, 중복 판단 포함) → `PUBLISHING`(발행, `PUBLISH_ON_RUN`일 때만) 순서다.

`progress`는 진행 화면(기획_v16 REQ-007, UI 플로우 5쪽)용 세부 정보다. 앞 단계의 값은 다음 단계에도 남는다.

| `step` | 화면 단계 | 함께 오는 값 |
|---|---|---|
| `CONTEXT` | 맥락 파악 | `context.members`. `issues`, `notionDocs`는 맥락 파악(TASK-019)이 생기기 전까지 `null` |
| `EXTRACTING` | 티켓 추출 (초안 작성·검수 포함) | `extraction.{todos, decisions, openQuestions}` |
| `DEDUPING` | 중복 검수 | `dedupe.{done, total, linked}`. 티켓 하나를 판단할 때마다 보고한다. 중복 판단이 꺼져 있으면 이 단계는 오지 않는다 (기획 3.1) |
| `DISPATCHING` | 담당자에게 전송 | 결과를 보내기 직전에 보고한다 (초안 저장 모드만) |

끝나면 결과 전체가 나온다. `BACKEND_BASE_URL`이 있으면 같은 단계·세부 정보와 결과를 백엔드에도 보고한다(`/internal/tasks/{id}/progress`, `/result`).

### 같은 taskId를 다시 보낼 때

노드가 끝날 때마다 상태를 taskId 단위로 저장한다(LangGraph checkpointer). 그래서 같은 taskId가 다시 오면 이렇게 처리한다.

| 이전 작업 | 처리 |
|---|---|
| 처리 중 | 409 (백엔드는 조회로 넘어감) |
| 이슈를 발행했거나 발행 여부가 불명(UNKNOWN) | 다시 실행하지 않는다. 202로 받고 `GET /runs/{taskId}`에 저장된 결과를 돌려준다 (중복 이슈 방지) |
| 발행 전에 끝남 (LLM 오류, 발행 0건, `--dry-run`) | 이전 기록을 지우고 다시 실행한다 |

`POST /projects/check-members`는 백엔드가 프로젝트를 저장할 때 부른다. 요청 `{ githubOwner, githubRepo, githubNames }`에 `{ members: [{ githubName, permission: GRANTED | DENIED | ERROR }] }`로 답한다. ERROR는 그 멤버만 확인에 실패한 경우다. MCP 전체 오류는 HTTP 502와 `{ errorCode, errorMessage }`로, MCP의 오류 코드(`MCP_UNAVAILABLE`, `REPOSITORY_NOT_ALLOWED`, `GITHUB_NOT_FOUND` 등)를 그대로 전달한다.

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

`RAG_ENABLED=true`면 business-mcp(8000)와 함께 rag-mcp(8002)에도 연결한다. 도구 명세는 이슈 #83에 있다.

| 도구 | 서버 | 누가 부르나 | 하는 일 |
|---|---|---|---|
| `search_similar_issues` | rag-mcp | 모델 | 의미가 비슷한 기존 이슈 찾기 (k 최대 10) |
| `search_issues` | business-mcp | 모델 | 이슈 키(예: AUTH-112)나 정확한 단어로 찾기 |
| `read_issue` | rag-mcp Resource `issue://{owner}/{repo}/{number}` | 모델 | 후보 이슈 원문 읽기 (모델은 Tool만 부를 수 있어 Agent가 Resource를 도구로 감쌈) |
| `sync_issues` | rag-mcp | 코드 | 실행마다 1회, 바뀐 이슈만 다시 색인 (`RAG_SYNC_TIMEOUT_SECONDS` 안에 안 끝나면 건너뜀) |
| `index_issue` | rag-mcp | 코드 | 새로 발행한 이슈를 색인에 추가 |
| `create_issue` | business-mcp | 코드 | 이슈 발행 (모델에게 주지 않음) |

- 저장소(owner/repo)는 백엔드 요청 값으로 고정한다. 모델용 도구에는 저장소 인자가 없어서 모델이 바꿀 수 없다.
- 도구 결과는 `TOOL_RESULT_MAX_CHARS`(2000자)로 자르고, 오류는 예외 대신 `[코드] 메시지`로 모델에게 돌려준다. `INDEX_EMPTY`(색인 없음)는 "후보 없음"으로 준다.
- rag-mcp에 연결하지 못하면 경고만 남기고 중복 확인 없이 기존처럼 발행한다. 결과 `metrics.rag.status`는 `disabled` / `unavailable` / `ready`이고, `metrics.rag.sync`에 색인 갱신 결과가 남는다.
- 판단 결과에 따른 발행은 아래 "중복 판단 (dedupe)"의 "발행 처리" 표를 본다.

### 중복 판단 (dedupe)

검수 다음, 발행 전에 승인(APPROVED)된 티켓마다 기존 이슈와의 관계를 판단한다. 모델이 조회 도구를 골라 부르고, 근거를 보고 `submit_decision`으로 관계를 제출한다.

```
model ──(조회 도구 호출)──▶ tools ──▶ model ── … ──(submit_decision)──▶ 끝
```

| 관계 | 뜻 |
|---|---|
| `SAME` | 같은 대상에 같은 변경 (이미 있는 작업) |
| `CHANGE` | 같은 대상이지만 방향·값이 다른 변경 |
| `RELATED` | 같은 영역의 다른 작업 |
| `UNRELATED` | 관계 없음 (후보가 없을 때도) |

- 모델에게는 조회 도구 3개(`search_similar_issues`, `search_issues`, `read_issue`)와 `submit_decision`만 준다. 이슈 생성 도구는 주지 않는다. 매 턴 도구를 하나 이상 부르게 한다(`tool_choice="required"`).
- 회의록에 관련 키(예: AUTH-112)가 있으면 키워드 검색을, 없으면 유사 검색을 먼저 고르도록 프롬프트로 안내한다. 유사 검색의 첫 질의는 회의록 원문 할 일 문장을 그대로 쓰고, 다시 검색할 때만 표현을 바꾼다. 어느 도구를 몇 번 부를지는 모델이 정한다.
- 모델에게는 회의록 원문의 할 일과 근거 인용을 준다. 초안 설명은 주지 않는다. 초안에 덧붙은 회의 맥락 때문에 같은 작업을 "범위가 더 넓다"며 RELATED로 판단하는 일이 많았다(#107).
- 판정 순서: 값/방향이 다르면 CHANGE → 기존 이슈가 하기로 한 일 안에 들어가면 SAME → 기존 이슈에 없는 일을 더 요구하면 RELATED. 닫힌 이슈도 같은 기준으로 판정한다.
- CHANGE면 모델이 `before`(기존 값)와 `after`(새 값)를 함께 제출한다. 카드의 변경 전후 표시와 갱신 섹션에 쓴다.
- 한도는 코드가 센다. 넘으면 판단 없이 끝내고(`skipped`) 새로 발행한다.

| 한도 | 기본값 | `skipped` |
|---|---|---|
| 티켓당 도구 호출 수 `DEDUPE_MAX_TOOL_CALLS` | 4 | `tool_limit` |
| 같은 도구 호출 수 `DEDUPE_MAX_SAME_TOOL` | 3 | `same_tool_limit` |
| 티켓당 시간 `DEDUPE_TIMEOUT_SECONDS` | 30초 | `timeout` |
| 동시에 판단하는 티켓 수 `DEDUPE_CONCURRENCY` | 4 | - |

- 그 밖에 판단 없이 끝나는 경우: 도구 결과에서 보지 않은 이슈 번호를 고름(`unverified_issue`), 제출 형식이 틀림(`invalid_decision`), LLM 오류(`error: …`), rag-mcp 연결 실패(`rag_unavailable`), 작업 마감이 지남(`deadline`).
- 도구 오류는 판단을 멈추지 않는다. `[코드] 메시지`로 모델에게 돌려주고, 모델이 다른 도구를 고르거나 UNRELATED로 끝낸다.
- 24건 회의도 4건씩 동시에 판단해 최악 약 3분 안에 끝난다 (30초 × 24 / 4).
- `RAG_ENABLED=false`(기본)면 이 단계를 건너뛰고 기존처럼 발행한다.
- 결과: 항목 `contentJson.dedupe`에 `relation`, `issue_number`, `issue_state`, `issue_title`, `reason`, `before`, `after`(CHANGE일 때만), `tool_calls`(모델이 고른 도구와 인자), `skipped`, `handling`(아래 "처리 유형")이 남는다. 연결했으면 `linked_existing: true`, 새로 발행해 색인에 넣었으면 `indexed`가 붙는다. `metrics.dedupe`에 판단 수, 건너뛴 수, 도구 호출 수, 관계별 수, 색인한 수가 남는다.

#### 처리 유형 (기획_v16 1.7.2, `handling.py`)

판정마다 처리 유형을 정해 `contentJson.dedupe.handling`에 남긴다. 담당자가 카드에서 바꿀 수 있는 제안이고, 실제 GitHub 쓰기는 일괄 발행(TASK-034)이 실행한다.

| 판정 | 열린 이슈 | 닫힌 이슈 |
|---|---|---|
| `SAME` | `LINK`: 기존 이슈에 댓글로 연결 | `CREATE` + 기존 번호 표기, 겹칠 수 있음 |
| `CHANGE` | `UPDATE`: 기존 이슈 본문 끝에 변경 섹션 추가 | `CREATE` + 기존 번호 표기, 겹칠 수 있음 |
| `RELATED` | `CREATE` + 관련 이슈 표기, 겹칠 수 있음 | 같음 |
| `UNRELATED` / 판단 없음 | `CREATE` | `CREATE` |

- `handling`: `{type, issue_number, overlap, judged}`. `overlap`이면 카드에 "겹칠 수 있음", `judged=false`면 "중복 판단 안 됨"을 표시한다. 한도 초과나 오류로 판단하지 못한 티켓은 `CREATE`, `judged=false`다.
- 문안 함수 (GitHub를 호출하지 않음)
  - `link_comment(ticket, meeting_date, meeting_title)`: `add_comment`의 body (회의, 원문 할 일, 근거, 판단 사유)
  - `change_section(...)`: `update_issue`의 `append_section` (`## 회의 변경 사항 (날짜)`, 변경 전 → 후, 원문 할 일, 근거)
  - `change_note(...)`: `update_issue`의 `note` (갱신 알림 댓글)

#### 발행 직전 재대조 (TASK-042, `recheck.py`)

대조는 초안 생성 때(그래프의 `dedupe` 노드) 1회, 발행 직전 1회 한다. 초안을 만든 뒤 담당자가 확인하기까지 그사이에 생긴 이슈를 잡기 위해서다. 일괄 발행(TASK-034)이 저장소마다 한 번 부른다.

```python
out = await recheck(tickets, issue_tools=tools, llm=llm, settings=settings)
for t in out["tickets"]:
    t["handling"]   # 실제로 실행할 처리 유형 {type, issue_number, overlap, judged, changed}
    t["recheck"]    # 재대조 판정 (contentJson.dedupe와 같은 형식)
```

- 입력 티켓은 결과 `contentJson` 형태(`action_text`, `evidence_quotes`, `expected`, `title`, `work_key`)에 담당자가 확인한 `handling`을 더한 것이다. 입력은 바꾸지 않고 복사본을 돌려준다.
- 대조 전에 색인을 한 번 증분 동기화한다(`sync_issues(full=false)`, `RAG_SYNC_TIMEOUT_SECONDS`=30). 실패(어떤 예외든)하거나 시간을 넘기면 기존 색인으로 계속한다.
- 판정 루프와 한도는 초안 생성 때와 같다.
- 최종 처리 유형(`handling.after_recheck`)
  - 재대조가 열린 같은 작업(`LINK`)을 찾으면 연결로 바꾸고 `changed=true`. 단 `handling.edited=true`(담당자가 카드에서 직접 바꾼 유형)면 바꾸지 않는다. 이미 확인된 티켓이라 다시 묻지 않고 댓글로 연결하며, 결과 화면에 표시한다.
  - 그 밖에는 담당자가 확인한 처리 유형을 그대로 쓴다. 카드에서 바꾼 유형을 재대조가 덮지 않는다.
  - 재대조에 실패하거나 rag-mcp가 없어도 확인한 처리 유형으로 발행한다.

#### 발행 처리 (현재 발행 노드)

`POST /publish`가 확인된 티켓만 별도 일괄 발행한다. 요청에는 백엔드가 발급한 `batchId`, 프로젝트별 저장소, 카드에서 편집된 `contentJson`, 확정 처리 유형과 대상 이슈 번호가 들어간다. Agent는 저장소별 재대조 뒤 `CREATE`는 `create_issue`, `LINK`는 `add_comment`, `UPDATE`는 `update_issue`를 코드로 호출하고 `/internal/publish-batches/{batchId}/result`에 결과를 보고한다. 같은 batchId는 다시 실행하지 않는다.

| 판단 | 기존 이슈가 열려 있음 | 기존 이슈가 닫혀 있음 |
|---|---|---|
| `SAME` | 새로 만들지 않고 연결 | 새로 발행, 본문 "관련"에 `이전 작업: #N` |
| `CHANGE` | 새로 발행, `#N과 다른 변경` | 새로 발행, `변경 전 작업: #N` |
| `RELATED` | 새로 발행, `관련 이슈: #N` | 새로 발행, `관련 이슈: #N` |
| `UNRELATED` / 판단 없음 | 새로 발행 | 새로 발행 |

- 연결은 `publishStatus=SUCCEEDED`, `githubIssueNumber`·`githubIssueUrl`은 기존 이슈(`https://github.com/{owner}/{repo}/issues/{N}`), `reason`은 `기존 이슈 #N과 같은 작업이라 새로 만들지 않음`이다. 백엔드 계약은 바뀌지 않는다(생성 성공 조건인 번호와 프로젝트 저장소 URL을 만족).
- `summary.linked`는 연결한 수다. `summary.published`(SUCCEEDED 수)에 들어 있다.
- 연결은 GitHub에 쓰지 않으므로 `DRY_RUN`이어도 한다.
- 기존 이슈는 수정·닫기·댓글을 하지 않는다.
- 새로 발행한 이슈는 바로 색인에 넣어(`index_issue`) 다음 실행에서 찾을 수 있게 한다. 실패하거나 10초 안에 끝나지 않아도 경고만 남기고 발행 결과는 그대로다. DRY_RUN이면 하지 않는다.
- 그래서 같은 회의록을 새 taskId로 다시 보내면, 모델이 첫 실행에서 만든 열린 이슈를 찾아 `SAME`으로 판단하고 새 이슈 없이 연결한다.
- 진행 단계는 `REVIEWING`을 유지한다(백엔드 단계 값에 새 값이 없음). `GET /runs/{id}/history`와 LangSmith trace에는 `dedupe` 노드와 티켓별 `dedupe` 실행(model → tools …)이 보인다.

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
- 근거 인용 중 원문에 없는 것은 초안 단계에서 뺀다 (모두 없으면 추출 단계의 근거를 그대로 둔다).
- 기본(초안 저장 모드)에서는 검수가 티켓을 막지 않는다. 승인·반려는 담당자가 내 티켓에서 한다 (기획 1.6).
  - 보완은 최대 `MAX_REVISIONS`(기본 2)회. 넘어도 남은 지적, 보완으로 고칠 수 없는 1번 지적은 `contentJson.review.notes`와 카드 경고("자동 검수 지적 N개가 남아 확인 필요")로 남기고 마지막 초안을 그대로 넘긴다 (기본 초안으로 바꾸지 않음)
  - "발행 보류", "조건부 기존 이슈 갱신" 표기는 카드 경고로 남기고 다른 초안처럼 검수한다
- 아래는 `PUBLISH_ON_RUN=true`(Phase 1 baseline)일 때의 판정이다.
- 보완은 최대 `MAX_REVISIONS`(기본 2)회. 넘어도 지적이 남으면
  - 액션 아이템 표에서 온 티켓 → 원문 기반 기본 초안으로 승인 (`FALLBACK_PUBLISH=true`, 본문에 검수 메모)
  - 표 없이 LLM이 뽑은 티켓에 2·3번 지적이 남음 → `NEEDS_CHECK`
- 회의록에 "발행 보류"면 `NEEDS_CHECK`, "조건부 기존 이슈 갱신"은 `CONDITIONAL_AS_NEEDS_CHECK=true`(기본)일 때 `NEEDS_CHECK`
- `APPROVED`만 발행 단계로 넘어간다.

### 1차 한계
- `RAG_ENABLED=false`(기본)면 기존 GitHub 이슈를 조회하지 않는다. 같은 taskId는 다시 발행하지 않지만(위 표), 같은 회의를 새 taskId로 다시 보내면 이슈가 다시 생성된다. 켜면 같은 작업의 열린 이슈로 연결한다("중복 판단").
- "기존 이슈 갱신(AUTH-112)" 같은 표기는 새 이슈 본문에 관련 키로만 남긴다.

### 테스트

```bash
pytest
```

가짜 LLM·가짜 도구를 쓰므로 OpenAI와 GitHub를 호출하지 않는다.

## Spring·Agent Swagger 명세 (#58)

[공개·내부 API 구분, Swagger 접속 및 전체 흐름 테스트 안내](../docs/api/README.md)를 참고한다.
