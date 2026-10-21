# meet-to-ticket RAG MCP 서버 이미지
# 빌드: docker build -t meet-to-ticket-rag ./rag-mcp
# 색인: docker run --rm --env-file rag-mcp/.env -v meet-to-ticket-rag-data:/data meet-to-ticket-rag python index.py
# 실행: docker run --rm -p 127.0.0.1:8002:8002 --env-file rag-mcp/.env -v meet-to-ticket-rag-data:/data meet-to-ticket-rag
# GitHub 토큰과 OpenAI 키는 이미지에 넣지 않고, 실행할 때 --env-file(compose에서는 env_file)로 넣는다.

FROM python:3.12-slim

# 팀이 쓰는 uv와 같은 버전으로 잠금 파일을 설치한다
COPY --from=ghcr.io/astral-sh/uv:0.12.17 /uv /bin/uv

# RAG_DEFAULT_HOST=0.0.0.0: 컨테이너 밖이나 다른 컨테이너에서 접속할 수 있도록 모든 주소에서 받는다.
# RAG_DEFAULT_CHROMA_PATH=/data/chroma: 색인을 볼륨(/data)에 저장해 컨테이너를 다시 띄워도 남게 한다.
# MCP_HOST, CHROMA_PATH 대신 RAG_DEFAULT_*로 두는 이유: .env의 빈 값(MCP_HOST=)이 --env-file로 들어와도 지워지지 않게
# ANONYMIZED_TELEMETRY=False: Chroma가 사용 통계를 외부로 보내지 않게 한다
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    RAG_DEFAULT_HOST=0.0.0.0 \
    RAG_DEFAULT_CHROMA_PATH=/data/chroma \
    ANONYMIZED_TELEMETRY=False

WORKDIR /app

# 소스보다 먼저 복사해서, 코드만 바뀌었을 때는 패키지 설치 단계를 캐시로 재사용한다
COPY requirements.txt .
# 실행용 잠금 파일(requirements.txt)만 설치한다. 테스트 도구(requirements-dev.txt)는 넣지 않는다
RUN uv pip install --system --no-cache -r requirements.txt

# 서버와 색인 명령에 필요한 소스만 복사한다 (tests 등은 .dockerignore로 제외)
COPY *.py ./

# root가 아닌 사용자로 실행하고, 색인 저장 폴더(/data)는 그 사용자가 쓸 수 있게 만든다
RUN useradd --create-home --uid 1000 app && mkdir -p /data && chown app:app /data
USER app
VOLUME /data

EXPOSE 8002

CMD ["python", "server.py"]
