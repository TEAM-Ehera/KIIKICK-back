package com.teamehera.kiikick.auth.config;

import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(EmailVerificationProperties.class)
public class EmailVerificationConfig {

    @Bean
    AuthHashEncoder authHashEncoder(EmailVerificationProperties properties) {
        return new AuthHashEncoder(
                bytes(properties.emailCodeSecret(), "AUTH_EMAIL_CODE_HMAC_SECRET"),
                bytes(properties.ipSecret(), "AUTH_IP_HMAC_SECRET")
        );
    }

    private byte[] bytes(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " is required");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
