package com.teamehera.kiikick.user.avatar;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AvatarStorageConfiguration.R2Properties.class)
class AvatarStorageConfiguration {
    @ConfigurationProperties("kiikick-avatar")
    record R2Properties(String endpoint, String bucket, String publicBaseUrl,
                        String accessKeyId, String secretAccessKey) {}

    @Bean
    @ConditionalOnProperty(prefix = "kiikick-avatar", name = "enabled", havingValue = "true")
    S3Client avatarR2Client(R2Properties settings) {
        return S3Client.builder()
                .endpointOverride(httpsOrigin(settings.endpoint()))
                .region(Region.of("auto"))
                // R2는 AWS SDK의 기본 chunked PUT 서명을 지원하지 않는다.
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        settings.accessKeyId(), settings.secretAccessKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(4)))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kiikick-avatar", name = "enabled", havingValue = "true")
    AvatarStorage r2AvatarStorage(S3Client avatarR2Client, R2Properties settings) {
        if (settings.bucket() == null || settings.bucket().isBlank()) {
            throw new IllegalStateException("AVATAR_R2_BUCKET is required");
        }
        return new R2AvatarStorage(avatarR2Client, settings.bucket(), httpsOrigin(settings.publicBaseUrl()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "kiikick-avatar", name = "enabled", havingValue = "false", matchIfMissing = true)
    AvatarStorage disabledAvatarStorage() {
        return new AvatarStorage() {
            @Override
            public String upload(UUID owner, byte[] jpeg) {
                throw new Unavailable();
            }

            @Override
            public boolean manages(String url) {
                return false;
            }

            @Override
            public void delete(UUID owner, String url) {}
        };
    }

    private static URI httpsOrigin(String value) {
        URI uri = URI.create(value == null ? "" : value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalStateException("R2 endpoint and public base URL must be HTTPS origins");
        }
        return uri.resolve("/");
    }

    private record R2AvatarStorage(S3Client client, String bucket, URI origin) implements AvatarStorage {
        private static final Pattern FILE = Pattern.compile(
                "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.jpg");

        @Override
        public String upload(UUID owner, byte[] jpeg) {
            String key = ownerPrefix(owner) + UUID.randomUUID() + ".jpg";
            try {
                client.putObject(request -> request.bucket(bucket).key(key)
                        .contentType("image/jpeg").cacheControl("public, max-age=300"),
                        RequestBody.fromBytes(jpeg));
                return origin.resolve(key).toString();
            } catch (SdkException exception) {
                throw new Unavailable(exception);
            }
        }

        @Override
        public boolean manages(String url) {
            if (url == null) return false;
            try {
                URI candidate = URI.create(url.strip()).normalize();
                // 동일 CDN의 파일을 JSON PATCH로 다시 연결하면 삭제 작업과 충돌할 수 있다.
                return origin.getHost().equalsIgnoreCase(candidate.getHost());
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        @Override
        public void delete(UUID owner, String url) {
            String prefix = origin.resolve(ownerPrefix(owner)).toString();
            if (url == null || !url.startsWith(prefix) || !FILE.matcher(url.substring(prefix.length())).matches()) {
                return;
            }
            String key = origin.relativize(URI.create(url)).getPath();
            try {
                client.deleteObject(request -> request.bucket(bucket).key(key));
            } catch (SdkException exception) {
                throw new Unavailable(exception);
            }
        }

        private static String ownerPrefix(UUID owner) {
            return "avatars/" + owner + "/";
        }
    }
}
