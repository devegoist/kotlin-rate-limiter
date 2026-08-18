package com.devegoist.kotlinratelimiter.filter

import com.devegoist.kotlinratelimiter.core.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.HandlerInterceptor

/**
 * HTTP 요청에 처리율 제한을 적용하는 Spring MVC Interceptor.
 *
 * 클라이언트 식별 우선순위:
 * 1. X-API-Key 헤더 (머신 클라이언트)
 * 2. Spring Security 인증 Principal (로그인 사용자)
 * 3. X-Forwarded-For 첫 번째 IP (리버스 프록시 환경)
 * 4. remoteAddr (직접 연결)
 *
 * 제한 초과 시 HTTP 429 Too Many Requests를 반환한다.
 */
class RateLimiterInterceptor(
    private val rateLimiter: RateLimiter
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val clientKey = resolveClientKey(request)

        if (!rateLimiter.tryAcquire(clientKey)) {
            log.warn("Rate limit exceeded for client: client={}, uri={}, algorithm={}",
                clientKey, request.requestURI, rateLimiter.algorithmName())

            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.writer.write(
                """{"error": "Too Many Requests", "message": "Rate limit exceeded"}"""
            )
            return false
        }

        return super.preHandle(request, response, handler)
    }

    private fun resolveClientKey(request: HttpServletRequest): String {
        request.getHeader("X-API-Key")?.let { return "apikey:$it" }
        request.userPrincipal?.name?.let { return "user:$it" }
        request.getHeader("X-Forwareded-For")
            ?.split(",")?.firstOrNull()?.trim()
            ?.let { return "ip:$it" }
        return "ip:${request.remoteAddr}"
    }
}