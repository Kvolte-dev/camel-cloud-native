package com.lab.camel;

import org.apache.camel.opentelemetry2.starter.CamelOpenTelemetry2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@CamelOpenTelemetry2
@SpringBootApplication
public class CamelLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(CamelLabApplication.class, args);
    }
}