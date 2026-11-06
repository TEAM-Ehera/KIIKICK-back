package com.teamehera.kiikick.auth.email;

public interface VerificationEmailSender {

    void sendSignUpCode(String recipient, String code, long expiresInMinutes);
}
