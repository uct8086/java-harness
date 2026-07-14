package uct8086.ai.vectorstore;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Standalone pgvector embedding store backed by raw JDBC.
 * <p>No dependency on the (absent) Spring AI vector‑store module.
 */
public class PgVectorEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorEmbeddingStore.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public PgVectorEmbeddingStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        ensureTable();
    }

    /** Ingest a document (text + optional metadata). */
    public void add(String content, Map<String, Object> metadata) {
        float[] emb = embed(content);
        if (emb == null) return;
        jdbc.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?)",
                UUID.randomUUID().toString(), content, toJson(metadata), toVector(emb));
        log.debug("Ingested document ({} chars)", content.length());
    }

    /** Ingest a Spring AI {@link Document}. */
    public void add(Document doc) {
        add(doc.getText(), doc.getMetadata() != null ? doc.getMetadata() : Map.of());
    }

    /** Ingest a batch. */
    public void addAll(List<Document> docs) {
        docs.forEach(this::add);
    }

    /** Semantic search via pgvector {@code <=>} (cosine distance). */
    public List<Document> search(String query, int topK) {
        float[] emb = embed(query);
        if (emb == null) return Collections.emptyList();
        try {
            return jdbc.query(
                    "SELECT content, metadata FROM vector_store ORDER BY embedding <=> ?::vector LIMIT ?",
                    ps -> { ps.setObject(1, toVector(emb)); ps.setInt(2, topK); },
                    (rs, rowNum) -> toDocument(rs));
        } catch (Exception e) {
            log.error("Vector search failed", e);
            return Collections.emptyList();
        }
    }

    // ───── private helpers ────────────────────────────

    private float[] embed(String text) {
        try {
            EmbeddingResponse resp = embeddingModel.call(new EmbeddingRequest(List.of(text), null));
            if (resp.getResults() != null && !resp.getResults().isEmpty()) {
                float[] out = resp.getResults().getFirst().getOutput();
                float[] f = new float[out.length];
                for (int i = 0; i < out.length; i++) f[i] = out[i];
                return f;
            }
        } catch (Exception e) {
            log.warn("Embedding failed ({} chars)", text.length(), e);
        }
        return null;
    }

    private void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vector_store (
                    id       UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    content  TEXT,
                    metadata JSONB DEFAULT '{}',
                    embedding vector
                )""");
        log.info("pgvector vector_store table ready");
    }

    private static PGobject toVector(float[] vec) {
        try {
            PGobject pg = new PGobject();
            pg.setType("vector");
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vec.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(vec[i]);
            }
            sb.append("]");
            pg.setValue(sb.toString());
            return pg;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create vector PGobject", e);
        }
    }

    private static String toJson(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : meta.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(esc(e.getKey())).append("\":\"")
              .append(esc(String.valueOf(e.getValue()))).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Document toDocument(ResultSet rs) throws SQLException {
        String content = rs.getString("content");
        String metaStr = rs.getString("metadata");
        Map<String, Object> meta = new HashMap<>();
        if (metaStr != null && !metaStr.isEmpty() && !"{}".equals(metaStr)) {
            try {
                String inner = metaStr.substring(1, metaStr.length() - 1);
                for (String pair : inner.split(",")) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        meta.put(kv[0].trim().replaceAll("^\"|\"$", ""),
                                 kv[1].trim().replaceAll("^\"|\"$", ""));
                    }
                }
            } catch (Exception ignored) {}
        }
        return new Document(content, meta);
    }
}
