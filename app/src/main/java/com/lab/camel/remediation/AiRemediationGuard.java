package com.lab.camel.remediation;

import com.lab.camel.contract.OrderContractService;
import com.lab.camel.contract.SchemaValidationException;
import com.lab.camel.model.Order;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@Service
public class AiRemediationGuard {

    private static final Logger log = LoggerFactory.getLogger(AiRemediationGuard.class);
    private static final Set<String> ALLOWED_RUNBOOKS = Set.of("RB-ORD-001", "RB-NET-003");

    private final JsonMapper jsonMapper;
    private final OrderContractService orderContractService;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int maxRetries;

    public AiRemediationGuard(
            JsonMapper jsonMapper,
            OrderContractService orderContractService,
            MeterRegistry meterRegistry,
            @Value("${app.ai-remediation.enabled:true}") boolean enabled,
            @Value("${app.ai-remediation.max-retries:1}") int maxRetries) {
        this.jsonMapper = jsonMapper;
        this.orderContractService = orderContractService;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.maxRetries = maxRetries;
    }

    public AiRemediationDecision evaluate(String rawAiResponse, int currentRetryCount) {
        if (!enabled) {
            return recordMetric(AiRemediationDecision.rejected(
                    "DISABLED", "Auto-remédiation désactivée par configuration", "NONE", currentRetryCount
            ));
        }

        if (currentRetryCount >= maxRetries) {
            return recordMetric(AiRemediationDecision.rejected(
                    "MAX_RETRIES_REACHED",
                    "Limite anti-boucle atteinte (aiRetryCount=" + currentRetryCount + ", max=" + maxRetries + ")",
                    "NONE",
                    currentRetryCount
            ));
        }

        String jsonContent = extractJsonObject(rawAiResponse);
        if (jsonContent == null) {
            return recordMetric(AiRemediationDecision.rejected(
                    "INVALID_AI_RESPONSE", "Aucun objet JSON détecté dans la réponse Ollama", "NONE", currentRetryCount
            ));
        }

        JsonNode root;
        try {
            root = jsonMapper.readTree(jsonContent);
        } catch (JacksonException e) {
            return recordMetric(AiRemediationDecision.rejected(
                    "INVALID_AI_RESPONSE", "JSON IA illisible : " + e.getOriginalMessage(), "NONE", currentRetryCount
            ));
        }

        String runbookRef = root.path("runbookRef").asString("NONE").trim();
        boolean autoRemediate = root.path("autoRemediate").asBoolean(false);

        if (!autoRemediate || !ALLOWED_RUNBOOKS.contains(runbookRef)) {
            return recordMetric(AiRemediationDecision.rejected(
                    "POLICY_BLOCKED",
                    "Rejeu interdit par politique SRE (runbookRef=" + runbookRef + ", autoRemediate=" + autoRemediate + ")",
                    runbookRef,
                    currentRetryCount
            ));
        }

        String candidatePayload = extractCorrectedPayload(root.path("correctedPayload"));
        if (candidatePayload == null || candidatePayload.isBlank()) {
            return recordMetric(AiRemediationDecision.rejected(
                    "SCHEMA_VALIDATION_FAILED", "Champ correctedPayload absent ou vide", runbookRef, currentRetryCount
            ));
        }

        try {
            Order validatedOrder = orderContractService.validateAndParse(candidatePayload);
            String normalizedPayload = jsonMapper.writeValueAsString(validatedOrder);
            log.info("Auto-remédiation approuvée | runbookRef={} | nextRetryCount={} | payload={}",
                    runbookRef, currentRetryCount + 1, normalizedPayload);
            return recordMetric(AiRemediationDecision.approved(runbookRef, normalizedPayload, currentRetryCount + 1));
        } catch (SchemaValidationException | JacksonException e) {
            return recordMetric(AiRemediationDecision.rejected(
                    "SCHEMA_VALIDATION_FAILED",
                    "Le correctedPayload proposé par l'IA viole le contrat Apicurio : " + e.getMessage(),
                    runbookRef,
                    currentRetryCount
            ));
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private String extractCorrectedPayload(JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isMissingNode() || payloadNode.isNull()) {
            return null;
        }
        if (payloadNode.isObject()) {
            return jsonMapper.writeValueAsString(payloadNode);
        }
        if (payloadNode.isString()) {
            return payloadNode.asString();
        }
        return null;
    }

    private AiRemediationDecision recordMetric(AiRemediationDecision decision) {
        meterRegistry.counter(
                "app.ai.remediation",
                "status", decision.status(),
                "runbook", decision.runbookRef()
        ).increment();
        return decision;
    }
}