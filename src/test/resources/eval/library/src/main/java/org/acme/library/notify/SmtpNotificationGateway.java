package org.acme.library.notify;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends email through SMTP, retrying transient failures up to three times with exponential
 * backoff (1s, 2s, 4s). After the last attempt the failure is logged and swallowed: a missed
 * reminder must not fail the batch job that triggered it.
 */
@Component
public class SmtpNotificationGateway implements NotificationGateway {

	private static final Logger log = LoggerFactory.getLogger(SmtpNotificationGateway.class);

	private static final int MAX_ATTEMPTS = 3;

	private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

	private final JavaMailSender mail;

	public SmtpNotificationGateway(JavaMailSender mail) {
		this.mail = mail;
	}

	@Override
	public void send(String recipient, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom("noreply@library.example");
		message.setTo(recipient);
		message.setSubject(subject);
		message.setText(body);

		Duration backoff = INITIAL_BACKOFF;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				mail.send(message);
				return;
			}
			catch (MailException ex) {
				if (attempt == MAX_ATTEMPTS) {
					log.error("Giving up on email to {} after {} attempts: {}", recipient, attempt, ex.getMessage());
					return;
				}
				sleep(backoff);
				backoff = backoff.multipliedBy(2);
			}
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
