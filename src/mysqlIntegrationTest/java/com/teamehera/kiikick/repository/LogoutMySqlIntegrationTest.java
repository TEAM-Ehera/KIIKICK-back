package com.teamehera.kiikick.repository;
import com.teamehera.kiikick.testsupport.*;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest(properties="spring.sql.init.mode=never")
@ContextConfiguration(initializers=LocalMySqlInitializer.class)
@Import(RefreshRotationContract.Beans.class)
class LogoutMySqlIntegrationTest extends LogoutContract {
    @MockitoBean VerificationEmailSender sender;
    @BeforeEach void target() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("kiikick_repository_test");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
    }
}
