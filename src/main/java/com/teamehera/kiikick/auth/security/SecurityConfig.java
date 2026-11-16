package com.teamehera.kiikick.auth.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.teamehera.kiikick.auth.repository.AdminUserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.util.Base64;

import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(AuthSecurityProperties.class)
public class SecurityConfig {

    private static final int MINIMUM_SECRET_BYTES = 32;

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionJwtAuthenticationConverter authenticationConverter,
            AuthAuthenticationEntryPoint authenticationEntryPoint,
            AuthAccessDeniedHandler accessDeniedHandler,
            AdminUserRepository adminUsers
    ) throws Exception {
        AuthorizationManager<RequestAuthorizationContext> adminOnly = (authentication, context) -> {
            var current = authentication.get();
            return new AuthorizationDecision(current.isAuthenticated()
                    && current.getPrincipal() instanceof AuthenticatedUser user
                    && adminUsers.isActiveAdmin(user.userId()));
        };
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/email-verifications/**",
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/actuator/health",
                                "/actuator/info",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/admin/comment-reports", "/api/v1/admin/comment-reports/**")
                        .access(adminOnly)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    @Bean
    SessionJwtAuthenticationConverter sessionJwtAuthenticationConverter() {
        return new SessionJwtAuthenticationConverter();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthSecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators
                .createDefaultWithIssuer(properties.issuer());
        JwtTimestampValidator requiredExpiry = new JwtTimestampValidator();
        // 기본 시간 검증은 exp 누락을 허용하므로 Access Token에서는 반드시 요구한다.
        requiredExpiry.setAllowEmptyExpiryClaim(false);
        OAuth2TokenValidator<Jwt> audience = jwt ->
                jwt.getAudience().contains(properties.audience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "대상이 올바르지 않은 토큰입니다.", null
                        ));
        OAuth2TokenValidator<Jwt> type = jwt ->
                AccessTokenService.ACCESS_TOKEN_TYPE.equals(
                        jwt.getClaimAsString(AccessTokenService.TOKEN_TYPE_CLAIM)
                ) ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "Access Token이 아닙니다.", null
                        ));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuer, requiredExpiry, audience, type
        ));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt cost 12는 가입·로그인에서 동일한 단방향 비밀번호 해시에 사용한다.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthSecurityErrorWriter authSecurityErrorWriter(
            ObjectMapper objectMapper,
            Clock clock
    ) {
        return new AuthSecurityErrorWriter(objectMapper, clock);
    }

    @Bean
    AuthAuthenticationEntryPoint authAuthenticationEntryPoint(
            AuthSecurityErrorWriter errorWriter
    ) {
        return new AuthAuthenticationEntryPoint(errorWriter);
    }

    @Bean
    AuthAccessDeniedHandler authAccessDeniedHandler(
            AuthSecurityErrorWriter errorWriter
    ) {
        return new AuthAccessDeniedHandler(errorWriter);
    }

    private SecretKey secretKey(AuthSecurityProperties properties) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(properties.secretBase64());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException(
                    "AUTH_ACCESS_TOKEN_SECRET_BASE64 must be valid Base64", exception
            );
        }
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "Access Token secret must contain at least 32 bytes"
            );
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
