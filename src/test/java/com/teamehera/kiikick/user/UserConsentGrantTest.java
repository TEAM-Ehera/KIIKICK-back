package com.teamehera.kiikick.user;
import com.teamehera.kiikick.testsupport.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@SpringBootTest(properties={"kiikick.auth.consent-grant-test-enabled=true", "spring.sql.init.mode=always"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(RefreshRotationContract.Beans.class)

class UserConsentGrantTest extends UserConsentGrantContract {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:consent_grant;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }
}
