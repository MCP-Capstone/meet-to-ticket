# business-mcp 개발환경 가이드

GitHub 이슈 도구를 제공하는 MCP 서버의 **파이썬 환경 설정** 문서입니다. 이 문서는 `business-mcp/` 폴더만 다룹니다.
`backend/`, `frontend/` 환경과 `agent/` 환경은 범위 밖입니다. 에이전트 쪽은 `agent/README.md`를 보세요.

이 폴더는 **mcp 2.x** 환경입니다. 확인된 버전(2026-10-20 기준)은 아래와 같습니다.

| 패키지 | 버전 |
|---|---|
| Python | 3.12 |
| mcp | 2.3.0 |
| httpx, python-dotenv | 0.28.1, 1.2.4 |
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
| `.env` | 실제 GitHub 토큰 등 비밀 값. 직접 만든다 | **안 올림** |
| `Dockerfile` | 컨테이너 이미지 설정 (11번) | 올림 |
| `.dockerignore` | 이미지에 넣지 않을 파일 목록 (`.env`, `.venv/`, `tests/` 등) | 올림 |
| `.venv/` | 가상환경 폴더. 설치된 패키지가 들어 있다 | **안 올림** |
| `server.py` | 서버를 만들고 서비스별 도구를 등록해 실행 | 올림 |
| `common.py` | 서비스 공통: `.env` 값, `[코드] 메시지` 오류, httpx 클라이언트, 도구 어노테이션 | 올림 |
| `integrations/<서비스>/` | 서비스별 `api.py`(외부 API 호출), `tools.py`(MCP 도구 등록). 지금은 `github/` | 올림 |

커밋하지 않을 파일(`.env`, `.venv/` 등)의 규칙은 이 폴더가 아니라 저장소 루트의 `.gitignore`가 담당합니다.

## 2. 왜 가상환경이 2개인가

에이전트가 쓰는 `langchain-mcp-adapters`는 `mcp>=1.24.0,<2.0.0`을 요구합니다. 반면 이 서버는 mcp 2.x의 `MCPServer`를 씁니다.
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
cd meet-to-ticket/business-mcp
```

**3) 가상환경 만들고 패키지 설치** (`business-mcp/` 폴더 안에서)

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

프롬프트 앞에 `(business-mcp)`가 보이면 켜진 것입니다. 끌 때는 `deactivate`입니다.

**5) `.env` 만들기**

macOS, Linux:

```bash
cp .env.example .env
```

Windows (PowerShell):

```powershell
Copy-Item .env.example .env
```

`.env`를 열어 `GITHUB_TOKEN` 값을 채웁니다. 이 파일은 절대 커밋하지 않습니다.

**6) 설치 확인**

```bash
python --version
python -c "from importlib.metadata import version; print('mcp', version('mcp'))"
python -c "from mcp.server.mcpserver import MCPServer; print('MCPServer OK')"
uv pip check
```

기대 결과는 `Python 3.12.x`, `mcp 2.x.x`, `MCPServer OK`, `All installed packages are compatible`입니다.

## 5. 실행 방법

서버를 HTTP로 띄웁니다. `business-mcp/` 폴더에서 가상환경을 켠 뒤 실행합니다.

```bash
python server.py
```

**서버 주소: `http://127.0.0.1:8000/mcp`** (host 127.0.0.1, port 8000, 경로 `/mcp`)

- mcp 2.3.0의 기본값이며, 임시 서버를 실제로 띄워 시작 로그와 접속으로 확인했습니다.
- 같은 임시 서버에 `agent/` 환경(mcp 1.30.0)의 `MultiServerMCPClient`가 접속해 도구 목록 조회에 성공했습니다.
- 1차 도구는 `create_issue`(이슈 생성), `list_issues`(이슈 목록 조회), `search_issues`(이슈 검색) 3개입니다. 자세한 내용은 아래 10번을 보세요.
- 에이전트는 `agent/.env`의 `MCP_SERVER_URL`로 이 주소에 접속합니다.

포트가 겹치면 `.env`의 `MCP_PORT`(필요하면 `MCP_HOST`)를 바꿉니다. 이때는 에이전트의 `MCP_SERVER_URL`도 같이 바꿔야 합니다.

