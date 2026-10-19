package dev.repomind.limits;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Resolves API keys to configured clients by comparing SHA-256 hashes in constant time. */
@Component
public class ApiClients {

	private final LimitsProperties properties;

	public ApiClients(LimitsProperties properties) {
		this.properties = properties;
		for (LimitsProperties.Client client : properties.clients()) {
			if (client.keySha256() == null || !client.keySha256().matches("(?i)[0-9a-f]{64}")) {
				throw new IllegalStateException("repomind.limits client '" + client.name()
						+ "' needs key-sha256: the hex SHA-256 of its API key");
			}
		}
	}

	public Optional<ApiClient> forKey(String apiKey) {
		byte[] presented = sha256(apiKey).getBytes(StandardCharsets.US_ASCII);
		ApiClient match = null;
		// compare against every client so timing does not reveal which (if any) matched
		for (LimitsProperties.Client client : properties.clients()) {
			byte[] expected = client.keySha256().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
			if (MessageDigest.isEqual(presented, expected)) {
				match = new ApiClient(client.name(), client.policy());
			}
		}
		return Optional.ofNullable(match);
	}

	public Optional<ApiClient> anonymous(String remoteAddress) {
		return Optional.ofNullable(properties.anonymous())
			.map(policy -> new ApiClient("anonymous:" + remoteAddress, policy));
	}

	static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
