package com.lab.camel.route;

import com.lab.camel.contract.OrderContractService;
import com.lab.camel.contract.SchemaValidationException;
import com.lab.camel.model.Order;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderKafkaRoute extends RouteBuilder {

    private final OrderContractService orderContractService;

    @Value("${app.processing-delay-ms:0}")
    private long processingDelayMs;

    public OrderKafkaRoute(OrderContractService orderContractService) {
        this.orderContractService = orderContractService;
    }

    @Override
    public void configure() {
        // Erreur de contrat de données : pas de retry, routage direct vers la DLQ
        onException(SchemaValidationException.class)
                .handled(true)
                .process(exchange -> {
                    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    String errorMsg = cause != null ? cause.getMessage() : "Violation du schéma JSON";
                    exchange.getIn().setHeader("ErrorMessage", errorMsg);
                    exchange.getIn().setHeader("SchemaViolation", "true");
                    MDC.put("dlq", "true");
                })
                .log("Contrat Apicurio violé, routage direct en DLQ | Raison=${header.ErrorMessage}")
                .to("kafka:orders.dlq")
                .process(exchange -> {
                    MDC.remove("dlq");
                    MDC.remove("CorrelationID");
                });

        // Erreurs métier/techniques : 3 tentatives avant envoi en DLQ
        errorHandler(deadLetterChannel("kafka:orders.dlq")
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .retryAttemptedLogLevel(org.apache.camel.LoggingLevel.WARN)
                .onPrepareFailure(exchange -> {
                    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    String errorMsg = cause != null ? cause.getMessage() : "Erreur inconnue";
                    exchange.getIn().setHeader("ErrorMessage", errorMsg);
                    MDC.put("dlq", "true");
                })
        );

        from("kafka:orders.process?groupId=local-processor&autoOffsetReset=earliest")
                .routeId("kafka-orders-process-route")
                .process(exchange -> {
                    String correlationId = exchange.getIn().getHeader("CorrelationID", String.class);
                    if (correlationId == null || correlationId.isBlank()) {
                        correlationId = UUID.randomUUID().toString();
                        exchange.getIn().setHeader("CorrelationID", correlationId);
                    }
                    MDC.put("CorrelationID", correlationId);

                    if (processingDelayMs > 0) {
                        Thread.sleep(processingDelayMs);
                    }

                    String rawBody = exchange.getIn().getBody(String.class);
                    Order order = orderContractService.validateAndParse(rawBody);

                    exchange.getIn().setHeader("X-Schema-Subject", orderContractService.getSubject());
                    exchange.getIn().setHeader("X-Schema-Id", orderContractService.getActiveSchemaId());
                    exchange.setProperty("parsedOrder", order);

                    if (order.item().contains("FAIL")) {
                        throw new IllegalStateException("Erreur simulée sur le message contenant FAIL");
                    }
                })
                .log("Commande validée (schemaId=${header.X-Schema-Id}, autoRemediated=${header.X-Auto-Remediated}, aiRetryCount=${header.aiRetryCount}) | orderId=${exchangeProperty.parsedOrder.orderId} | item=${exchangeProperty.parsedOrder.item} | qty=${exchangeProperty.parsedOrder.qty}")
                .process(exchange -> MDC.remove("CorrelationID"));
    }
}