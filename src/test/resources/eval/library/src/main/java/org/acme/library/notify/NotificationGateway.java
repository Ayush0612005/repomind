package org.acme.library.notify;

public interface NotificationGateway {

	void send(String recipient, String subject, String body);

}
