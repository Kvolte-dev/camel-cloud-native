package com.lab.camel.contract;

import com.lab.camel.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderContractServiceTest {

    private OrderContractService service;

    @BeforeEach
    void setUp() {
        service = new OrderContractService(
                JsonMapper.builder().build(),
                "http://localhost:8080/apis/ccompat/v7",
                "orders.process-value"
        );
    }

    @Test
    void shouldValidateAndParseValidOrder() {
        Order order = service.validateAndParse("""
            {"orderId": 42, "item": "Clavier", "qty": 3}
            """);

        assertThat(order).isEqualTo(new Order(42L, "Clavier", 3));
    }

    @Test
    void shouldDefaultQuantityToOneWhenOmitted() {
        Order order = service.validateAndParse("""
            {"orderId": 7, "item": "Ecran"}
            """);

        assertThat(order.qty()).isEqualTo(1);
    }

    @Test
    void shouldRejectPayloadViolatingJsonSchema() {
        assertThatThrownBy(() -> service.validateAndParse("""
            {"orderId": "ABC", "qty": -5}
            """))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("Violation du contrat JSON Schema");
    }

    @Test
    void shouldRejectUnknownAdditionalProperty() {
        assertThatThrownBy(() -> service.validateAndParse("""
            {"orderId": 10, "item": "Souris", "unexpected": true}
            """))
                .isInstanceOf(SchemaValidationException.class);
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> service.validateAndParse("{invalid-json"))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("désérialisation JSON");
    }
}