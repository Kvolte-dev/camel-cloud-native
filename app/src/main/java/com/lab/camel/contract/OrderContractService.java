package com.lab.camel.contract;

import com.lab.camel.model.Order;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class OrderContractService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderContractService.class);
    private static final String SCHEMA_CLASSPATH = "schemas/order-schema.json";

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final SchemaRegistry schemaRegistry;
    private final String registryUrl;
    private final String subject;

    private final AtomicReference<Schema> activeSchema = new AtomicReference<>();
    private final AtomicInteger activeSchemaId = new AtomicInteger(-1);

    public OrderContractService(
            JsonMapper jsonMapper,
            @Value("${apicurio.registry-url}") String registryUrl,
            @Value("${apicurio.order-subject}") String subject) {
        this.jsonMapper = jsonMapper;
        this.registryUrl = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
        this.subject = subject;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        loadLocalFallbackSchema();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String localSchemaJson = readLocalSchema();
            int registeredId = registerSchemaInApicurio(localSchemaJson);
            configureBackwardCompatibility();
            loadLatestSchemaFromApicurio(registeredId);
        } catch (Exception e) {
            log.warn("Apicurio Registry indisponible au démarrage ({}), utilisation du schéma local en repli : {}",
                    registryUrl, e.getMessage());
        }
    }

    public Order validateAndParse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new SchemaValidationException("Payload vide reçu sur le topic orders.process");
        }

        JsonNode jsonNode;
        try {
            jsonNode = jsonMapper.readTree(rawPayload);
        } catch (JacksonException e) {
            throw new SchemaValidationException("Échec de désérialisation JSON : " + e.getOriginalMessage(), e);
        }

        List<Error> errors = activeSchema.get().validate(jsonNode);
        if (!errors.isEmpty()) {
            String details = errors.stream()
                    .map(Error::toString)
                    .collect(Collectors.joining("; "));
            throw new SchemaValidationException(
                    "Violation du contrat JSON Schema (" + subject + ", schemaId=" + activeSchemaId.get() + ") : " + details
            );
        }

        try {
            return jsonMapper.treeToValue(jsonNode, Order.class);
        } catch (JacksonException e) {
            throw new SchemaValidationException("Impossible de mapper le JSON vers Order : " + e.getOriginalMessage(), e);
        }
    }

    public String getSubject() {
        return subject;
    }

    public int getActiveSchemaId() {
        return activeSchemaId.get();
    }

    private int registerSchemaInApicurio(String schemaContent) throws IOException, InterruptedException {
        String body = jsonMapper.writeValueAsString(Map.of(
                "schemaType", "JSON",
                "schema", schemaContent
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl + "/subjects/" + subject + "/versions"))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " lors de l'enregistrement : " + response.body());
        }

        int schemaId = jsonMapper.readTree(response.body()).path("id").asInt(-1);
        log.info("Contrat JSON Schema enregistré dans Apicurio Registry | subject={} | schemaId={}", subject, schemaId);
        return schemaId;
    }

    private void configureBackwardCompatibility() throws IOException, InterruptedException {
        String body = jsonMapper.writeValueAsString(Map.of("compatibility", "BACKWARD"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl + "/config/" + subject))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("Règle de compatibilité BACKWARD activée sur Apicurio | subject={}", subject);
        } else {
            log.warn("Impossible d'appliquer la compatibilité BACKWARD (HTTP {}) : {}", response.statusCode(), response.body());
        }
    }

    private void loadLatestSchemaFromApicurio(int fallbackId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl + "/subjects/" + subject + "/versions/latest"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " lors de la lecture du schéma : " + response.body());
        }

        JsonNode root = jsonMapper.readTree(response.body());
        String remoteSchemaJson = root.path("schema").asString();
        int schemaId = root.path("id").asInt(fallbackId);

        Schema compiled = schemaRegistry.getSchema(remoteSchemaJson, InputFormat.JSON);
        compiled.initializeValidators();
        activeSchema.set(compiled);
        activeSchemaId.set(schemaId);
        log.info("Schéma actif chargé depuis Apicurio Registry | subject={} | schemaId={} | version={}",
                subject, schemaId, root.path("version").asInt(1));
    }

    private void loadLocalFallbackSchema() {
        try {
            Schema compiled = schemaRegistry.getSchema(readLocalSchema(), InputFormat.JSON);
            compiled.initializeValidators();
            activeSchema.set(compiled);
        } catch (IOException e) {
            throw new IllegalStateException("Schéma local introuvable : " + SCHEMA_CLASSPATH, e);
        }
    }

    private String readLocalSchema() throws IOException {
        return new ClassPathResource(SCHEMA_CLASSPATH).getContentAsString(StandardCharsets.UTF_8);
    }
}