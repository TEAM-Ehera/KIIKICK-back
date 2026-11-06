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
class MyProfileTest extends MyProfileContract {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // 다른 전체 테스트의 H2 스키마와 분리하고 외부 DB 설정을 사용하지 않는다.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:my_profile;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }
}
