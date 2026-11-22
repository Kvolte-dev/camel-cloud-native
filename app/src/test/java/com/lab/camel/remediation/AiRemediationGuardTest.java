package com.lab.camel.remediation;

import com.lab.camel.contract.OrderContractService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AiRemediationGuardTest {

    private JsonMapper jsonMapper;
    private OrderContractService contractService;
    private SimpleMeterRegistry meterRegistry;
    private AiRemediationGuard guard;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        contractService = new OrderContractService(
                jsonMapper,
                "http://localhost:8080/apis/ccompat/v7",
                "orders.process-value"
        );
        meterRegistry = new SimpleMeterRegistry();
        guard = new AiRemediationGuard(jsonMapper, contractService, meterRegistry, true, 1);
    }

    @Test
    void shouldApproveValidRunbookEvenWithMarkdownFences() {
        String aiResponse = """
            ```json
            {
              "runbookRef": "RB-ORD-001",
              "cause": "Article FAIL détecté",
              "severity": "MEDIUM",
              "autoRemediate": true,
              "suggestedAction": "Remplacer FAIL par MANUAL_REVIEW",
              "correctedPayload": {"orderId": 1001, "item": "MANUAL_REVIEW", "qty": 2}
            }
            ```
            """;

        AiRemediationDecision decision = guard.evaluate(aiResponse, 0);

        assertThat(decision.approved()).isTrue();
        assertThat(decision.status()).isEqualTo("APPROVED");
        assertThat(decision.nextRetryCount()).isEqualTo(1);
        assertThat(decision.correctedPayload()).contains("\"item\":\"MANUAL_REVIEW\"");
        assertThat(meterRegistry.counter("app.ai.remediation", "status", "APPROVED", "runbook", "RB-ORD-001").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldBlockDisallowedRunbookByPolicy() {
        String aiResponse = """
            {
              "runbookRef": "RB-KFK-002",
              "cause": "Contrat violé",
              "severity": "HIGH",
              "autoRemediate": true,
              "suggestedAction": "Rejeter sans rejeu",
              "correctedPayload": {"orderId": 1, "item": "FIXED"}
            }
            """;

        AiRemediationDecision decision = guard.evaluate(aiResponse, 0);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.status()).isEqualTo("POLICY_BLOCKED");
        assertThat(meterRegistry.counter("app.ai.remediation", "status", "POLICY_BLOCKED", "runbook", "RB-KFK-002").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldBlockWhenMaxRetriesReached() {
        AiRemediationDecision decision = guard.evaluate("{}", 1);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.status()).isEqualTo("MAX_RETRIES_REACHED");
    }

    @Test
    void shouldBlockWhenCorrectedPayloadViolatesApicurioSchema() {
        String aiResponse = """
            {
              "runbookRef": "RB-ORD-001",
              "autoRemediate": true,
              "correctedPayload": {"orderId": -10, "item": ""}
            }
            """;

        AiRemediationDecision decision = guard.evaluate(aiResponse, 0);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.status()).isEqualTo("SCHEMA_VALIDATION_FAILED");
    }

    @Test
    void shouldBlockWhenFeatureFlagDisabled() {
        var disabledGuard = new AiRemediationGuard(jsonMapper, contractService, meterRegistry, false, 1);

        AiRemediationDecision decision = disabledGuard.evaluate("{}", 0);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.status()).isEqualTo("DISABLED");
    }
}