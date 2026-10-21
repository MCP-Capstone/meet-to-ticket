# rag-mcp

기존 GitHub 이슈를 저장소별 Vector DB(Chroma)에 미리 색인해 두고, Agent가 이슈를 발행하기 전에 **비슷한 기존 이슈를 찾을 수 있게** 하는 MCP 서버입니다. 이 문서는 `rag-mcp/` 폴더만 다룹니다.

| 서버 | 역할 |
|---|---|
| `mcp-server/` (8000) | GitHub 이슈 생성·조회 (business mcp) |
| `rag-mcp/` (8002) | 기존 이슈 색인과 의미 검색 (rag mcp) |

강의안 레퍼런스 아키텍처의 rag mcp 구조를 따릅니다. 문서(이슈)는 질문과 별개로 미리 색인하고, 검색은 Vector DB에서 합니다.

> 지금은 이슈 색인까지 있습니다. 검색 도구와 이슈 원문 Resource(#86)는 이어서 추가합니다.

## 1. 이 폴더의 파일

| 파일 | 역할 | Git에 올리나 |
|---|---|---|
| `server.py` | MCP 서버 (Streamable HTTP, 기본 `127.0.0.1:8002/mcp`) | 올림 |
| `config.py` | `.env` 값을 읽어 설정으로 정리 | 올림 |
| `github.py` | GitHub 이슈 읽기 (읽기 전용, 허용 저장소 검사) | 올림 |
| `embeddings.py` | 문장을 벡터로 바꾸는 임베딩 (OpenAI) | 올림 |
| `store.py` | Vector DB(Chroma) 저장소. 저장소별 컬렉션 | 올림 |
| `indexer.py` | 색인 파이프라인 (바뀐 이슈만 다시 색인) | 올림 |
| `index.py` | 색인 실행 명령 | 올림 |
| `errors.py` | 도구 오류 형식 `[코드] 메시지` | 올림 |
| `requirements.in` | 직접 쓰는 패키지와 허용 범위 | 올림 |
| `requirements.txt` | `.in`을 풀어서 전체 버전을 고정한 파일. uv가 생성한다 | 올림 |
| `requirements-dev.in`, `requirements-dev.txt` | 위에 테스트 도구를 더한 것. 개발자 PC는 이걸로 설치한다 | 올림 |
| `.python-version` | Python 버전(3.12) | 올림 |
| `.env.example` | `.env`에 어떤 값이 필요한지 보여 주는 빈 양식 | 올림 |
| `.env` | GitHub 토큰, OpenAI 키 등 비밀 값. 직접 만든다 | **안 올림** |
| `.venv/` | 가상환경 | **안 올림** |
| `chroma-data/` | Vector DB 저장 폴더. 색인하면 생긴다 | **안 올림** |

## 2. 왜 mcp-server와 환경을 따로 두나

같은 mcp 2.x를 쓰지만, 이 서버는 Vector DB(chromadb)와 임베딩(openai) 패키지가 추가로 필요합니다. chromadb는 함께 설치되는 패키지가 많아(약 100개), mcp-server 환경에 섞지 않고 폴더와 가상환경을 따로 둡니다. agent(mcp 1.x) 환경과도 합치지 않습니다.

## 3. 처음 세팅

`rag-mcp/` 폴더 안에서 실행합니다. uv 설치 방법은 `mcp-server/README.md` 4절과 같습니다.

```bash
uv venv --python 3.12
uv pip sync requirements-dev.txt
source .venv/bin/activate
cp .env.example .env
```

`.env`를 열어 `GITHUB_TOKEN`, `OPENAI_API_KEY`를 채웁니다. 테스트 저장소는 `docs/dev-env.md` 기준 `MCP-Capstone/mcp-capstone`입니다.

| 이름 | 설명 |
|---|---|
| `GITHUB_TOKEN` | 색인할 저장소의 이슈를 읽을 수 있는 토큰 |
| `GITHUB_OWNER`, `GITHUB_REPO` | 색인 대상 저장소 (기본 허용 대상) |
| `GITHUB_ALLOWED_REPOS` | 선택. 여러 저장소를 허용할 때 쉼표 구분 `owner/repo` |
| `OPENAI_API_KEY` | 임베딩 생성용 키 |
| `EMBEDDING_MODEL` | 기본 `text-embedding-3-small` |
| `CHROMA_PATH` | Vector DB 저장 폴더. 비우면 `rag-mcp/chroma-data` |
| `MCP_HOST`, `MCP_PORT` | 선택. 기본 `127.0.0.1`, `8002` |

## 4. 실행

```bash
python server.py
```

**서버 주소: `http://127.0.0.1:8002/mcp`**. Agent는 이 주소로 연결합니다. 포트를 바꾸면 `docs/dev-env.md`와 Agent 설정도 함께 바꿉니다.

## 5. 이슈 색인

티켓 발행과 별개로 기존 이슈를 미리 색인해 둡니다. 처음 한 번은 전체가, 그 뒤로는 바뀐 이슈만 임베딩됩니다.

```bash
python index.py                       # .env의 GITHUB_OWNER/GITHUB_REPO
python index.py --owner MCP-Capstone --repo mcp-capstone
python index.py --full                # 기존 색인을 지우고 전체 다시 색인
```

결과 예: `{"repository": "MCP-Capstone/mcp-capstone", "indexed": 3, "skipped": 0, "total": 16, ...}`

| 항목 | 내용 |
|---|---|
| 색인 대상 | PR을 뺀 열린·닫힌 이슈 |
| 색인 텍스트 | 제목 + 본문 앞 2,000자 |
| 저장 위치 | `CHROMA_PATH` 폴더, 저장소마다 컬렉션 `issues__{owner}__{repo}` (cosine) |
| 컬렉션 메타데이터 | 임베딩 모델, 벡터 차원, 마지막 색인 시각(`last_synced_at`) |
| 다시 색인 | `last_synced_at` 이후 바뀐 이슈만 GitHub에서 받고, `updated_at`이 같으면 건너뜀. 바뀐 이슈가 없으면 임베딩을 부르지 않음 |
| 임베딩 모델을 바꾸면 | 기존 색인과 비교할 수 없어 자동으로 전체 다시 색인 |
| GitHub에서 지운 이슈 | 바뀐 것만 받는 방식이라 색인에 남습니다. `--full`로 정리합니다 |

오류는 `[코드] 메시지` 형식으로 나옵니다: `CONFIG_MISSING`(키 없음), `REPOSITORY_NOT_ALLOWED`, `GITHUB_AUTH`, `GITHUB_NOT_FOUND`, `GITHUB_RATE_LIMIT`, `GITHUB_ERROR`, `EMBEDDING_ERROR`.

## 6. 테스트

```bash
pytest
```

`tests/conftest.py`가 테스트 중 실제 GitHub·OpenAI 호출을 막습니다. 색인 테스트는 가짜 GitHub(`tests/fakes.py`), 가짜 임베딩, 임시 Chroma 폴더를 씁니다. 토큰과 키가 `.env`에 있어도 테스트에서는 지워집니다.

## 7. 패키지 추가 규칙

`mcp-server/README.md` 6절과 같습니다. 패키지 추가와 compile은 환경 담당이 하며, 이 폴더에서 아래 두 줄로 `.txt`를 만듭니다.

```bash
uv pip compile requirements.in --universal -o requirements.txt
uv pip compile requirements-dev.in -c requirements.txt --universal -o requirements-dev.txt
```
