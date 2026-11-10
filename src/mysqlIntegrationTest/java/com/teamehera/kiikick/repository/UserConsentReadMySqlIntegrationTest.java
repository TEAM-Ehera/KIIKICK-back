package com.teamehera.kiikick.repository;
import com.teamehera.kiikick.testsupport.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
@SpringBootTest(properties="spring.sql.init.mode=never")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(RefreshRotationContract.Beans.class)
@ContextConfiguration(initializers=LocalMySqlInitializer.class)
class UserConsentReadMySqlIntegrationTest extends UserConsentReadContract {

}
