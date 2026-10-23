package com.mcpcapstone.meettoticket.common;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonCodec json;

    private JsonNode document() throws Exception {
        return json.read(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void 공개_API와_세션인증을_문서화하고_내부_서버_API를_섞지_않는다() throws Exception {
        JsonNode doc = document();
        assertThat(doc.path("paths").size()).isEqualTo(25);
        for (var entry : doc.path("paths").properties()) {
            assertThat(entry.getKey()).startsWith("/api/");
            for (var operation : entry.getValue().properties()) {
                if (!java.util.Set.of("get", "post").contains(operation.getKey())) continue;
                assertThat(operation.getValue().path("summary").asText()).isNotBlank();
                for (JsonNode parameter : operation.getValue().path("parameters")) {
                    assertThat(parameter.path("name").asText()).isNotIn("userId", "loginUserId");
                }
            }
        }
        assertThat(doc.path("components").path("securitySchemes").path("sessionCookie")
                .path("name").asText()).isEqualTo("JSESSIONID");
        assertThat(doc.at("/paths/~1api~1auth~1login/post/security").size()).isZero();
        assertThat(doc.at("/paths/~1api~1auth~1me/get/security/0/sessionCookie").isArray()).isTrue();
        JsonNode projectSecurity = doc.at("/paths/~1api~1projects/get").has("security")
                ? doc.at("/paths/~1api~1projects/get/security") : doc.path("security");
        assertThat(projectSecurity.path(0).path("sessionCookie").isArray()).isTrue();
        assertThat(doc.at("/paths/~1api~1tickets~1publish/post/responses").has("202")).isTrue();
    }

    @Test
    void 파일업로드와_비동기접수_상태코드_및_집계를_제공한다() throws Exception {
        JsonNode doc = document();
        JsonNode upload = doc.at("/paths/~1api~1projects~1{projectId}~1meetings/post");
        assertThat(upload.path("requestBody").path("content").has("multipart/form-data")).isTrue();
        assertThat(upload.path("responses").has("201")).isTrue();
        JsonNode request = doc.at("/paths/~1api~1projects~1{projectId}~1analysis-tasks/post");
        assertThat(request.path("requestBody").path("content").has("multipart/form-data")).isTrue();
        assertThat(request.path("responses").has("202")).isTrue();
        assertThat(request.at("/responses/400").toString()).contains("INVALID_MEETING_INPUT");
        JsonNode start = doc.at("/paths/~1api~1meetings~1{meetingId}~1analysis-tasks/post");
        assertThat(start.path("responses").has("202")).isTrue();
        assertThat(start.path("responses").has("409")).isTrue();
        assertThat(start.path("responses").has("502")).isFalse();
        assertThat(start.path("description").asText()).contains("RabbitMQ", "2초·4초·8초", "DLQ");
        JsonNode items = doc.at("/paths/~1api~1analysis-tasks~1{taskId}~1items/get/responses");
        assertThat(items.has("409")).isTrue();
        assertThat(items.at("/200/content/application~1json/examples/mockApproved/value/summary/skipped").asInt()).isEqualTo(1);
        assertThat(items.at("/200/content/application~1json/examples/mockApproved/value/items/0/publishStatus").asText()).isEqualTo("SKIPPED");
        assertThat(doc.at("/components/schemas/Summary/properties").has("unknown")).isTrue();
        assertThat(doc.at("/components/schemas/MeetingResponse/properties").has("participantIds")).isTrue();
        assertThat(doc.at("/components/schemas/Item/properties/publishStatus/enum").toString()).contains("UNKNOWN", "SKIPPED");
        assertThat(doc.at("/components/schemas/Item/properties/errorMessage/type").toString()).contains("null");
    }
}
