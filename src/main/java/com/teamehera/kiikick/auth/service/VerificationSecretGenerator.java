package com.teamehera.kiikick.auth.service;

public interface VerificationSecretGenerator {

    String generateCode();

    String generateActionToken();
}
