package org.acme.library.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param loanPeriod how long a book may be kept
 * @param finePerDay charged for each overdue day after the member's grace period
 * @param maxFinePerLoan cap on the fine for a single loan
 * @param suspensionThreshold unpaid fines at or above this suspend the member
 * @param maxRenewals renewals allowed per loan
 * @param reminderLeadTime how long before the due date a reminder email is sent
 */
@ConfigurationProperties("library")
public record LibraryProperties(
		@DefaultValue("14d") Duration loanPeriod,
		@DefaultValue("0.25") BigDecimal finePerDay,
		@DefaultValue("10.00") BigDecimal maxFinePerLoan,
		@DefaultValue("5.00") BigDecimal suspensionThreshold,
		@DefaultValue("2") int maxRenewals,
		@DefaultValue("2d") Duration reminderLeadTime) {
}
