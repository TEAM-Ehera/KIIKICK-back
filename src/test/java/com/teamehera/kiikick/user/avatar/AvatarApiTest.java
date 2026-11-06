package com.teamehera.kiikick.user.avatar;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.user.repository.UserRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.sql.init.mode=always")
@AutoConfigureMockMvc
class AvatarApiTest {
    private static final String PATH = "/api/v1/users/me/avatar";
    private static final String OLD_URL = "https://avatars.example.com/avatars/old.jpg";
    private static final String NEW_URL = "https://avatars.example.com/avatars/new.jpg";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AccessTokenService tokens;
    @MockitoBean AvatarStorage storage;
    private UUID userId;
    private String bearer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:avatars;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
        users.save(userId, "키킥");
        users.updateAvatar(userId, OLD_URL);
        bearer = "Bearer " + tokens.issue(userId, UUID.randomUUID()).token();
    }

    @Test
    void uploadResizesImageAndReplacesOnlyTheAuthenticatedUsersAvatar() throws Exception {
        UUID otherId = UUID.randomUUID();
        users.save(otherId, "other");
        when(storage.manages(OLD_URL)).thenReturn(true);
        when(storage.upload(eq(userId), any(byte[].class))).thenAnswer(call -> {
            byte[] jpeg = call.getArgument(1);
            assertThat(jpeg).startsWith((byte) 0xff, (byte) 0xd8);
            var image = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertThat(image.getWidth()).isEqualTo(512);
            assertThat(image.getHeight()).isEqualTo(256);
            return NEW_URL;
        });

        mvc.perform(multipart(PATH).file(png()).param("userId", otherId.toString()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarUrl").value(NEW_URL));
        verify(storage).delete(userId, OLD_URL);
        assertThat(users.findById(otherId).orElseThrow().avatarUrl()).isNull();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarUrl").value(NEW_URL))
                .andExpect(jsonPath("$.data.nickname").value("키킥"));
    }

    @Test
    void unauthorizedAndInvalidUploadsLeaveTheAvatarUntouched() throws Exception {
        mvc.perform(multipart(PATH).file(png())).andExpect(status().isUnauthorized());
        var forged = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3});
        mvc.perform(multipart(PATH).file(forged).header("Authorization", bearer))
                .andExpect(status().isUnprocessableEntity());
        assertThat(users.findById(userId).orElseThrow().avatarUrl()).isEqualTo(OLD_URL);
        verifyNoInteractions(storage);
    }

    @Test
    void storageFailurePreservesTheCurrentUrlAndDeleteCanBeRetried() throws Exception {
        when(storage.upload(eq(userId), any(byte[].class))).thenThrow(new AvatarStorage.Unavailable());
        when(storage.manages(OLD_URL)).thenReturn(true);
        mvc.perform(multipart(PATH).file(png()).header("Authorization", bearer))
                .andExpect(status().isServiceUnavailable());
        assertThat(users.findById(userId).orElseThrow().avatarUrl()).isEqualTo(OLD_URL);

        mvc.perform(delete(PATH).header("Authorization", bearer)).andExpect(status().isNoContent());
        mvc.perform(delete(PATH).header("Authorization", bearer)).andExpect(status().isNoContent());
        assertThat(users.findById(userId).orElseThrow().avatarUrl()).isNull();
        verify(storage).delete(userId, OLD_URL);
    }

    private MockMultipartFile png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB), "PNG", bytes);
        return new MockMultipartFile("file", "avatar.png", "image/png", bytes.toByteArray());
    }
}
