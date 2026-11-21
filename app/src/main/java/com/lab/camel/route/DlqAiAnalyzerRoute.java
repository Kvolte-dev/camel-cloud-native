package com.lab.camel.route;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class DlqAiAnalyzerRoute extends RouteBuilder {

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

                    String prompt = """
                    Tu es un ingénieur SRE spécialisé en intégration Kafka et Apache Camel.
                    Un message a échoué et est arrivé en Dead Letter Queue (DLQ).
                    Analyse l'erreur et réponds UNIQUEMENT par un objet JSON valide (sans texte autour, sans balises markdown) avec ces 4 clés :
                    - "cause": explication courte de la panne en français
                    - "severity": LOW, MEDIUM ou HIGH
                    - "suggestedAction": action corrective concrète
                    - "correctedPayload": proposition du payload corrigé si possible

                    Erreur : %s
                    Payload en échec : %s
                    """.formatted(
                            errorMessage != null ? errorMessage : "Inconnue",
                            failedPayload != null ? failedPayload : "Vide"
                    );

                    exchange.getIn().setBody(prompt);
                })
                .log("Envoi du message DLQ à Ollama pour analyse...")
                .to("langchain4j-chat:dlq-analyzer?chatOperation=CHAT_SINGLE_MESSAGE")
                .log("Analyse IA terminée | Resultat=${body}")
                .to("kafka:orders.dlq.analyzed")
                .process(exchange -> {
                    MDC.remove("ai_analysis");
                    MDC.remove("CorrelationID");
                });
    }
}