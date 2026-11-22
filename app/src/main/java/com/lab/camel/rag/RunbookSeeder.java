package com.lab.camel.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RunbookSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunbookSeeder.class);

    private record Runbook(String id, String content) {}

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String collectionName;

    public RunbookSeeder(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${qdrant.collection-name}") String collectionName) {
        this.qdrantClient = qdrantClient;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.collectionName = collectionName;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var runbooks = List.of(
                    new Runbook("RB-ORD-001", """
                    [RUNBOOK RB-ORD-001] Erreur simulée ou article contenant FAIL dans orders.process.
                    Cause métier : le système ERP en amont envoie la valeur sentinelle "FAIL" lorsqu'une référence produit est désactivée.
                    Action obligatoire : ouvrir un incident tagué INC-ERP-REF, remplacer "item": "FAIL" par "item": "MANUAL_REVIEW" dans le payload corrigé, puis rejouer dans orders.process.
                    Sévérité standard : MEDIUM.
                    """),
                    new Runbook("RB-KFK-002", """
                    [RUNBOOK RB-KFK-002] Échec de désérialisation JSON ou payload vide sur Kafka.
                    Cause métier : contrat de schéma non respecté par le producteur ActiveMQ.
                    Action obligatoire : vérifier le schéma enregistré dans Apicurio Registry et rejeter le message vers la file d'audit sans rejeu automatique.
                    Sévérité standard : HIGH.
                    """),
                    new Runbook("RB-NET-003", """
                    [RUNBOOK RB-NET-003] Timeout réseau ou indisponibilité temporaire d'un service aval.
                    Cause métier : micro-coupure entre namespaces Kubernetes ou redémarrage de pod.
                    Action obligatoire : vérifier les métriques Prometheus et les traces Tempo, puis rejouer le message tel quel après 5 minutes.
                    Sévérité standard : LOW.
                    """)
            );

            List<String> ids = new ArrayList<>();
            List<Embedding> embeddings = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();

            for (Runbook runbook : runbooks) {
                TextSegment segment = TextSegment.from(runbook.content().trim());
                Embedding embedding = embeddingModel.embed(segment).content();
                ids.add(UUID.nameUUIDFromBytes(runbook.id().getBytes(StandardCharsets.UTF_8)).toString());
                embeddings.add(embedding);
                segments.add(segment);
            }

            boolean exists = Boolean.TRUE.equals(qdrantClient.collectionExistsAsync(collectionName).get());
            if (!exists) {
                int vectorSize = embeddings.getFirst().dimension();
                qdrantClient.createCollectionAsync(
                        collectionName,
                        VectorParams.newBuilder()
                                .setSize(vectorSize)
                                .setDistance(Distance.Cosine)
                                .build()
                ).get();
                log.info("Collection Qdrant '{}' créée (dimension={})", collectionName, vectorSize);
            }

            embeddingStore.addAll(ids, embeddings, segments);
            log.info("Base RAG Qdrant synchronisée avec {} runbooks SRE dans '{}'", segments.size(), collectionName);
        } catch (Exception e) {
            log.warn("Initialisation RAG Qdrant ignorée au démarrage (Ollama/Qdrant indisponible) : {}", e.getMessage());
        }
    }
}