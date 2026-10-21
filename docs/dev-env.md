# 개발 환경

## 포트

| 서비스 | 디렉토리 | 포트 | 주소 |
|---|---|---|---|
| Frontend (Vite) | `frontend/` | 5173 | http://localhost:5173 |
| Backend (Spring) | `backend/` | 8080 | http://localhost:8080 |
| Agent (FastAPI) | `agent/` | 8001 | http://localhost:8001 |
| MCP 서버 | `mcp-server/` | 8000 | http://127.0.0.1:8000/mcp |
| RAG MCP 서버 | `rag-mcp/` | 8002 | http://127.0.0.1:8002/mcp |
| PostgreSQL | - | 5432 | localhost:5432 |
| RabbitMQ AMQP | - | 5672 | localhost:5672 |
| RabbitMQ 관리 화면 | - | 15672 | http://localhost:15672 |

- 포트를 바꾸지 않는다. 바꿔야 하면 이 문서부터 수정하고 팀에 공지한다.
- Vite는 `strictPort`로 설정되어 있어 5173이 사용 중이면 다른 포트로 넘어가지 않고 실패한다.

## 호출 흐름

```
브라우저 → frontend(5173) ─/api 프록시─▶ backend(8080) → agent(8001) ─┬→ mcp-server(8000) → GitHub
                                            │                       └→ rag-mcp(8002) → Chroma (기존 이슈 색인)
                                            └→ PostgreSQL(5432)
```

- 브라우저가 직접 호출하는 곳은 frontend(5173)뿐이다.
- agent, mcp-server, rag-mcp는 서버끼리만 호출하므로 CORS 설정이 필요 없다.

## CORS

### 개발: Vite 프록시 (설정 완료)

프론트는 백엔드 주소를 직접 쓰지 않고 `/api`로 시작하는 상대 경로로 호출한다.

```ts
fetch('/api/meetings')
```

Vite 개발 서버가 `/api` 요청을 `http://localhost:8080`으로 전달한다. 브라우저 입장에서는 같은 출처(5173) 요청이라 CORS가 발생하지 않는다. 설정은 `frontend/vite.config.ts`에 있다.

- 백엔드 API는 모두 `/api`로 시작해야 한다.
- 프론트 코드에 `http://localhost:8080`을 직접 쓰지 않는다.

### 프록시 없이 호출할 때: Spring CORS 설정

빌드한 프론트를 따로 띄우는 등 프록시를 거치지 않는 경우에만 필요하다. 백엔드의 기존 `WebConfig`(`common/config/WebConfig.java`)에 아래 `addCorsMappings`를 추가한다.

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
```

## 티켓 테스트 저장소

티켓(GitHub Issue) 생성 테스트는 **`MCP-Capstone/mcp-capstone`** 저장소에서 한다. 이 저장소(`meet-to-ticket`)에는 테스트 이슈를 만들지 않는다.

`mcp-server/.env`:

```
GITHUB_OWNER=MCP-Capstone
GITHUB_REPO=mcp-capstone
```

## 실행 순서

1. PostgreSQL·RabbitMQ (`docker compose up -d postgres rabbitmq`)
2. MCP 서버 (`mcp-server/README.md`), RAG MCP 서버 (`rag-mcp/README.md`)
3. Agent (`agent/README.md`, `--port 8001`)
4. Backend
5. Frontend (`cd frontend && npm install && npm run dev`)
