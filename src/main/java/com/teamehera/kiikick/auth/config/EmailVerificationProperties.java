package com.teamehera.kiikick.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kiikick.auth.verification")
public record EmailVerificationProperties(
        String emailCodeSecret,
        String ipSecret,
        String senderAddress,
        String senderName
) {
}