```
MCP_PORT=8010
```

`server.py`는 시작할 때 `python-dotenv`로 `business-mcp/.env`를 읽습니다. 터미널에 같은 이름의 환경 변수가 있어도 `.env` 값이 우선합니다.

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

## 7. GitHub에 올리는 것과 올리지 않는 것

| 구분 | 대상 | 이유 |
|---|---|---|
| 올림 | 소스 코드 | 공유 대상 |
| 올림 | `requirements*.in`, `requirements*.txt` | 모두가 같은 버전을 쓰기 위해 |
| 올림 | `.python-version` | Python 버전 공유 |
| 올림 | `.env.example` | 필요한 값의 목록만 있고 비밀은 없다 |
| 안 올림 | `.env` | GitHub 토큰 같은 비밀 값이 들어 있다. 한 번 올라가면 기록에 남는다 |
| 안 올림 | `.venv/` | 용량이 크고 컴퓨터마다 다르다. `.txt`로 언제든 다시 만들 수 있다 |

안 올리는 파일은 저장소 루트의 `.gitignore`가 자동으로 걸러 줍니다. 이 폴더에는 별도의 `.gitignore`를 두지 않습니다.

## 8. 자주 나는 문제

**mcp 버전 충돌 (에이전트 패키지를 이 환경에 설치했을 때)**
`langchain-mcp-adapters`를 이 폴더 환경에 설치하면 mcp 2.x와 `mcp<2` 요구가 부딪혀 설치가 실패하거나 한쪽이 망가집니다. 에이전트 패키지는 `agent/` 환경에서만 설치합니다. 이미 설치했다면 환경을 지우고 다시 만듭니다.

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
`ModuleNotFoundError: No module named 'mcp'` 같은 오류가 납니다. 프롬프트 앞에 `(business-mcp)`가 있는지 확인하고 4번의 활성화 명령을 실행합니다. 켜지 않고 바로 실행하려면 환경의 파이썬을 직접 지정할 수도 있습니다.

```bash
.venv/bin/python server.py
```

Windows에서는 `.venv\Scripts\python.exe server.py`입니다.

**다른 폴더의 가상환경을 켠 채 실행한 경우**
`agent/` 환경을 켠 채 서버를 실행하면 mcp 1.x가 잡혀 `MCPServer`를 못 찾는 오류가 납니다. 프롬프트가 `(agent)`로 되어 있지 않은지 보고, 끈 뒤 이 폴더의 환경을 켭니다. 지금 어떤 환경인지는 아래로 확인합니다.

```bash
python -c "import sys; print(sys.prefix)"
```

경로 끝이 `business-mcp/.venv`여야 합니다.

**Windows PowerShell에서 activate가 막힐 때**
"이 시스템에서 스크립트를 실행할 수 없으므로..."라는 오류가 나면 실행 정책 때문입니다. 현재 사용자에게만 허용합니다.

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

**`.env`를 커밋해 버렸을 때**
1. **먼저** GitHub 토큰을 즉시 폐기(revoke)하고 새로 발급합니다. 기록에서 파일을 지우는 것보다 이게 우선입니다. 한 번 올라간 값은 이미 노출된 것으로 봅니다.
2. 새 토큰을 `.env`에 넣습니다.
3. 팀에 알립니다. 저장소 기록에서 파일을 지우는 작업은 팀과 상의해서 합니다.

## 9. 하지 말 것

- `requirements.txt`, `requirements-dev.txt`를 손으로 수정하지 않는다. 고치려면 `.in`을 수정하고 compile한다.
- `pip install`이나 `uv pip install`로 패키지를 하나씩 설치하고 그대로 두지 않는다. `.txt`에 없으면 다음 `uv pip sync` 때 지워지고, 다른 팀원 환경에는 아예 없다.
- `.venv/`를 커밋하지 않는다.
- `agent/`와 `business-mcp/` 환경을 하나로 합치지 않는다. mcp 버전이 충돌한다.
- `.env`와 실제 토큰을 커밋하거나 메신저, 이슈에 붙여넣지 않는다.
- 패키지 추가와 compile을 환경 담당이 아닌 사람이 임의로 하지 않는다.

