package com.financeos.core.config;

import com.financeos.domain.inbox.InboxMemoEvictionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InboxMemoWebConfig implements WebMvcConfigurer {

    private final InboxMemoEvictionInterceptor interceptor;

    public InboxMemoWebConfig(InboxMemoEvictionInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
