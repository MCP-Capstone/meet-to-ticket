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
