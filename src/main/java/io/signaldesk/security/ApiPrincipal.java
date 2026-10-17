package io.signaldesk.security;

public record ApiPrincipal(String username, String tenantId, String role) { }
