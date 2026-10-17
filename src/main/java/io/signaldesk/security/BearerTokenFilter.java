package io.signaldesk.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Local/static service tokens. Replace account configuration with an OIDC issuer for production. */
public class BearerTokenFilter extends OncePerRequestFilter {
    private final TokenProperties properties;
    public BearerTokenFilter(TokenProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
            for (var account : properties.accounts()) {
                if (account.token() != null && !account.token().isBlank()
                        && MessageDigest.isEqual(supplied, account.token().getBytes(StandardCharsets.UTF_8))) {
                    var principal = new ApiPrincipal(account.username(), account.tenantId(), account.role());
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
