package com.teamehera.kiikick.auth.email;

import com.teamehera.kiikick.auth.config.EmailVerificationProperties;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpVerificationEmailSender implements VerificationEmailSender {

    private final JavaMailSender mailSender;
    private final EmailVerificationProperties properties;

    public SmtpVerificationEmailSender(
            JavaMailSender mailSender,
            EmailVerificationProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendSignUpCode(
            String recipient,
            String code,
            long expiresInMinutes
    ) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(formattedSender());
        message.setTo(recipient);
        message.setSubject("[KIIKICK] 이메일 인증번호");
        message.setText("""
                KIIKICK 회원가입 인증번호는 %s 입니다.

                인증번호는 %d분 동안 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시해주세요.
                """.formatted(code, expiresInMinutes));
        try {
            // JavaMailSender가 설정된 SMTP 서버와 실제 전송 프로토콜을 수행한다.
            mailSender.send(message);
        } catch (MailException exception) {
            throw VerificationApiException.emailDeliveryUnavailable(exception);
        }
    }

    private String formattedSender() {
        String name = properties.senderName();
        if (name == null || name.isBlank()) {
            return properties.senderAddress();
        }
        return name + " <" + properties.senderAddress() + ">";
    }
}
