package com.lab.camel.route;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;


@Component
public class OrderKafkaRoute extends RouteBuilder {
    // En haut de la classe OrderKafkaRoute :
    @org.springframework.beans.factory.annotation.Value("${app.processing-delay-ms:0}")
    private long processingDelayMs;
    @Override
    public void configure() {

        // 1. Stratégie DLQ : 3 tentatives espacées d'1 seconde, puis routage vers direct:orders-dlq
        errorHandler(deadLetterChannel("direct:orders-dlq")
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .logExhausted(true));

        // 2. Route principale : consommation du topic Kafka orders.process
        from("kafka:orders.process?groupId=local-processor&autoOffsetReset=earliest")
                .routeId("kafka-orders-process-route")
                .process(exchange -> {
                    String correlationId = exchange.getIn().getHeader("CorrelationID", String.class);
                    if (correlationId == null || correlationId.isBlank()) {
                        correlationId = UUID.randomUUID().toString();
                        exchange.getIn().setHeader("CorrelationID", correlationId);
                    }
                    MDC.put("CorrelationID", correlationId);
                })
                .log("Traitement Kafka démarré | Partition=${header['kafka.PARTITION']} | Offset=${header['kafka.OFFSET']} | Payload=${body}")
                .process(exchange -> {
                    if (processingDelayMs > 0) {
                        Thread.sleep(processingDelayMs);
                    }
                    String body = exchange.getIn().getBody(String.class);
                    if (body != null && body.contains("FAIL")) {
                        throw new IllegalStateException("Erreur simulée sur le message contenant FAIL");
                    }
                })
                .log("Commande traitée avec succès | Payload=${body}")
                .process(exchange -> MDC.remove("CorrelationID"));

        // 3. Sous-route DLQ : enrichit l'erreur, loggue pour Kibana et publie dans orders.dlq
        from("direct:orders-dlq")
                .routeId("kafka-orders-dlq-route")
                .process(exchange -> {
                    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    String errorMsg = (cause != null) ? cause.getMessage() : "Erreur inconnue";
                    exchange.getIn().setHeader("ErrorMessage", errorMsg);
                    MDC.put("dlq", "true");
                })
                .log(LoggingLevel.ERROR, "Message envoyé en DLQ | Erreur=${header.ErrorMessage} | Payload=${body}")
                .to("kafka:orders.dlq")
                .process(exchange -> {
                    MDC.remove("dlq");
                    MDC.remove("CorrelationID");
                });
    }
}