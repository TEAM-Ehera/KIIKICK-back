package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user_list_controller;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class UserListControllerTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    @Test
    void arrayResponseExposesNextPageThroughLinkHeader() throws Exception {
        users.save(UUID.randomUUID(), "first");
        users.save(UUID.randomUUID(), "second");
        var first = mvc.perform(get("/api/v1/users").param("limit", "1").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse();
        String link = first.getHeader(HttpHeaders.LINK);
        assertThat(link).isNotNull().endsWith("; rel=\"next\"");
        String next = link.substring(1, link.indexOf('>'));
        var second = mvc.perform(get(next).with(jwt())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(header().doesNotExist(HttpHeaders.LINK))
                .andReturn().getResponse();
        assertThat(second.getContentAsString()).isNotEqualTo(first.getContentAsString());
    }

    @Test
    void invalidCursorAndOversizedPagesReturn400() throws Exception {
        mvc.perform(get("/api/v1/users").param("cursor", "broken").with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/users").param("limit", "101").with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
    }
}
