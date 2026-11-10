package com.teamehera.kiikick.auth.domain;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ConsentPolicyTest {
    @Test void 승인된_두목적의_1점0만지원한다() {
        for (String purpose : new String[]{"GPS_COLLECTION", "HEALTH_DATA"}) {
            assertThat(ConsentPolicy.supportsPurpose(purpose)).isTrue();
            assertThat(ConsentPolicy.supports(purpose, "1.0")).isTrue();
            assertThat(ConsentPolicy.supports(purpose, "0.9")).isFalse();
        }
    }
    @Test void 비교와_검색공개_미지원목적을거부한다() {
        for (String purpose : new String[]{"FRIEND_COMPARISON", "FRIEND_DISCOVERY", "gps_collection", ""}) {
            assertThat(ConsentPolicy.supportsPurpose(purpose)).isFalse();
            assertThat(ConsentPolicy.supports(purpose, "1.0")).isFalse();
        }
    }
    @Test void null은_예외없이_거부한다() {
        assertThat(ConsentPolicy.supportsPurpose(null)).isFalse();
        assertThat(ConsentPolicy.supports(null, "1.0")).isFalse();
        assertThat(ConsentPolicy.supports("GPS_COLLECTION", null)).isFalse();
    }
}
