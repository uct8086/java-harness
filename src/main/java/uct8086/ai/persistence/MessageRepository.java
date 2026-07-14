package uct8086.ai.persistence;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * Spring Data JDBC repository for {@link MessageEntity}.
 */
public interface MessageRepository extends ListCrudRepository<MessageEntity, Long> {

    List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    long deleteBySessionId(String sessionId);
}
