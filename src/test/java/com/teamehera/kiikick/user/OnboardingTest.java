package com.teamehera.kiikick.user;
import com.teamehera.kiikick.testsupport.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@SpringBootTest(properties="spring.sql.init.mode=always")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(RefreshRotationContract.Beans.class)
class OnboardingTest extends OnboardingContract {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:onboarding;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }
}
