package com.teamehera.kiikick.auth.email;

import com.teamehera.kiikick.auth.config.EmailVerificationProperties;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import jakarta.mail.MessagingException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class SmtpVerificationEmailSender implements VerificationEmailSender {

    private static final String SUBJECT = "[KIIKICK] 회원 인증 메일";
    private static final String HTML_TEMPLATE = loadTemplate();

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
        String plainText = """
                [KIIKICK] 회원 인증 메일

                본 이메일 인증은 KIIKICK(키킥) 계정 설정을 위한 필수사항이에요. 아래 인증코드 6자리를 확인해 주세요.

                %s

                인증코드는 발급 후 %d분 동안 유효해요. KIIKICK 앱으로 돌아가 인증코드를 입력해 주세요.

                본인이 요청하지 않은 이메일이라면 무시해 주세요.
                """.formatted(code, expiresInMinutes);
        String html = HTML_TEMPLATE.replace("{{expiresInMinutes}}", Long.toString(expiresInMinutes))
                .replace("{{code}}", HtmlUtils.htmlEscape(code, UTF_8.name()));
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_RELATED, UTF_8.name());
            helper.setFrom(formattedSender());
            helper.setTo(recipient);
            helper.setSubject(SUBJECT);
            helper.setText(plainText, html);
            helper.addInline("kiikick-logo", new ClassPathResource("kiikick-logo.png"), "image/png");
            mailSender.send(message);
        } catch (MailException | MessagingException exception) {
            throw VerificationApiException.emailDeliveryUnavailable(exception);
        }
    }

    private static String loadTemplate() {
        try {
            return new ClassPathResource("auth-signup-email.html").getContentAsString(UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the signup email template", exception);
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