## 10. 도구와 Agent 연동 계약

**구조**

```
business-mcp/
├── server.py                  서버 생성, 서비스별 register(mcp), 실행
├── common.py                  .env 값, "[코드] 메시지" 오류, httpx 클라이언트, READ/WRITE 어노테이션
└── integrations/
    └── github/
        ├── api.py             GitHub REST 호출과 오류 코드 변환 (MCP를 모름)
        └── tools.py           MCP 도구 정의와 register(mcp)
```

서비스(Notion, Slack)를 추가할 때는 `integrations/<서비스>/`에 `api.py`, `tools.py`를 두고 `server.py`에서 `register(mcp)`만 부릅니다.

**환경 변수 (`.env`)**

| 이름 | 설명 |
|---|---|
| `GITHUB_TOKEN` | 서비스 계정 토큰 하나. 모든 프로젝트 저장소에 쓴다 (서비스 계정을 저장소에 초대하고 Issues 읽기·쓰기 권한) |
| `GITHUB_ALLOWED_REPOS` | 선택. 값이 있으면 이 저장소들만 허용(쉼표 구분 `owner/repo`), 비우면 검사하지 않음 |
| `NOTION_TOKEN` | Notion Integration 토큰 하나. 기획 상위 페이지를 이 Integration에 공유해야 읽고 하위 티켓 페이지를 만들 수 있음 |
| `MCP_HOST`, `MCP_PORT` | 선택. 기본 `127.0.0.1`, `8000` |

토큰은 `.env`에만 둡니다. 어느 저장소에 쓸지(`owner`, `repo`)는 백엔드가 프로젝트 정보로 넘기고, Agent 코드가 도구 인자에 넣습니다 (모델에게 보여주지 않음).

**연결**: `python server.py` → `http://127.0.0.1:8000/mcp` (Streamable HTTP)

**도구**

| 도구 | 부르는 시점 | 입력 | 결과 | 다시 불러도 되나 |
|---|---|---|---|---|
| `create_issue` | 발행 (신규) | `owner`, `repo`, `title`, `body`, `labels`, `assignees` | `{status: "created", number, url, assignees, dropped_assignees, dropped_labels}` | 아니요 (이슈가 하나 더 생김) |
| `add_comment` | SAME 기존 이슈 연결 | `owner`, `repo`, `number`, `body` | `{status: "commented", comment_id, url}` | 아니요 (댓글이 하나 더 생김) |
| `update_issue` | CHANGE 기존 이슈 반영 | `owner`, `repo`, `number`, `append_section`, `add_labels`, `note` | `{status: "updated", number, url, dropped_labels, comment_id, comment_url, comment_error}` | 아니요 (본문·댓글이 달라짐) |
| `list_labels` | 초안 라벨 고르기 | `owner`, `repo` | `{labels: [{name, description}]}` | 예 |
| `check_member_permissions` | 프로젝트 저장(멤버 권한 표시) | `owner`, `repo`, `github_names` | `{members: [{github_name, permission}]}` | 예 |
| `list_issues` | 연결 확인 | `owner`, `repo`, `state="open"`, `limit=10` | `{issues: [{number, title, state, url, assignees}]}` | 예 |
| `search_issues` | 중복 판단 (키·단어 검색) | `owner`, `repo`, `query`, `state="all"`, `limit=5` | `{issues: [{number, title, state, url, closed_at}]}` | 예 |
| `check_notion_page` | 프로젝트 저장 시 Notion 연결 확인 | `page_url_or_id` | `{page_id, title, url}` | 예 |
| `list_pages` | 회의 분석 전 기획 문서 선택 | `root_page_url_or_id` | `{pages: [{page_id, title}]}` | 예 |
| `read_page` | 선택한 기획 문서 맥락 읽기 | `page_url_or_id`, `max_chars=4000` | `{page_id, title, url, markdown, truncated}` | 예 |
| `create_page` | Notion 분류 티켓 발행 | `root_page_url_or_id`, `title`, `markdown` | `{status: "created", page_id, url, folder_page_id}` | 아니요 (페이지가 하나 더 생김) |

