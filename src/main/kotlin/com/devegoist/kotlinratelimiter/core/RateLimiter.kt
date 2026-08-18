package com.devegoist.kotlinratelimiter.core

/**
 * 처리율 제한 알고리즘의 공통 인터페이스.
 *
 * 전략 패턴(Strategy Pattern)으로 설계되어 있어서
 * 구현체(Token Bucket, Leaky Bucket 등)를 설정만으로 교체할 수 있다.
 */
interface RateLimiter {

    /**
     * 요청 1건의 처리를 허용할지 판단한다.
     *
     * @param key 클라이언트 식별자 (IP, API Key, User ID 등)
     * @return true = 허용, false = 거부(rate limited)
     */
    fun tryAcquire(key: String): Boolean

    /**
     * 알고리즘 이름과 설정값을 반환한다. (로깅/모니터링용)
     */
    fun algorithmName(): String

}