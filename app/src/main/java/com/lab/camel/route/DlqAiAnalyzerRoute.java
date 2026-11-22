package com.lab.camel.route;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class DlqAiAnalyzerRoute extends RouteBuilder {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public DlqAiAnalyzerRoute(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public void configure() {
        from("kafka:orders.dlq?groupId=dlq-ai-analyzer&autoOffsetReset=earliest")
                .routeId("kafka-dlq-ai-analyzer-route")
                .process(exchange -> {
                    String correlationId = exchange.getIn().getHeader("CorrelationID", String.class);
                    String errorMessage = exchange.getIn().getHeader("ErrorMessage", String.class);
                    String failedPayload = exchange.getIn().getBody(String.class);

                    if (correlationId != null && !correlationId.isBlank()) {
                        MDC.put("CorrelationID", correlationId);
                    }
                    MDC.put("ai_analysis", "true");

                    String safeError = errorMessage != null ? errorMessage : "Inconnue";
                    String safePayload = failedPayload != null ? failedPayload : "Vide";

                    String ragContext = retrieveRunbookContext(safeError + " | Payload: " + safePayload);
                    exchange.getIn().setHeader("RagContextFound", !ragContext.isBlank());

                    String prompt = """
                    Tu es un ingénieur SRE spécialisé en intégration Kafka et Apache Camel.
                    Un message a échoué et est arrivé en Dead Letter Queue (DLQ).
                    Appuie-toi PRIORITAIREMENT sur les runbooks internes ci-dessous s'ils correspondent à l'incident.

                    --- RUNBOOKS INTERNES (RAG QDRANT) ---
                    %s
                    --------------------------------------

                    Réponds UNIQUEMENT par un objet JSON valide (sans texte autour, sans balises markdown) avec ces 5 clés :
                    - "runbookRef": l'identifiant du runbook appliqué (ex: "RB-ORD-001") ou "NONE"
                    - "cause": explication courte de la panne en français
                    - "severity": LOW, MEDIUM ou HIGH
                    - "suggestedAction": action corrective concrète en reprenant les consignes du runbook
                    - "correctedPayload": proposition du payload corrigé selon le runbook

                    Erreur : %s
                    Payload en échec : %s
                    """.formatted(
                            ragContext.isBlank() ? "Aucun runbook correspondant trouvé." : ragContext,
                            safeError,
                            safePayload
                    );

                    exchange.getIn().setBody(prompt);
                })
                .log("Contexte RAG récupéré (match=${header.RagContextFound}), envoi à Ollama pour analyse...")
                .to("langchain4j-chat:dlq-analyzer?chatOperation=CHAT_SINGLE_MESSAGE")
                .log("Analyse IA + RAG terminée | Resultat=${body}")
                .to("kafka:orders.dlq.analyzed")
                .process(exchange -> {
                    MDC.remove("ai_analysis");
                    MDC.remove("CorrelationID");
                });
    }

    private String retrieveRunbookContext(String query) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(2)
                    .minScore(0.45)
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
            return matches.stream()
                    .map(m -> String.format("(Score %.2f) %s", m.score(), m.embedded().text()))
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.warn("Recherche RAG Qdrant en échec, bascule sans contexte : {}", e.getMessage());
            return "";
        }
    }
}