package io.signaldesk.shared;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader("X-Correlation-Id");
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) id = UUID.randomUUID().toString();
        response.setHeader("X-Correlation-Id", id);
        try (var ignored = MDC.putCloseable("correlationId", id)) { chain.doFilter(request, response); }
    }
}
