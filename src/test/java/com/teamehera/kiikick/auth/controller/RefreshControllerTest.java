package com.teamehera.kiikick.auth.controller;
import com.teamehera.kiikick.auth.service.*;
import com.teamehera.kiikick.auth.dto.RefreshRequest;
import com.teamehera.kiikick.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties="spring.sql.init.mode=never")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class RefreshControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RefreshService service;
    private static final String TOKEN="private-refresh-token";
    @Test void 공개_API는_200_세션_서울날짜_캐시금지를_반환한다() throws Exception {
        var t=LocalDateTime.of(2026,11,6,0,0,0,123456000);
        var user=new User(UUID.randomUUID(),null,null,null,null,t,t,null);
        when(service.refresh(TOKEN)).thenReturn(new LoginResult(UUID.randomUUID(),"new-access","new-refresh",
                t.toInstant(ZoneOffset.UTC).plusSeconds(900),user,false));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\""+TOKEN+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-06T09:15:00.123456+09:00"))
                .andExpect(jsonPath("$.data.user.createdAt").value("2026-11-06T09:00:00.123456+09:00"))
                .andExpect(jsonPath("$.meta.serverTime").value(org.hamcrest.Matchers.endsWith("+09:00")));
    }
    @Test void 누락과_타입오류는_422로_거부한다() throws Exception {
        for(String body:new String[]{"{}","{\"refreshToken\":null}","{\"refreshToken\":{}}","{\"refreshToken\":123}","{\"refreshToken\":\"abc\",\"unexpected\":true}"})
            mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }
    @Test void 재사용_401에도_토큰이_로그나_오류에_노출되지_않는다(CapturedOutput output) throws Exception {
        when(service.refresh(TOKEN)).thenThrow(VerificationApiException.invalidRefreshToken(true));
        var body=mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\""+TOKEN+"\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.error.persistence").value("SAVED")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOKEN);assertThat(output).doesNotContain(TOKEN);
        assertThat(new RefreshRequest(TOKEN).toString()).doesNotContain(TOKEN);
    }
}
