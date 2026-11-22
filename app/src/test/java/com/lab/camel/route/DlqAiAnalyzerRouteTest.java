package com.lab.camel.route;

import com.lab.camel.contract.OrderContractService;
import com.lab.camel.remediation.AiRemediationGuard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlqAiAnalyzerRouteTest {

    private CamelContext context;
    private ProducerTemplate template;
    private AtomicReference<String> simulatedAiResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        simulatedAiResponse = new AtomicReference<>();

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);

        Embedding vector = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(vector));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(
                        new EmbeddingMatch<>(
                                0.92,
                                "rb-ord-001",
                                vector,
                                TextSegment.from("[RUNBOOK RB-ORD-001] Remplacer FAIL par MANUAL_REVIEW puis rejouer dans orders.process.")
                        )
                )));

        var jsonMapper = JsonMapper.builder().build();
        var contractService = new OrderContractService(
                jsonMapper,
                "http://localhost:8080/apis/ccompat/v7",
                "orders.process-value"
        );
        var remediationGuard = new AiRemediationGuard(
                jsonMapper,
                contractService,
                new SimpleMeterRegistry(),
                true,
                1
        );

        context = new DefaultCamelContext();
        context.setAutowiredEnabled(false);
        context.addComponent("kafka", new MockComponent());
        context.addRoutes(new DlqAiAnalyzerRoute(embeddingModel, embeddingStore, remediationGuard));

        AdviceWith.adviceWith(context, "kafka-dlq-ai-analyzer-route", route -> {
            route.replaceFromWith("direct:orders-dlq");
            route.weaveByToUri("langchain4j-chat:*")
                    .replace()
                    .process(exchange -> exchange.getIn().setBody(simulatedAiResponse.get()));
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
    void approvedRemediationShouldArchiveAndReinjectIntoOrdersProcess() throws Exception {
        simulatedAiResponse.set("""
            {
              "runbookRef": "RB-ORD-001",
              "cause": "Article sentinelle FAIL",
              "severity": "MEDIUM",
              "autoRemediate": true,
              "suggestedAction": "Ouvrir INC-ERP-REF et remplacer FAIL par MANUAL_REVIEW",
              "correctedPayload": {"orderId": 1001, "item": "MANUAL_REVIEW", "qty": 2}
            }
            """);

        MockEndpoint analyzedMock = context.getEndpoint("kafka:orders.dlq.analyzed", MockEndpoint.class);
        MockEndpoint reprocessMock = context.getEndpoint("kafka:orders.process", MockEndpoint.class);

        analyzedMock.expectedMessageCount(1);
        reprocessMock.expectedMessageCount(1);
        reprocessMock.expectedHeaderReceived("aiRetryCount", "1");
        reprocessMock.expectedHeaderReceived("X-Auto-Remediated", "true");
        reprocessMock.expectedHeaderReceived("X-Remediation-Runbook", "RB-ORD-001");

        template.sendBodyAndHeaders(
                "direct:orders-dlq",
                """
                {"orderId": 1001, "item": "FAIL", "qty": 2}
                """,
                Map.of(
                        "CorrelationID", "corr-1001",
                        "ErrorMessage", "Erreur simulée sur le message contenant FAIL"
                )
        );

        analyzedMock.assertIsSatisfied();
        reprocessMock.assertIsSatisfied();

        var reinjectedIn = reprocessMock.getExchanges().getFirst().getIn();
        assertThat(reinjectedIn.getHeader("ErrorMessage")).isNull();
        assertThat(reinjectedIn.getBody(String.class)).contains("\"orderId\":1001", "\"item\":\"MANUAL_REVIEW\"");
    }

    @Test
    void policyBlockedRemediationShouldArchiveWithoutReinjection() throws Exception {
        simulatedAiResponse.set("""
            {
              "runbookRef": "RB-KFK-002",
              "cause": "Contrat JSON Schema non respecté",
              "severity": "HIGH",
              "autoRemediate": false,
              "suggestedAction": "Rejeter sans rejeu automatique",
              "correctedPayload": null
            }
            """);

        MockEndpoint analyzedMock = context.getEndpoint("kafka:orders.dlq.analyzed", MockEndpoint.class);
        MockEndpoint reprocessMock = context.getEndpoint("kafka:orders.process", MockEndpoint.class);

        analyzedMock.expectedMessageCount(1);
        reprocessMock.expectedMessageCount(0);

        template.sendBodyAndHeaders(
                "direct:orders-dlq",
                """
                {"orderId": "BAD", "qty": -1}
                """,
                Map.of("ErrorMessage", "Violation du contrat JSON Schema")
        );

        analyzedMock.assertIsSatisfied();
        reprocessMock.assertIsSatisfied();
    }

    @Test
    void maxRetriesReachedShouldPreventInfiniteLoop() throws Exception {
        simulatedAiResponse.set("""
            {
              "runbookRef": "RB-ORD-001",
              "cause": "Nouvel échec après auto-remédiation",
              "severity": "MEDIUM",
              "autoRemediate": true,
              "suggestedAction": "Remplacer FAIL par MANUAL_REVIEW",
              "correctedPayload": {"orderId": 1001, "item": "MANUAL_REVIEW", "qty": 2}
            }
            """);

        MockEndpoint analyzedMock = context.getEndpoint("kafka:orders.dlq.analyzed", MockEndpoint.class);
        MockEndpoint reprocessMock = context.getEndpoint("kafka:orders.process", MockEndpoint.class);

        analyzedMock.expectedMessageCount(1);
        reprocessMock.expectedMessageCount(0);

        template.sendBodyAndHeaders(
                "direct:orders-dlq",
                """
                {"orderId": 1001, "item": "FAIL", "qty": 2}
                """,
                Map.of(
                        "ErrorMessage", "Erreur simulée sur le message contenant FAIL",
                        "aiRetryCount", "1"
                )
        );

        analyzedMock.assertIsSatisfied();
        reprocessMock.assertIsSatisfied();
    }
}