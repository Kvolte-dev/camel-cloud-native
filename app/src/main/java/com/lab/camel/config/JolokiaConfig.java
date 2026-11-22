package com.lab.camel.config;

import org.jolokia.server.core.http.AgentServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JolokiaConfig {

    @Bean
    public ServletRegistrationBean<AgentServlet> jolokiaServlet() {
        return new ServletRegistrationBean<>(new AgentServlet(), "/actuator/jolokia/*");
    }
}