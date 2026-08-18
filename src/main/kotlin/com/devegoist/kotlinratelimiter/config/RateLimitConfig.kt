package com.devegoist.kotlinratelimiter.config

import com.devegoist.kotlinratelimiter.algorithm.TokenBucketRateLimiter
import com.devegoist.kotlinratelimiter.core.RateLimiter
import com.devegoist.kotlinratelimiter.filter.RateLimiterInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class RateLimitConfig : WebMvcConfigurer {

    @Bean
    fun rateLimiter() : RateLimiter {
        // 여기서 구현체를 교체하면 알고리즘이 바뀐다 (전략 패턴)
        return TokenBucketRateLimiter(capacity = 20, refillRate = 10.0)
    }

    @Bean
    fun rateLimitInterceptor(rateLimiter: RateLimiter) : RateLimiterInterceptor {
        return RateLimiterInterceptor(rateLimiter)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimitInterceptor(rateLimiter()))
            .addPathPatterns("/api/**")
    }
}