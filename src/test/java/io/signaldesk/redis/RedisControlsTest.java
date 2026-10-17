package io.signaldesk.redis;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisControlsTest {
    @Test void redisOutageAllowsRequestsButDoesNotGrantWorkerLock() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("offline"));
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        var controls = new RedisControls(redis, JsonMapper.builder().findAndAddModules().build(), new SimpleMeterRegistry(), true, 2);
        assertThat(controls.allow("acme", "maya")).isTrue();
        assertThat(controls.get("acme", UUID.randomUUID(), 0)).isEmpty();
        assertThat(controls.lock("sla-scan")).isEmpty();
    }
    @Test void limiterRejectsOverBudget() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(1L, 2L, 3L);
        var controls = new RedisControls(redis, JsonMapper.builder().build(), new SimpleMeterRegistry(), true, 2);
        assertThat(controls.allow("acme", "maya")).isTrue();
        assertThat(controls.allow("acme", "maya")).isTrue();
        assertThat(controls.allow("acme", "maya")).isFalse();
    }
}
