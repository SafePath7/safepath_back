package com.safepath.backend.risk;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * Aligns the servlet upload defaults with this module's 10 MB storage limit.
 * Servlet multipart limits apply to all controllers. Explicit Spring multipart
 * settings, including location and disk threshold, remain under deployment control.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(name = "spring.servlet.multipart.enabled", matchIfMissing = true)
@EnableConfigurationProperties(MultipartProperties.class)
public class EventMediaUploadConfiguration {
    @Bean
    @ConditionalOnMissingBean(MultipartConfigElement.class)
    MultipartConfigElement eventMediaMultipartConfig(MultipartProperties properties, Environment environment) {
        Binder binder = Binder.get(environment);
        if (!binder.bind("spring.servlet.multipart.max-file-size", DataSize.class).isBound()) {
            properties.setMaxFileSize(DataSize.ofMegabytes(10));
        }
        if (!binder.bind("spring.servlet.multipart.max-request-size", DataSize.class).isBound()) {
            properties.setMaxRequestSize(DataSize.ofMegabytes(12));
        }
        return properties.createMultipartConfig();
    }
}
