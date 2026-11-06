package com.teamehera.kiikick.auth.service;
import com.teamehera.kiikick.testsupport.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:logout_http;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.sql.init.mode=always"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({RefreshRotationContract.Beans.class,LogoutHttpContract.Probe.class})
class LogoutHttpTest extends LogoutHttpContract {}
