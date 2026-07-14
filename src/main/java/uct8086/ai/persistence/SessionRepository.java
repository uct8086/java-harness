package uct8086.ai.persistence;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

/**
 * Spring Data JDBC repository for {@link SessionEntity}.
 */
public interface SessionRepository extends ListCrudRepository<SessionEntity, String> {

    List<SessionEntity> findAllByOrderByUpdatedAtDesc();
}