- `create_issue`: 제목 앞뒤 공백 제거, 256자·본문 65000자 초과분은 자름. 담당자 지정이 거부(422)되면 담당자 없이 한 번 더 생성하고 `dropped_assignees`에 표시합니다.
- `create_issue` 라벨: 보내기 전에 저장소 라벨 목록과 비교해(대소문자 무시) 없는 라벨은 빼고 `dropped_labels`에 표시합니다. push 권한이 있는 토큰으로 없는 라벨을 넘기면 GitHub가 라벨을 새로 만들 수 있어서입니다. 라벨을 요청하지 않으면 라벨 목록을 조회하지 않습니다.
- `add_comment`: 기존 이슈에 연결 댓글을 새로 남깁니다. `SAME` 판정의 댓글 문안은 Agent가 만들고 MCP는 게시만 합니다.
- `update_issue`: 본문 전체를 바꾸지 않고 `append_section`을 기존 본문 끝에 추가합니다. 제목·담당자·상태는 받지 않으며, 라벨은 기존 라벨을 유지한 채 유효한 `add_labels`만 추가합니다. `note`가 있으면 갱신 알림 댓글을 한 번 더 남깁니다. 본문·라벨 저장 뒤 이 댓글만 실패하면 갱신 결과는 유지하고 `comment_error`로 알립니다. PR이나 본문 길이 초과 갱신은 거부합니다.
- `list_labels`: 저장소 라벨 전체(페이지를 모두 받음). Agent는 이 안에서 초안 라벨을 고릅니다.
- `check_member_permissions`: 멤버마다 이 저장소 이슈의 담당자로 지정할 수 있는지 GitHub assignees API로 확인합니다. `permission`은 `GRANTED`(가능), `DENIED`(불가), `ERROR`(확인 실패, 권한 없음으로 보지 않음). 먼저 저장소를 조회해서, 저장소를 볼 수 없으면 멤버별 결과 없이 `[GITHUB_NOT_FOUND]` 같은 오류를 돌려줍니다. 계정은 앞뒤 공백·대소문자 중복을 정리하고 한 번에 100명까지. GitHub 계정이 없는 멤버(`NO_ACCOUNT`)는 백엔드가 판단하고 넘기지 않습니다.
- `list_issues`: 최신순, PR 제외, `limit` 1~50, `state`는 `open`·`closed`·`all`
- `search_issues`: 검색 범위(`repo:owner/repo`, `is:issue`, `state:`)는 코드가 붙이고, 검색어에 섞인 `repo:`·`org:`·`is:` 같은 범위 지정은 지웁니다 (GitHub 검색은 `repo:`를 여러 개 주면 OR로 묶어 다른 저장소까지 찾기 때문). `limit` 1~20. 검색 API는 호출 한도가 따로 있습니다(인증 시 분당 30회 정도). 의미가 비슷한 이슈는 `rag-mcp`의 `search_similar_issues`로 찾습니다.
- `check_notion_page`: Notion URL·UUID를 정규 페이지 ID로 바꾸고 Integration 접근 여부를 확인합니다. 토큰만 넣는 것으로는 부족하며, 해당 상위 페이지를 Integration에 공유해야 합니다.
- `list_pages`: 기획 상위 페이지의 바로 아래 문서만 돌려줍니다. MCP가 만든 `회의 후속 티켓` 폴더는 분석 맥락에서 제외합니다.
- `read_page`: 최상위 블록을 Markdown으로 바꾸고 `max_chars`(1~12,000)까지만 돌려줍니다. Agent는 목록에서 필요한 문서를 고른 뒤 읽습니다.
- `create_page`: 기획 상위 페이지 아래 `회의 후속 티켓` 폴더를 재사용하거나 처음 한 번 만든 뒤, 그 아래 Markdown 티켓 페이지를 만듭니다. 제목·본문은 Agent가 만들고 MCP는 게시만 합니다.

**오류 코드**

실패는 `[코드] 메시지` 형식이고, Agent에는 `Error executing tool create_issue: [코드] 메시지`로 전달됩니다. 화면에 보여 줄 문구는 백엔드가 코드로 정합니다.

