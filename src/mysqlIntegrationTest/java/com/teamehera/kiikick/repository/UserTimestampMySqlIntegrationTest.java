package com.teamehera.kiikick.repository;

import com.jayway.jsonpath.JsonPath;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import com.teamehera.kiikick.auth.service.VerificationSecretGenerator;
import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import com.teamehera.kiikick.user.dto.UserCreateRequest;
import com.teamehera.kiikick.user.dto.UserUpdateRequest;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties={"spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:mysql://${MYSQL_TEST_DB_HOST}:${MYSQL_TEST_DB_PORT}/${MYSQL_TEST_DB_NAME}"
                +"?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"})
@ContextConfiguration(initializers=LocalMySqlInitializer.class)
@Import(MySqlRepositoryIntegrationTest.ClockConfig.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class UserTimestampMySqlIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AuthHashEncoder hashes;
    @Autowired MySqlRepositoryIntegrationTest.TestClock clock;
    @MockitoBean VerificationEmailSender emailSender;
    @MockitoBean VerificationSecretGenerator generator;
    final Set<UUID> ownedUsers=new HashSet<>();
    final Set<String> ownedEmails=new HashSet<>();
    String ip;

    @BeforeEach
    void prepare() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("kiikick_repository_test");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        assertThat(mockingDetails(emailSender).isMock()).isTrue();
        clock.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        UUID id=UUID.randomUUID();
        byte[] bytes=ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        bytes[0]=0x20; bytes[1]=0x01; bytes[2]=0x0d; bytes[3]=(byte)0xb8;
        ip=InetAddress.getByAddress(bytes).getHostAddress();
        when(generator.generateCode()).thenReturn("654321");
        when(generator.generateActionToken()).thenAnswer(invocation -> UUID.randomUUID().toString()+UUID.randomUUID());
    }

    @AfterEach
    void cleanup() {
        for(String email:ownedEmails) {
            jdbc.update("DELETE FROM email_verification_flows WHERE normalized_email=?",email);
            jdbc.update("DELETE FROM auth_rate_limit_events WHERE key_value=? AND rate_action IN ('EMAIL_VERIFICATION_SEND','EMAIL_PASSWORD_LOGIN')",email);
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE key_value=? AND rate_action IN ('EMAIL_VERIFICATION_SEND','EMAIL_PASSWORD_LOGIN')",email);
        }
        if(ip!=null) {
            String hash=hashes.hashIpAddress(ip);
            jdbc.update("DELETE FROM auth_rate_limit_events WHERE rate_action='EMAIL_VERIFICATION_SEND' AND limit_type='IP' AND key_value=?",hash);
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action='EMAIL_VERIFICATION_SEND' AND limit_type='IP' AND key_value=?",hash);
        }
        for(UUID id:ownedUsers) {
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",uuidToBytes(id));
            jdbc.update("DELETE FROM email_credentials WHERE user_id=?",uuidToBytes(id));
            jdbc.update("DELETE FROM users WHERE id=?",uuidToBytes(id));
        }
    }

    @Test
    void UTC_날짜와_마이크로초를_단건_목록_검색에서_보존한다() {
        UUID id=UUID.randomUUID(); ownedUsers.add(id);
        String nickname="utc-"+id.toString().substring(0,8);
        LocalDateTime time=LocalDateTime.of(2026,11,5,0,1,2,123456000);
        jdbc.update("INSERT INTO users (id,nickname,created_at,updated_at,onboarding_completed_at,deletion_requested_at) "
                +"VALUES (?,?,?,?,?,?)",uuidToBytes(id),nickname,time,time.plusSeconds(1),time.plusSeconds(2),time.plusSeconds(3));
        var user=users.findById(id).orElseThrow();
        assertThat(user.createdAt()).isEqualTo(time);
        assertThat(user.updatedAt()).isEqualTo(time.plusSeconds(1));
        assertThat(user.onboardingCompletedAt()).isEqualTo(time.plusSeconds(2));
        assertThat(user.deletionRequestedAt()).isEqualTo(time.plusSeconds(3));
        assertThat(user.deletedAt()).isNull();
        assertThat(user.createdAt().toInstant(ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-11-05T00:01:02.123456Z"));
        assertThat(users.findAll()).contains(user);
        assertThat(users.searchByNickname(nickname,10)).contains(user);
        assertThat(service.getById(id).createdAt()).isEqualTo(time);
    }

    @Test
    void nullable_날짜와_User_CRUD를_유지한다() {
        var created=service.create(new UserCreateRequest("utc-probe")); ownedUsers.add(created.id());
        var user=users.findById(created.id()).orElseThrow();
        assertThat(user.onboardingCompletedAt()).isNull();
        assertThat(user.deletionRequestedAt()).isNull();
        assertThat(user.deletedAt()).isNull();
        assertThat(service.update(created.id(),new UserUpdateRequest("utc-updated")).nickname()).isEqualTo("utc-updated");
        assertThat(service.getAll()).anyMatch(value -> value.id().equals(created.id()));
        service.delete(created.id());
        assertThat(users.findById(created.id())).isEmpty();
        assertThat(users.searchByNickname("utc-updated",100)).noneMatch(value -> value.id().equals(created.id()));
    }

    @Test
    void 이메일_인증과_오류_인증실패_메타는_Z를_반환한다() throws Exception {
        String email=newEmail();
        String requested=postJson("/api/v1/auth/email-verifications","{\"email\":\""+email+"\"}",202);
        String expires=JsonPath.read(requested,"$.data.expiresAt");
        assertThat(Instant.parse(expires)).isEqualTo(clock.instant().plusSeconds(180));
        assertUtc(requested,"$.data.resendAvailableAt");
        String id=JsonPath.read(requested,"$.data.verificationId");
        String verified=postJson("/api/v1/auth/email-verifications/"+id+"/verify","{\"code\":\"654321\"}",200);
        String tokenExpiry=JsonPath.read(verified,"$.data.expiresAt");
        assertThat(Instant.parse(tokenExpiry)).isEqualTo(clock.instant().plusSeconds(600));
        postJson("/api/v1/auth/email-verifications","{",400);
        var denied=mvc.perform(get("/api/v1/utc-time-protected-probe")).andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(401);
        assertUtc(denied.getContentAsString(),"$.meta.serverTime");
    }

    String newEmail() {
        String email="utc-probe-"+UUID.randomUUID()+"@example.com"; ownedEmails.add(email); return email;
    }

    String postJson(String path,String body,int status) throws Exception {
        var response=mvc.perform(post(path).with(request -> {request.setRemoteAddr(ip);return request;})
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        String json=response.getContentAsString(); assertUtc(json,"$.meta.serverTime"); return json;
    }

    void assertUtc(String json,String path) {
        String value=JsonPath.read(json,path); assertThat(value).endsWith("Z"); Instant.parse(value);
    }

    void assertUserCreatedAt(String json) {
        String id=JsonPath.read(json,"$.data.user.id"); UUID userId=UUID.fromString(id); ownedUsers.add(userId);
        String stored=jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') FROM users WHERE id=?",
                String.class,uuidToBytes(userId));
        Instant expected=LocalDateTime.parse(stored).toInstant(ZoneOffset.UTC);
        String returned=JsonPath.read(json,"$.data.user.createdAt");
        assertUtc(json,"$.data.user.createdAt"); assertUtc(json,"$.data.expiresAt");
        assertThat(Instant.parse(returned)).isEqualTo(expected);
    }
}
