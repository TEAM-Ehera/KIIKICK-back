package com.teamehera.kiikick.user;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.testsupport.AccountDeletionContract;
import com.teamehera.kiikick.testsupport.RefreshRotationContract;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@AutoConfigureMockMvc
@Import(RefreshRotationContract.Beans.class)
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.datasource.url=jdbc:h2:mem:account_deletion;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class AccountDeletionTest extends AccountDeletionContract {
    @MockitoBean VerificationEmailSender sender;
}
