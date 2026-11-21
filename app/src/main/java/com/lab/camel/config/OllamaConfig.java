package com.lab.camel.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OllamaConfig {

    @Bean
    public ChatModel ollamaChatModel(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model-name}") String modelName) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}