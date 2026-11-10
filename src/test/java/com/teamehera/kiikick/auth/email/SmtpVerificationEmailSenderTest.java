package com.teamehera.kiikick.auth.email;

import com.teamehera.kiikick.auth.config.EmailVerificationProperties;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpVerificationEmailSenderTest {

    @Test
    void 실제_SMTP로_HTML_인증메일과_텍스트_대체_본문을_전송한다() throws Exception {
        try (TestSmtpServer server = new TestSmtpServer()) {
            SmtpVerificationEmailSender sender = sender(server.port());

            sender.sendSignUpCode("receiver@example.com", "012345", 3);

            MimeMessage received = server.awaitMessage(Duration.ofSeconds(5));
            assertThat(received.getAllRecipients()[0].toString())
                    .isEqualTo("receiver@example.com");
            assertThat(received.getSubject()).isEqualTo("[KIIKICK] 회원 인증 메일");
            var root = (Multipart) received.getContent();
            var body = (Multipart) root.getBodyPart(0).getContent();
            assertThat(body.getContentType()).startsWith("multipart/alternative");
            assertThat(body.getBodyPart(0).getContentType()).startsWith("text/plain");
            assertThat(body.getBodyPart(0).getContent().toString()).contains("012345", "3분");
            assertThat(body.getBodyPart(1).getContentType()).startsWith("text/html");
            assertThat(body.getBodyPart(1).getContent().toString())
                    .contains("[KIIKICK] 회원 인증 메일",
                            "본 이메일 인증은 KIIKICK(키킥) 계정 설정을 위한 필수사항이에요.",
                            "아래 인증코드 6자리를 확인해 주세요.",
                            "012345",
                            "인증코드는 발급 후 3분 동안 유효해요.",
                            "KIIKICK 앱으로 돌아가 인증코드를 입력해 주세요.",
                            "본인이 요청하지 않은 이메일이라면 무시해 주세요.",
                            "#00DAC3")
                    .doesNotContain("{{code}}", "{{expiresInMinutes}}");
        }
    }

    @Test
    void HTML에서_인증코드_특수문자를_이스케이프하고_전달된_유효시간을_표시한다() throws Exception {
        try (TestSmtpServer server = new TestSmtpServer()) {
            sender(server.port()).sendSignUpCode("receiver@example.com", "<123>&", 7);

            var root = (Multipart) server.awaitMessage(Duration.ofSeconds(5)).getContent();
            var body = (Multipart) root.getBodyPart(0).getContent();
            assertThat(body.getBodyPart(1).getContent().toString())
                    .contains("&lt;123&gt;&amp;", "발급 후 7분 동안")
                    .doesNotContain("<123>&");
        }
    }

    @Test
    void SMTP_연결에_실패하면_503용_예외로_변환한다() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> sender(unusedPort)
                .sendSignUpCode("receiver@example.com", "123456", 3))
                .isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
    }

    private SmtpVerificationEmailSender sender(int port) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(port);
        mailSender.getJavaMailProperties().put("mail.smtp.auth", "false");
        mailSender.getJavaMailProperties().put("mail.smtp.starttls.enable", "false");
        mailSender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "1000");
        mailSender.getJavaMailProperties().put("mail.smtp.timeout", "1000");

        return new SmtpVerificationEmailSender(
                mailSender,
                new EmailVerificationProperties(
                        "email-code-secret-not-used-in-this-test",
                        "ip-secret-not-used-in-this-test-value",
                        "no-reply@kiikick.test",
                        "KIIKICK"
                )
        );
    }

    private static final class TestSmtpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final CompletableFuture<byte[]> message = new CompletableFuture<>();
        private final Thread serverThread;

        private TestSmtpServer() throws Exception {
            serverSocket = new ServerSocket(0);
            serverThread = Thread.ofPlatform().start(this::serve);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        MimeMessage awaitMessage(Duration timeout) throws Exception {
            byte[] rawMessage = message.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new MimeMessage(
                    Session.getInstance(new Properties()),
                    new ByteArrayInputStream(rawMessage)
            );
        }

        private void serve() {
            try (Socket socket = serverSocket.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.US_ASCII));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.US_ASCII), true)) {
                writer.print("220 localhost test SMTP\r\n");
                writer.flush();

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("EHLO")) {
                        writer.print("250-localhost\r\n250-8BITMIME\r\n250 OK\r\n");
                    } else if (line.startsWith("HELO") || line.startsWith("MAIL FROM")
                            || line.startsWith("RCPT TO") || line.startsWith("RSET")) {
                        writer.print("250 OK\r\n");
                    } else if (line.equals("DATA")) {
                        writer.print("354 End data with <CR><LF>.<CR><LF>\r\n");
                        writer.flush();
                        StringBuilder data = new StringBuilder();
                        while ((line = reader.readLine()) != null && !line.equals(".")) {
                            data.append(line).append("\r\n");
                        }
                        message.complete(data.toString().getBytes(StandardCharsets.US_ASCII));
                        writer.print("250 queued\r\n");
                    } else if (line.equals("QUIT")) {
                        writer.print("221 bye\r\n");
                        writer.flush();
                        return;
                    } else {
                        writer.print("250 OK\r\n");
                    }
                    writer.flush();
                }
            } catch (Exception exception) {
                message.completeExceptionally(exception);
            }
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            serverThread.join(1000);
        }
    }
}