| 코드 | 원인 |
|---|---|
| `INVALID_ARGUMENT` | 입력값 오류 (빈 제목, 빈 owner/repo, 잘못된 state) |
| `REPOSITORY_NOT_ALLOWED` | `GITHUB_ALLOWED_REPOS`에 값이 있는데 그 안에 없는 저장소 |
| `GITHUB_AUTH` | 401·403: 토큰이 없거나, 서비스 계정에 그 저장소 권한이 없음 |
| `GITHUB_NOT_FOUND` | 404: 저장소가 없거나 토큰으로 볼 수 없음 |
| `GITHUB_VALIDATION` | 422: GitHub가 요청 값을 거부 |
| `GITHUB_RATE_LIMIT` | 429 또는 남은 호출 수 0인 403 |
| `GITHUB_ERROR` | 그 밖의 실패 (5xx 등) |
| `NOTION_CONFIG_MISSING` | `NOTION_TOKEN`이 설정되지 않음 |
| `NOTION_AUTH` / `NOTION_FORBIDDEN` | 토큰이 잘못됐거나 Integration에 페이지가 공유되지 않음 |
| `NOTION_NOT_FOUND` | 페이지를 찾을 수 없음 |
| `NOTION_VALIDATION` / `NOTION_RATE_LIMIT` / `NOTION_ERROR` | Notion 요청값 오류 / 호출 제한 / 그 밖의 실패 |

연결 실패나 시간 초과는 태그 없이 전달되고, 에이전트는 이를 "결과 모름"으로 처리합니다 (이슈가 만들어졌을 수 있어 다시 보내지 않음).

**테스트**: `pytest` (실제 GitHub 호출 없음, `httpx.MockTransport` 사용)

## 11. Docker로 실행

가상환경 없이 컨테이너로 서버를 띄울 수 있습니다. 저장소 루트(`meet-to-ticket/`)에서 실행합니다.

**1) 이미지 빌드**

```bash
docker build -t meet-to-ticket-mcp ./business-mcp
```

**2) 실행**

```bash
docker run --rm -p 127.0.0.1:8000:8000 --env-file business-mcp/.env meet-to-ticket-mcp
```

- 주소는 로컬 실행과 같은 `http://127.0.0.1:8000/mcp`입니다. 에이전트의 `MCP_SERVER_URL`은 바꾸지 않아도 됩니다.
- `-p 127.0.0.1:8000:8000`은 내 PC에서만 접속하게 합니다. `-p 8000:8000`으로 쓰면 같은 네트워크의 다른 PC에서도 접속할 수 있습니다.
- 로컬에서 `python server.py`가 이미 8000번을 쓰고 있으면 실행이 실패합니다. 먼저 끄거나 `-p 127.0.0.1:8010:8000`처럼 바깥 포트를 바꿉니다.
- 끌 때는 실행한 터미널에서 Ctrl+C를 누릅니다.

**이미지 구성**

| 항목 | 내용 |
|---|---|
| 기반 이미지 | `python:3.12-slim` |
| 설치 패키지 | `requirements.txt` (실행용 잠금 파일. 테스트 도구는 넣지 않음) |
| 들어가는 소스 | `server.py`, `common.py`, `integrations/` |
| 서버 주소 | 컨테이너 안에서 `0.0.0.0:8000` (`MCP_HOST`, `MCP_PORT` 기본값) |
| 실행 사용자 | root가 아닌 `app` |

**주의**

- `.env`(GitHub 토큰)는 이미지에 들어가지 않습니다(`.dockerignore`). 실행할 때 `--env-file`로 넣습니다.
- `business-mcp/.env`에 `MCP_HOST=127.0.0.1`을 적어 두면 컨테이너 밖에서 접속할 수 없습니다. 컨테이너로 실행할 때는 `.env`에 `MCP_HOST`를 적지 않습니다.
- `requirements.txt`나 소스를 바꾸면 이미지를 다시 빌드해야 반영됩니다.
- 다른 서비스와 함께 띄우는 `docker-compose.yml` 설정은 이 폴더 범위 밖입니다. compose에서는 서비스 이름으로 접속합니다(예: 에이전트에서 `http://business-mcp:8000/mcp`).
