package dev.repomind.limits;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class LimitsWebConfig implements WebMvcConfigurer {

	private final LimitsInterceptor interceptor;

	public LimitsWebConfig(LimitsInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// GitHub authenticates webhook deliveries by signature and must never be rate limited away
		registry.addInterceptor(interceptor).addPathPatterns("/api/**").excludePathPatterns("/api/github/webhook");
	}

}
