package com.teamehera.kiikick.auth.service;

import org.springframework.stereotype.Component;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class EmailNormalizer {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final Pattern LOCAL_PART = Pattern.compile(
            "^[^\\s@\\p{Cntrl}]+$"
    );

    public String normalize(String email) {
        if (email == null) {
            throw VerificationApiException.invalidRequest("이메일을 입력해주세요.");
        }
        String trimmed = email.strip();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at != trimmed.indexOf('@') || at == trimmed.length() - 1) {
            throw VerificationApiException.invalidRequest("올바른 이메일 형식이 아닙니다.");
        }

        String localPart = trimmed.substring(0, at);
        String domain = trimmed.substring(at + 1);
        if (localPart.length() > MAX_LOCAL_PART_LENGTH
                || !LOCAL_PART.matcher(localPart).matches()) {
            throw VerificationApiException.invalidRequest("올바른 이메일 형식이 아닙니다.");
        }

        String asciiDomain;
        try {
            asciiDomain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException exception) {
            throw VerificationApiException.invalidRequest("올바른 이메일 형식이 아닙니다.");
        }
        if (asciiDomain.isBlank() || asciiDomain.length() > MAX_DOMAIN_LENGTH
                || !asciiDomain.contains(".")) {
            throw VerificationApiException.invalidRequest("올바른 이메일 형식이 아닙니다.");
        }

        // 서비스 정책에 따라 로컬 파트까지 대소문자 무관 식별자로 정규화한다.
        String normalized = localPart.toLowerCase(Locale.ROOT)
                + "@" + asciiDomain.toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            throw VerificationApiException.invalidRequest("이메일이 너무 깁니다.");
        }
        return normalized;
    }
}
