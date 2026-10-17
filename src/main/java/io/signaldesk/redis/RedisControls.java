package io.signaldesk.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.signaldesk.incident.api.IncidentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Expendable fast state: cache and rate limiter fail open; lock failure skips scheduled work. */
@Component
public class RedisControls {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final int requestsPerMinute;
    public RedisControls(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry metrics,
                         @Value("${signaldesk.redis.enabled:true}") boolean enabled,
                         @Value("${signaldesk.redis.requests-per-minute:120}") int requestsPerMinute) {
        this.redis = redis; this.mapper = mapper; this.metrics = metrics;
        this.enabled = enabled; this.requestsPerMinute = requestsPerMinute;
    }
    public boolean allow(String tenant, String user) {
        if (!enabled) return true;
        try { Long n = redis.execute(LIMIT, List.of("sd:rate:" + part(tenant) + ":" + part(user)));
            return n == null || n <= requestsPerMinute;
        } catch (RuntimeException e) { unavailable(); return true; }
    }
    public Optional<IncidentResponse> get(String tenant, UUID id, long version) {
        if (!enabled) return Optional.empty();
        try {
            String json = redis.opsForValue().get(key(tenant, id, version));
            metrics.counter(json == null ? "signaldesk.cache.miss" : "signaldesk.cache.hit").increment();
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, IncidentResponse.class));
        } catch (Exception e) { unavailable(); return Optional.empty(); }
    }
    public void put(IncidentResponse incident) {
        if (!enabled) return;
        try { redis.opsForValue().set(key(incident.tenantId(), incident.id(), incident.version()),
                    mapper.writeValueAsString(incident), Duration.ofSeconds(60));
        } catch (Exception e) { unavailable(); }
    }
    public void invalidate(String tenant, UUID id, long version) {
        if (!enabled) return;
        try { redis.delete(key(tenant, id, version)); } catch (RuntimeException e) { unavailable(); }
    }
    public Optional<String> lock(String name) {
        if (!enabled) return Optional.of("disabled");
        String token = UUID.randomUUID().toString();
        try { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("sd:lock:" + name, token, Duration.ofSeconds(30)))
                    ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException e) { unavailable(); return Optional.empty(); }
    }
    public void unlock(String name, String token) {
        if (!enabled) return;
        try { redis.execute(RELEASE, List.of("sd:lock:" + name), token); }
        catch (RuntimeException e) { unavailable(); }
    }
    private String key(String tenant, UUID id, long version) { return "sd:incident:" + part(tenant) + ":" + id + ":v" + version; }
    private String part(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private void unavailable() { metrics.counter("signaldesk.redis.failure").increment(); }
}
