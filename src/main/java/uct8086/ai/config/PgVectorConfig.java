package uct8086.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import uct8086.ai.vectorstore.PgVectorEmbeddingStore;

@Configuration
public class PgVectorConfig {

    @Value("${pgvector.datasource.url}")
    private String url;

    @Value("${pgvector.datasource.username}")
    private String username;

    @Value("${pgvector.datasource.password}")
    private String password;

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public PgVectorEmbeddingStore pgVectorEmbeddingStore(EmbeddingModel embeddingModel) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        return new PgVectorEmbeddingStore(new JdbcTemplate(ds), embeddingModel);
    }
}
