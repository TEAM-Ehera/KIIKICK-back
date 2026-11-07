package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.testsupport.AccountDeletionContract;
import com.teamehera.kiikick.testsupport.RefreshRotationContract;
import org.springframework.context.annotation.Import;
import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@AutoConfigureMockMvc
@Import(RefreshRotationContract.Beans.class)
@SpringBootTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(initializers = LocalMySqlInitializer.class)
class AccountDeletionMySqlIntegrationTest extends AccountDeletionContract {
    @MockitoBean VerificationEmailSender sender;
}
