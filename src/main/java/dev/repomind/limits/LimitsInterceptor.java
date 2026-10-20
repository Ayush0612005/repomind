package dev.repomind.limits;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Identifies the caller of every {@code /api} request from its {@code X-Api-Key} header (or
 * {@code Authorization: Bearer}), applies the client's rate limit, and stores the client as a
 * request attribute for the token budget. Rejections are thrown so they get the API's usual
 * problem-detail responses.
 */
@Component
public class LimitsInterceptor implements HandlerInterceptor {

	static final String API_KEY_HEADER = "X-Api-Key";

	private final LimitsProperties properties;

	private final ApiClients clients;

	private final RateLimiter rateLimiter;

	public LimitsInterceptor(LimitsProperties properties, ApiClients clients, RateLimiter rateLimiter) {
		this.properties = properties;
		this.clients = clients;
		this.rateLimiter = rateLimiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!properties.enabled()) {
			request.setAttribute(ApiClient.ATTRIBUTE, ApiClient.UNLIMITED);
			return true;
		}
		ApiClient client = identify(request);
		request.setAttribute(ApiClient.ATTRIBUTE, client);
		if (client.anonymous() && handler instanceof HandlerMethod method
				&& (method.hasMethodAnnotation(RequiresApiKey.class)
						|| method.getBeanType().isAnnotationPresent(RequiresApiKey.class))) {
			throw new InvalidApiKeyException("This endpoint requires an API key (header " + API_KEY_HEADER + ")");
		}

		RateLimiter.Decision decision = rateLimiter.acquire(client);
		response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
		if (decision.remaining() >= 0) {
			response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
		}
		if (!decision.allowed()) {
			throw new RateLimitedException(decision);
		}
		return true;
	}

	private ApiClient identify(HttpServletRequest request) {
		String key = apiKey(request);
		if (key == null) {
			return clients.anonymous(request.getRemoteAddr())
				.orElseThrow(() -> new InvalidApiKeyException("An API key is required (header " + API_KEY_HEADER + ")"));
		}
		return clients.forKey(key).orElseThrow(() -> new InvalidApiKeyException("Unknown API key"));
	}

	private static String apiKey(HttpServletRequest request) {
		String key = request.getHeader(API_KEY_HEADER);
		if (StringUtils.hasText(key)) {
			return key.strip();
		}
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String token = authorization.substring(7).strip();
			return token.isEmpty() ? null : token;
		}
		return null;
	}

}
