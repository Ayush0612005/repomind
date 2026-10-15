package dev.repomind;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RepomindApplication {

	public static void main(String[] args) {
		// The JDBC driver sends the JVM zone to Postgres on connect; legacy host zone ids
		// (e.g. Windows' "Asia/Calcutta") are rejected by newer tzdata. Timestamps are stored
		// as timestamptz anyway, so the service runs in UTC regardless of host.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(RepomindApplication.class, args);
	}

}
