package io.signaldesk.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "signaldesk.auth")
public record TokenProperties(List<Account> accounts) {
    public record Account(String username, String tenantId, String role, String token) { }
}
