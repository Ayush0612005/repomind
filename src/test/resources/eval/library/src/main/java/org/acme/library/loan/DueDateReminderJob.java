package org.acme.library.loan;

import java.time.Clock;
import java.time.LocalDate;

import org.acme.library.config.LibraryProperties;
import org.acme.library.member.MemberRepository;
import org.acme.library.notify.NotificationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Emails members whose loans are about to fall due. Runs every morning at 08:00. */
@Component
public class DueDateReminderJob {

	private static final Logger log = LoggerFactory.getLogger(DueDateReminderJob.class);

	private final LoanRepository loans;

	private final MemberRepository members;

	private final NotificationGateway notifications;

	private final LibraryProperties properties;

	private final Clock clock;

	public DueDateReminderJob(LoanRepository loans, MemberRepository members, NotificationGateway notifications,
			LibraryProperties properties, Clock clock) {
		this.loans = loans;
		this.members = members;
		this.notifications = notifications;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 8 * * *")
	public void sendReminders() {
		LocalDate horizon = LocalDate.now(clock).plusDays(properties.reminderLeadTime().toDays());
		int sent = 0;
		for (Loan loan : loans.findDueForReminder(horizon)) {
			var member = members.findById(loan.memberId());
			if (member.isEmpty()) {
				continue;
			}
			// mark first so a crash mid-run does not send the same reminder twice
			loan.markReminderSent();
			loans.save(loan);
			notifications.send(member.get().email(), "Your library book is due on " + loan.dueOn(),
					"Please return or renew " + loan.isbn() + " by " + loan.dueOn() + ".");
			sent++;
		}
		log.info("Sent {} due-date reminders", sent);
	}

}
