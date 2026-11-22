package com.lab.camel.route;

import com.lab.camel.remediation.AiRemediationDecision;
import com.lab.camel.remediation.AiRemediationGuard;
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
    private final AiRemediationGuard remediationGuard;

    public DlqAiAnalyzerRoute(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            AiRemediationGuard remediationGuard) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.remediationGuard = remediationGuard;
    }

    @Override
    public void configure() {
        from("kafka:orders.dlq?groupId=dlq-ai-analyzer&autoOffsetReset=earliest")
                .routeId("kafka-dlq-ai-analyzer-route")
                .process(exchange -> {
                    String correlationId = exchange.getIn().getHeader("CorrelationID", String.class);
                    String errorMessage = exchange.getIn().getHeader("ErrorMessage", String.class);
                    String failedPayload = exchange.getIn().getBody(String.class);
                    int retryCount = parseRetryCount(exchange.getIn().getHeader("aiRetryCount", String.class));

                    if (correlationId != null && !correlationId.isBlank()) {
                        MDC.put("CorrelationID", correlationId);
                    }
                    MDC.put("ai_analysis", "true");
                    exchange.setProperty("currentAiRetryCount", retryCount);

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

                    Réponds UNIQUEMENT par un objet JSON valide (sans texte autour, sans balises markdown) avec ces 6 clés :
                    - "runbookRef": l'identifiant du runbook appliqué (ex: "RB-ORD-001", "RB-KFK-002", "RB-NET-003") ou "NONE"
                    - "cause": explication courte de la panne en français
                    - "severity": LOW, MEDIUM ou HIGH
                    - "autoRemediate": true si le runbook demande explicitement un rejeu dans orders.process, false s'il demande un rejet sans rejeu automatique
                    - "suggestedAction": action corrective concrète en reprenant les consignes du runbook
                    - "correctedPayload": l'objet JSON corrigé conforme au schéma Order (orderId entier positif, item texte, qty entier optionnel) ou null si autoRemediate est false

                    Erreur : %s
                    Payload en échec : %s
                    """.formatted(
                            ragContext.isBlank() ? "Aucun runbook correspondant trouvé." : ragContext,
                            safeError,
                            safePayload
                    );

                    exchange.getIn().setBody(prompt);
                })
                .log("Contexte RAG récupéré (match=${header.RagContextFound}, aiRetryCount=${exchangeProperty.currentAiRetryCount}), envoi à Ollama...")
                .to("langchain4j-chat:dlq-analyzer?chatOperation=CHAT_SINGLE_MESSAGE")
                .log("Analyse IA + RAG terminée | Resultat=${body}")
                .to("kafka:orders.dlq.analyzed")
                .process(exchange -> {
                    String aiResult = exchange.getIn().getBody(String.class);
                    int currentRetryCount = exchange.getProperty("currentAiRetryCount", 0, Integer.class);
                    AiRemediationDecision decision = remediationGuard.evaluate(aiResult, currentRetryCount);

                    exchange.getIn().setHeader("RemediationStatus", decision.status());
                    exchange.getIn().setHeader("RemediationReason", decision.reason());
                    exchange.getIn().setHeader("X-Remediation-Runbook", decision.runbookRef());

                    if (decision.approved()) {
                        exchange.getIn().setBody(decision.correctedPayload());
                        exchange.getIn().setHeader("aiRetryCount", String.valueOf(decision.nextRetryCount()));
                        exchange.getIn().setHeader("X-Auto-Remediated", "true");
                    }
                })
                .choice()
                .when(header("RemediationStatus").isEqualTo("APPROVED"))
                .removeHeaders("kafka.*|ErrorMessage|SchemaViolation")
                .log("Auto-remédiation IA approuvée (${header.X-Remediation-Runbook}, retry=${header.aiRetryCount}), réinjection dans orders.process | Payload=${body}")
                .to("kafka:orders.process")
                .otherwise()
                .log("Auto-remédiation IA non déclenchée | status=${header.RemediationStatus} | raison=${header.RemediationReason}")
                .end()
                .process(exchange -> {
                    MDC.remove("ai_analysis");
                    MDC.remove("CorrelationID");
                });
    }

    private int parseRetryCount(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(headerValue.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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