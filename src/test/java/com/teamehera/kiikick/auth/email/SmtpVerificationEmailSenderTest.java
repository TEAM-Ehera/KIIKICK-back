package com.teamehera.kiikick.auth.email;

import com.teamehera.kiikick.auth.config.EmailVerificationProperties;
import com.teamehera.kiikick.auth.service.VerificationApiException;
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
    void 실제_SMTP_프로토콜로_인증번호_메일을_전송한다() throws Exception {
        try (TestSmtpServer server = new TestSmtpServer()) {
            SmtpVerificationEmailSender sender = sender(server.port());

            sender.sendSignUpCode("receiver@example.com", "123456", 3);

            MimeMessage received = server.awaitMessage(Duration.ofSeconds(5));
            assertThat(received.getAllRecipients()[0].toString())
                    .isEqualTo("receiver@example.com");
            assertThat(received.getSubject()).isEqualTo("[KIIKICK] 이메일 인증번호");
            assertThat(received.getContent().toString()).contains("123456", "3분");
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
