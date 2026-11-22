package com.lab.camel.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RagConfig {

    @Bean
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.embedding-model-name}") String embeddingModelName) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public QdrantClient qdrantClient(
            @Value("${qdrant.host}") String host,
            @Value("${qdrant.port}") int port) {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build()
        );
    }

    @Bean
    public EmbeddingStore<TextSegment> qdrantEmbeddingStore(
            QdrantClient qdrantClient,
            @Value("${qdrant.collection-name}") String collectionName) {
        return QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(collectionName)
                .build();
    }
}