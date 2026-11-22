package com.lab.camel.route;

import com.lab.camel.contract.OrderContractService;
import com.lab.camel.model.Order;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockComponent;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OrderKafkaRouteTest {

    private CamelContext context;
    private ProducerTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        var contractService = new OrderContractService(
                JsonMapper.builder().build(),
                "http://localhost:8080/apis/ccompat/v7",
                "orders.process-value"
        );

        context = new DefaultCamelContext();
        context.setAutowiredEnabled(false);
        context.addComponent("kafka", new MockComponent());
        context.addRoutes(new OrderKafkaRoute(contractService));

        AdviceWith.adviceWith(context, "kafka-orders-process-route", route -> {
            route.replaceFromWith("direct:orders-process");
            route.weaveAddLast().to("mock:orders-validated");
        });

        context.start();
        template = context.createProducerTemplate();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
    }

    @Test
    void validOrderShouldBeValidatedAndNotSentToDlq() throws Exception {
        MockEndpoint validatedMock = context.getEndpoint("mock:orders-validated", MockEndpoint.class);
        MockEndpoint dlqMock = context.getEndpoint("kafka:orders.dlq", MockEndpoint.class);

        validatedMock.expectedMessageCount(1);
        validatedMock.expectedHeaderReceived("X-Schema-Subject", "orders.process-value");
        dlqMock.expectedMessageCount(0);

        template.sendBody("direct:orders-process", """
            {"orderId": 42, "item": "Laptop", "qty": 2}
            """);

        validatedMock.assertIsSatisfied();
        dlqMock.assertIsSatisfied();
        Order parsed = validatedMock.getExchanges().getFirst().getProperty("parsedOrder", Order.class);
        assertThat(parsed).isEqualTo(new Order(42L, "Laptop", 2));
    }

    @Test
    void schemaViolationShouldRouteDirectlyToDlqWithoutRetry() throws Exception {
        MockEndpoint validatedMock = context.getEndpoint("mock:orders-validated", MockEndpoint.class);
        MockEndpoint dlqMock = context.getEndpoint("kafka:orders.dlq", MockEndpoint.class);

        validatedMock.expectedMessageCount(0);
        dlqMock.expectedMessageCount(1);
        dlqMock.expectedHeaderReceived("SchemaViolation", "true");

        template.sendBody("direct:orders-process", """
            {"orderId": "BAD", "qty": -5}
            """);

        validatedMock.assertIsSatisfied();
        dlqMock.assertIsSatisfied();
        String errorMessage = dlqMock.getExchanges().getFirst().getIn().getHeader("ErrorMessage", String.class);
        assertThat(errorMessage).contains("Violation du contrat JSON Schema");
    }

    @Test
    void failItemShouldRetryAndRouteToDlq() throws Exception {
        MockEndpoint validatedMock = context.getEndpoint("mock:orders-validated", MockEndpoint.class);
        MockEndpoint dlqMock = context.getEndpoint("kafka:orders.dlq", MockEndpoint.class);

        validatedMock.expectedMessageCount(0);
        dlqMock.expectedMessageCount(1);

        template.sendBody("direct:orders-process", """
            {"orderId": 99, "item": "FAIL", "qty": 1}
            """);

        validatedMock.assertIsSatisfied();
        dlqMock.assertIsSatisfied();
        var dlqIn = dlqMock.getExchanges().getFirst().getIn();
        assertThat(dlqIn.getHeader("ErrorMessage", String.class)).contains("FAIL");
        assertThat(dlqIn.getHeader("SchemaViolation")).isNull();
    }
}