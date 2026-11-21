package com.lab.camel.route;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderActiveMqRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("activemq6:queue:orders.in")
                .routeId("activemq-orders-in-route")
                .process(exchange -> {
                    String correlationId = exchange.getIn().getHeader("CorrelationID", String.class);
                    if (correlationId == null || correlationId.isBlank()) {
                        correlationId = UUID.randomUUID().toString();
                        exchange.getIn().setHeader("CorrelationID", correlationId);
                    }
                    // Clé Kafka basée sur le CorrelationID pour répartir les messages sur les 3 partitions
                    exchange.getIn().setHeader(KafkaConstants.KEY, correlationId);
                    MDC.put("CorrelationID", correlationId);
                })
                .log("Commande reçue depuis ActiveMQ, transfert vers Kafka | CorrelationID=${header.CorrelationID} | Payload=${body}")
                .to("kafka:orders.process")
                .process(exchange -> MDC.remove("CorrelationID"));
    }
}