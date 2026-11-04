package com.teamehera.kiikick.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("kiikick.auth.access-token")
public record AuthSecurityProperties(
        String secretBase64,
        String issuer,
        String audience,
        Duration lifetime
) {
}
