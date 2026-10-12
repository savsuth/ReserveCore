package io.github.savsuth.reservecore.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor apiKeyAuth;

    WebConfig(ApiKeyAuthInterceptor apiKeyAuth) {
        this.apiKeyAuth = apiKeyAuth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyAuth).addPathPatterns("/v1/**");
    }
}
