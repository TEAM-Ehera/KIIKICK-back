package com.teamehera.kiikick.auth.password;

import com.teamehera.kiikick.auth.service.SignupApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyValidatorTest {

    private PasswordPolicyValidator validator;

    @BeforeEach
    void setUp() {
        String list = IntStream.range(0, 999)
                .mapToObj(index -> "blocked-" + index)
                .collect(Collectors.joining("\n"))
                + "\npassword\n";
        CommonPasswordBlacklist blacklist = new CommonPasswordBlacklist(
                new ByteArrayResource(list.getBytes(StandardCharsets.UTF_8))
        );
        validator = new PasswordPolicyValidator(blacklist);
    }

    @Test
    void 비밀번호는_8자부터_64자까지_허용하고_조합이나_연속문자를_강제하지_않는다() {
        assertThatCode(() -> validator.validate("abcd5678"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("a".repeat(64)))
                .doesNotThrowAnyException();
    }

    @Test
    void 비밀번호는_8자_미만과_64자_초과를_거부한다() {
        assertInvalid("a".repeat(7));
        assertInvalid("a".repeat(65));
    }

    @Test
    void 문자수와_별도로_UTF8_72바이트를_검사한다() {
        assertThatCode(() -> validator.validate("가".repeat(24)))
                .doesNotThrowAnyException();
        assertInvalid("가".repeat(25));
    }

    @Test
    void 차단목록은_Locale_ROOT_소문자_기준으로_비교한다() {
        assertInvalid("password");
        assertInvalid("Password");
        assertInvalid("PASSWORD");
        assertThatCode(() -> validator.validate("Password!"))
                .doesNotThrowAnyException();
    }

    private void assertInvalid(String password) {
        assertThatThrownBy(() -> validator.validate(password))
                .isInstanceOf(SignupApiException.class)
                .extracting(exception -> ((SignupApiException) exception).code())
                .isEqualTo("INVALID_PASSWORD");
    }
}
