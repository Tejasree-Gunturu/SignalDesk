package io.signaldesk.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import io.signaldesk.redis.RedisControls;

@Configuration
@EnableConfigurationProperties(TokenProperties.class)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, TokenProperties properties, RedisControls redis) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("VIEWER", "RESPONDER", "ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("RESPONDER", "ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401); res.setContentType("application/json");
                            res.getWriter().write("{\"status\":401,\"message\":\"Valid bearer token required\"}");
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403); res.setContentType("application/json");
                            res.getWriter().write("{\"status\":403,\"message\":\"Insufficient role\"}");
                        }))
                .addFilterBefore(new BearerTokenFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantRateLimitFilter(redis), BearerTokenFilter.class)
                .build();
    }
}
