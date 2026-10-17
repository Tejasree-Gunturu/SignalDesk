package io.signaldesk.incident.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signaldesk.incident.application.IncidentService;
import io.signaldesk.incident.domain.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class IncidentControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired IncidentService service;
    @Autowired EntityManagerFactory emf;
    private static final String BODY = """
            {"title":"Checkout 5xx","serviceName":"checkout","description":"Error rate above 10%","severity":"CRITICAL","reporter":"spoofed"}
            """;
    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test-admin");
    }
    private String create() throws Exception {
        return mapper.readTree(mvc.perform(authenticated(post("/api/v1/incidents")).contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
    @Test void createsIdempotentlyAndRecordsAnAuditTrail() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(authenticated(post("/api/v1/incidents")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(authenticated(post("/api/v1/incidents")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(first).get("id").asText();
        assertThat(mapper.readTree(second).get("id").asText()).isEqualTo(id);
        mvc.perform(authenticated(post("/api/v1/incidents/" + id + "/acknowledge"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"spoofed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.assignedTo").value("maya"));
        mvc.perform(authenticated(get("/api/v1/incidents/" + id + "/audit")))
                .andExpect(jsonPath("$[0].actor").value("maya"))
                .andExpect(jsonPath("$[1].action").value("INCIDENT_ACKNOWLEDGED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, UUID.fromString(id))).isEqualTo(2);
    }
    @Test void differentPayloadForSameKeyIsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(authenticated(post("/api/v1/incidents")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        mvc.perform(authenticated(post("/api/v1/incidents")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("Checkout 5xx", "Different")))
                .andExpect(status().isConflict());
    }
    @Test void fullLifecycleAndInvalidTransition() throws Exception {
        String id = create();
        mvc.perform(authenticated(post("/api/v1/incidents/" + id + "/close")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\":\"maya\"}")).andExpect(status().isConflict());
        for (String action : List.of("acknowledge", "resolve", "close")) {
            mvc.perform(authenticated(post("/api/v1/incidents/" + id + "/" + action)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"actor\":\"maya\"}")).andExpect(status().isOk());
        }
        mvc.perform(authenticated(get("/api/v1/incidents/" + id))).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE incident_id=?", Integer.class, UUID.fromString(id))).isEqualTo(4);
    }
    @Test void requiresTokenAndEnforcesTenantAndRole() throws Exception {
        String id = create();
        mvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/incidents").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/incidents/" + id).header("Authorization", "Bearer test-beta")).andExpect(status().isNotFound());
        mvc.perform(authenticated(get("/api/v1/incidents")).header("X-Tenant-Id", "beta")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/incidents").header("Authorization", "Bearer test-viewer")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/incidents/" + id).header("Authorization", "Bearer test-viewer")).andExpect(status().isOk());
    }
    @Test void validatesBodyPaginationAndHeaders() throws Exception {
        mvc.perform(authenticated(post("/api/v1/incidents")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(authenticated(get("/api/v1/incidents?size=101"))).andExpect(status().isBadRequest());
        mvc.perform(authenticated(post("/api/v1/incidents")).header("Idempotency-Key", "a".repeat(201))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
    }
    @Test void filtersAndReturnsNotFound() throws Exception {
        create();
        mvc.perform(authenticated(get("/api/v1/incidents?severity=CRITICAL&status=OPEN&size=1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"));
        mvc.perform(authenticated(get("/api/v1/incidents/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }
    @Test void concurrentCreateHasOneIncidentOneEventAndOneAudit() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = new CreateIncidentRequest("Concurrent", "api", "Failure", Severity.HIGH, "maya", null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<UUID> task = () -> { start.await(); return service.create("acme", key, request).id(); };
            var a = executor.submit(task); var b = executor.submit(task); start.countDown();
            UUID id = a.get(20, TimeUnit.SECONDS);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE incident_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, id)).isEqualTo(1);
        }
    }
    @Test void optimisticLockRejectsAStaleWrite() throws Exception {
        UUID id = UUID.fromString(create());
        try (var first = emf.createEntityManager(); var second = emf.createEntityManager()) {
            first.getTransaction().begin(); second.getTransaction().begin();
            Incident a = first.find(Incident.class, id); Incident b = second.find(Incident.class, id);
            a.acknowledge("maya", Instant.now()); first.getTransaction().commit();
            b.resolve(Instant.now());
            assertThatThrownBy(() -> second.getTransaction().commit()).isInstanceOf(jakarta.persistence.RollbackException.class);
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
        }
    }
}
