package uct8086.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the RAG (Retrieval-Augmented Generation) subsystem.
 *
 * <p>Supports:
 * <ul>
 *   <li>In-memory vector store (dev/test)</li>
 *   <li>PGVector (PostgreSQL, production)</li>
 *   <li>Chroma (lightweight vector DB)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "uct8086.ai.rag")
public class RagProperties {

    /** Enable/disable RAG auto-retrieval (context injection into every prompt) */
    private boolean enabled = true;

    /** Number of top-k documents to retrieve per query */
    private int topK = 5;

    /** Minimum similarity threshold (0.0 - 1.0) for retrieval */
    private double similarityThreshold = 0.7;

    /** Vector store type: simple, pgvector, chroma */
    private String vectorStore = "simple";

    /** Embedding model to use (defaults to main model if not set) */
    private String embeddingModel;

    /** Maximum chunk size for document splitting (characters) */
    private int chunkSize = 1000;

    /** Overlap between chunks (characters) */
    private int chunkOverlap = 200;

    /** Directory for local knowledge base files */
    private String knowledgeBaseDir = "knowledge-base";

    /** Auto-ingest .md / .txt files from knowledge-base dir on startup */
    private boolean autoIngestOnStartup = true;

    // ===== Getters & Setters =====

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

    public String getVectorStore() { return vectorStore; }
    public void setVectorStore(String vectorStore) { this.vectorStore = vectorStore; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public String getKnowledgeBaseDir() { return knowledgeBaseDir; }
    public void setKnowledgeBaseDir(String knowledgeBaseDir) { this.knowledgeBaseDir = knowledgeBaseDir; }

    public boolean isAutoIngestOnStartup() { return autoIngestOnStartup; }
    public void setAutoIngestOnStartup(boolean autoIngestOnStartup) { this.autoIngestOnStartup = autoIngestOnStartup; }
}
