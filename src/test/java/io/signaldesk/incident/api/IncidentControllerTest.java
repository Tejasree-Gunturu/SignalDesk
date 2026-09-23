package io.signaldesk.incident.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IncidentControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsIdempotentlyAndRecordsAnAuditTrail() throws Exception {
        String body = """
                {"title":"Checkout API returns 5xx","serviceName":"checkout-api","description":"5xx rate above threshold","severity":"CRITICAL","reporter":"maya"}
                """;

        String first = mvc.perform(
                post("/api/v1/incidents")
                        .header("X-Tenant-Id", "acme")
                        .header("Idempotency-Key", "checkout-5xx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assignedTo").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mvc.perform(
                post("/api/v1/incidents")
                        .header("X-Tenant-Id", "acme")
                        .header("Idempotency-Key", "checkout-5xx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(second).get("id").asText())
                .isEqualTo(objectMapper.readTree(first).get("id").asText());

        String id = objectMapper.readTree(first).get("id").asText();

        mvc.perform(
                post("/api/v1/incidents/{id}/acknowledge", id)
                        .header("X-Tenant-Id", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"maya\"}")
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        mvc.perform(
                get("/api/v1/incidents/{id}/audit", id)
                        .header("X-Tenant-Id", "acme")
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("INCIDENT_CREATED"))
                .andExpect(jsonPath("$[1].action").value("INCIDENT_ACKNOWLEDGED"));
    }

    @Test
    void rejectsInvalidLifecycleTransition() throws Exception {
        mvc.perform(
                post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Memory leak\",\"serviceName\":\"api\",\"description\":\"Leak\",\"severity\":\"HIGH\",\"reporter\":\"alex\"}")
        )
                .andExpect(status().isCreated())
                .andDo(result -> {
                    JsonNode node = objectMapper.readTree(
                            result.getResponse().getContentAsString()
                    );

                    mvc.perform(
                            post(
                                    "/api/v1/incidents/{id}/close",
                                    node.get("id").asText()
                            )
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"actor\":\"alex\"}")
                    )
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.message")
                                    .value("Only a resolved incident can be closed"));
                });
    }
}