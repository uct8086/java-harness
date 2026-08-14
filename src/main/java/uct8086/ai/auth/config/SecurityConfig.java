package uct8086.ai.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration.
 *
 * <p>Login/logout are public; everything else under {@code /api/**} requires
 * authentication (carried via the auth_token cookie). Stateless (no HTTP session).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthTokenFilter authTokenFilter;

    public SecurityConfig(AuthTokenFilter authTokenFilter) {
        this.authTokenFilter = authTokenFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public auth endpoints
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                // Health check (for K8s probes)
                .requestMatchers("/actuator/health").permitAll()
                // SSE streaming endpoint: auth is handled by AuthTokenFilter (cookie-based)
                // and CurrentUser.requireId() at controller entry. The async dispatch back
                // from SseEmitter would otherwise hit AuthorizationFilter with an empty
                // SecurityContext (STATELESS), causing "Access Denied".
                .requestMatchers("/api/chat/stream").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS: allow the frontend origin (Vite dev + same-origin prod).
     * Cookies need allowCredentials + explicit origins (not "*").
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
