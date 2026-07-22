package uct8086.ai.config;

import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * Spring configuration that wires Qoder token-exchange authentication
 * into the Spring AI OpenAI HTTP client.
 * <p>
 * When {@code qoder.auth.enabled=true}, this configuration registers an
 * {@link OpenAiHttpClientBuilderCustomizer} that adds an OkHttp interceptor.
 * The interceptor dynamically injects the Bearer token obtained from
 * {@link QoderTokenService} into every outgoing model-call request.
 * <p>
 * <b>Prerequisite:</b> Set {@code spring.ai.openai.api-key=} (empty) in
 * application.yml so that Spring AI enters no-auth mode, which strips the
 * default {@code Authorization} header. This interceptor then adds the
 * Qoder Bearer token in its place.
 */
@Configuration
@EnableConfigurationProperties(QoderAuthProperties.class)
@ConditionalOnProperty(name = "qoder.auth.enabled", havingValue = "true", matchIfMissing = true)
public class QoderChatConfig {

    private static final Logger log = LoggerFactory.getLogger(QoderChatConfig.class);

    private final QoderTokenService tokenService;

    public QoderChatConfig(QoderTokenService tokenService) {
        this.tokenService = tokenService;
        log.info("Qoder token-exchange authentication enabled for Spring AI OpenAI client.");
    }

    /**
     * Registers an OkHttp interceptor that:
     * <ol>
     *   <li>Removes the default {@code Authorization} header (no-auth mode safety)</li>
     *   <li>Retrieves a fresh Bearer token from {@link QoderTokenService}</li>
     *   <li>Injects {@code Authorization: Bearer <qoder-token>} into the request</li>
     * </ol>
     * <p>
     * If the downstream server returns 401 Unauthorized, the token is
     * invalidated and the request is retried once with a freshly acquired token.
     */
    @Bean
    public OpenAiHttpClientBuilderCustomizer qoderAuthCustomizer() {
        return builder -> builder.interceptor(chain -> {
            Request original = chain.request();

            // 1. Strip any pre-existing Authorization header
            Request authenticatedRequest = original.newBuilder()
                    .removeHeader("Authorization")
                    .header("Authorization", "Bearer " + tokenService.getToken())
                    .build();

            // 2. Execute the request
            Response response = chain.proceed(authenticatedRequest);

            // 3. On 401, invalidate cached token and retry once
            if (response.code() == 401) {
                log.warn("Qoder returned 401 — token may be expired, refreshing and retrying...");
                response.close();
                tokenService.invalidate();

                Request retryRequest = original.newBuilder()
                        .removeHeader("Authorization")
                        .header("Authorization", "Bearer " + tokenService.getToken())
                        .build();

                return chain.proceed(retryRequest);
            }

            return response;
        });
    }
}
