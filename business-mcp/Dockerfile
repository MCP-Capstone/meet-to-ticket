# meet-to-ticket MCP 서버 이미지
# 빌드: docker build -t meet-to-ticket-mcp ./business-mcp
# 실행: docker run --rm -p 127.0.0.1:8000:8000 --env-file business-mcp/.env meet-to-ticket-mcp
# GitHub 토큰은 이미지에 넣지 않고, 실행할 때 --env-file(compose에서는 env_file)로 넣는다.

FROM python:3.12-slim

# 팀이 쓰는 uv와 같은 버전으로 잠금 파일을 설치한다
COPY --from=ghcr.io/astral-sh/uv:0.12.17 /uv /bin/uv

# MCP_HOST=0.0.0.0: 컨테이너 밖이나 다른 컨테이너에서 접속할 수 있도록 모든 주소에서 받는다.
# (127.0.0.1로 두면 컨테이너 안에서만 접속된다)
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MCP_HOST=0.0.0.0 \
    MCP_PORT=8000

WORKDIR /app

# 소스보다 먼저 복사해서, 코드만 바뀌었을 때는 패키지 설치 단계를 캐시로 재사용한다
COPY requirements.txt .
# 실행용 잠금 파일(requirements.txt)만 설치한다. 테스트 도구(requirements-dev.txt)는 넣지 않는다
RUN uv pip install --system --no-cache -r requirements.txt

COPY common.py server.py ./
COPY integrations ./integrations

# root가 아닌 사용자로 실행한다
RUN useradd --create-home --uid 1000 app
USER app

EXPOSE 8000

CMD ["python", "server.py"]
