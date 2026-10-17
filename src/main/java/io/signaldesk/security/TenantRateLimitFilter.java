package io.signaldesk.security;

import io.signaldesk.redis.RedisControls;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class TenantRateLimitFilter extends OncePerRequestFilter {
    private final RedisControls redis;
    public TenantRateLimitFilter(RedisControls redis) { this.redis = redis; }
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (req.getRequestURI().startsWith("/api/") && authentication != null
                && authentication.getPrincipal() instanceof ApiPrincipal principal) {
            String header = req.getHeader("X-Tenant-Id");
            if (header != null && !principal.tenantId().equals(header.trim())) {
                reject(res, 403, "Tenant header does not match authenticated tenant"); return;
            }
            if (!redis.allow(principal.tenantId(), principal.username())) {
                res.setHeader("Retry-After", "60");
                reject(res, 429, "Rate limit exceeded"); return;
            }
        }
        chain.doFilter(req, res);
    }
    private void reject(HttpServletResponse res, int code, String message) throws IOException {
        res.setStatus(code); res.setContentType("application/json");
        res.getWriter().write("{\"status\":" + code + ",\"message\":\"" + message + "\"}");
    }
}
