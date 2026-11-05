package com.teamehera.kiikick.auth.password;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonPasswordBlacklistTest {

    @Test
    void 실제_차단목록은_중복_없는_1000개를_소문자_기준으로_비교한다() {
        CommonPasswordBlacklist blacklist = new CommonPasswordBlacklist(
                new ClassPathResource("blacklist.txt")
        );

        assertThat(blacklist.size()).isEqualTo(1_000);
        assertThat(blacklist.contains("password")).isTrue();
        assertThat(blacklist.contains("PASSWORD")).isTrue();
        assertThat(blacklist.contains(" password")).isFalse();
        assertThat(blacklist.contains("password ")).isFalse();
    }

    @Test
    void 파일이_누락되면_초기화를_실패한다() {
        assertThatThrownBy(() -> new CommonPasswordBlacklist(
                new FileSystemResource("missing-password-blacklist.txt")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or unreadable");
    }

    @Test
    void 파일_읽기가_실패하면_초기화를_실패한다() {
        AbstractResource unreadable = new AbstractResource() {
            @Override
            public String getDescription() {
                return "unreadable blacklist";
            }

            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public boolean isReadable() {
                return true;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("read failed");
            }
        };

        assertThatThrownBy(() -> new CommonPasswordBlacklist(unreadable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be loaded");
    }

    @Test
    void 중복으로_고유_항목이_1000개가_아니면_초기화를_실패한다() {
        String content = "password\n".repeat(1_000);
        assertThatThrownBy(() -> new CommonPasswordBlacklist(
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 1000 unique entries");
    }
}
