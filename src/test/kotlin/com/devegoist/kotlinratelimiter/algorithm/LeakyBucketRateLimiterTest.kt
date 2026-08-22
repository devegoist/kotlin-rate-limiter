package com.example.ratelimiter.algorithm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("LeakyBucketRateLimiter")
class LeakyBucketRateLimiterTest {

    @Nested
    @DisplayName("기본 동작")
    inner class BasicBehavior {

        @Test
        fun `버킷 용량만큼 요청을 받아들인다`() {
            val limiter = LeakyBucketRateLimiter(capacity = 5, leakRate = 0.001)

            repeat(5) { i ->
                assertThat(limiter.tryAcquire("c"))
                    .describedAs("요청 ${i + 1}")
                    .isTrue()
            }
            // 6번째는 overflow
            assertThat(limiter.tryAcquire("c")).isFalse()
        }
    }

    @Nested
    @DisplayName("누출")
    inner class Leaking {

        @Test
        fun `시간이 지나면 물이 빠져 다시 요청을 받을 수 있다`() {
            val limiter = LeakyBucketRateLimiter(capacity = 3, leakRate = 10.0)

            repeat(3) { limiter.tryAcquire("c") }
            assertThat(limiter.tryAcquire("c")).isFalse()

            // 300ms 대기 → 초당 10개 누출이므로 약 3개 빠짐
            Thread.sleep(300)

            assertThat(limiter.tryAcquire("c")).isTrue()
        }

        @Test
        fun `물은 0 미만으로 내려가지 않는다`() {
            val limiter = LeakyBucketRateLimiter(capacity = 5, leakRate = 100.0)

            limiter.tryAcquire("c")

            // 오래 방치 → 이론상 -50이지만 0에서 멈춤
            Thread.sleep(500)

            // 빈 버킷이므로 capacity만큼 다시 받을 수 있어야 함
            val allowed = (1..10).count { limiter.tryAcquire("c") }
            assertThat(allowed).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("Token Bucket과의 차이")
    inner class DifferenceFromTokenBucket {

        @Test
        fun `유휴 시간이 길어도 버스트가 늘어나지 않는다`() {
            val leaky = LeakyBucketRateLimiter(capacity = 5, leakRate = 10.0)

            // 1초 방치 (Token Bucket이면 토큰이 가득 찼을 시간)
            Thread.sleep(1000)

            // 여전히 capacity(5)까지만 허용 — 그 이상 몰아 쓸 수 없다
            val allowed = (1..20).count { leaky.tryAcquire("c") }
            assertThat(allowed).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("클라이언트 격리")
    inner class ClientIsolation {

        @Test
        fun `서로 다른 클라이언트는 독립적인 버킷을 가진다`() {
            val limiter = LeakyBucketRateLimiter(capacity = 2, leakRate = 0.001)

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
        fun `동시 접근 시 정확히 capacity만큼만 허용된다`() {
            val capacity = 100L
            val limiter = LeakyBucketRateLimiter(capacity = capacity, leakRate = 0.0001)

            val threads = 50
            val executor = Executors.newFixedThreadPool(threads)
            val latch = CountDownLatch(threads)
            val allowed = AtomicInteger(0)

            repeat(threads) {
                executor.submit {
                    try {
                        repeat(10) {
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
            assertThatThrownBy { LeakyBucketRateLimiter(capacity = 0, leakRate = 1.0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `leakRate가 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { LeakyBucketRateLimiter(capacity = 5, leakRate = 0.0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}