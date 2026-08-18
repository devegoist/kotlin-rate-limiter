package com.devegoist.kotlinratelimiter.algorithm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("TokenBucketRateLimiter")
class TokenBucketRateLimiterTest {

    @Nested
    @DisplayName("기본 동작")
    inner class BasicBehavior {

        @Test
        fun `버킷 용량만큼 연속 요청이 허용된다`() {
            val limiter = TokenBucketRateLimiter(5, 1.0)

            repeat(5) { i ->
                assertThat(limiter.tryAcquire("user-1"))
                    .describedAs { "요청 ${i + 1}" }
                    .isTrue
            }
            assertThat(limiter.tryAcquire("user-1")).isFalse
        }

        @Test
        fun `버킷이 비면 요청을 거부한다`() {
            val limiter = TokenBucketRateLimiter(capacity = 1, refillRate = 0.001)

            assertThat(limiter.tryAcquire("c")).isTrue()
            assertThat(limiter.tryAcquire("c")).isFalse()
            assertThat(limiter.tryAcquire("c")).isFalse()
        }

    }

    @Nested
    @DisplayName("Lazy Refill")
    inner class LazyRefill {

        @Test
        fun `시간이 지나면 토큰이 충전된다`() {
            val limiter = TokenBucketRateLimiter(capacity = 3, refillRate = 10.0)

            repeat(3) { limiter.tryAcquire("c") }
            assertThat(limiter.tryAcquire("c")).isFalse()

            // 300ms 대기 → 10 * 0.3 = 약 3개 충전
            Thread.sleep(300)

            assertThat(limiter.tryAcquire("c")).isTrue()
        }

        @Test
        fun `오래 방치해도 capacity를 초과해서 충전되지 않는다`() {
            val limiter = TokenBucketRateLimiter(capacity = 5, refillRate = 100.0)

            Thread.sleep(500) // 이론상 50개지만 capacity=5로 제한

            val allowed = (1..10).count { limiter.tryAcquire("c") }
            assertThat(allowed).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("클라이언트 격리")
    inner class ClientIsolation {

        @Test
        fun `서로 다른 클라이언트는 독립적인 버킷을 가진다`() {
            val limiter = TokenBucketRateLimiter(capacity = 2, refillRate = 0.001)

            repeat(2) { limiter.tryAcquire("alice") }
            assertThat(limiter.tryAcquire("alice")).isFalse()

            assertThat(limiter.tryAcquire("bob")).isTrue()
            assertThat(limiter.tryAcquire("bob")).isTrue()
            assertThat(limiter.tryAcquire("bob")).isFalse()
        }
    }

    @Nested
    @DisplayName("스레드 안전성")
    inner class ThreadSafety {

        @Test
        fun `50개 스레드가 동시에 접근해도 정확히 capacity만큼만 허용된다`() {
            val capacity = 100L
            val limiter = TokenBucketRateLimiter(capacity = capacity, refillRate = 0.0001)

            val threads = 50
            val requestsPerThread = 10
            val executor = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(threads)
            val allowed = AtomicInteger(0)

            repeat(threads) {
                executor.submit {
                    try {
                        repeat(requestsPerThread) {
                            if (limiter.tryAcquire("shared")) allowed.incrementAndGet()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()

            assertThat(allowed.get()).isEqualTo(capacity.toInt())
        }
    }

    @Nested
    @DisplayName("입력 검증")
    inner class Validation {

        @Test
        fun `capacity가 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { TokenBucketRateLimiter(capacity = 0, refillRate = 1.0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `refillRate가 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { TokenBucketRateLimiter(capacity = 10, refillRate = -1.0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

}