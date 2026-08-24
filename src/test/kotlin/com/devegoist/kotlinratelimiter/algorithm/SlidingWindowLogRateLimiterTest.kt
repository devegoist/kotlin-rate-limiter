package com.devegoist.kotlinratelimiter.algorithm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("SlidingWindowLogRateLimiter")
class SlidingWindowLogRateLimiterTest {

    @Nested
    @DisplayName("기본 동작")
    inner class BasicBehavior {

        @Test
        fun `윈도우 안에서 limit만큼 요청이 허용된다`() {
            val limiter = SlidingWindowLogRateLimiter(limit = 5, windowSizeMillis = 10_000)

            repeat(5) { i ->
                assertThat(limiter.tryAcquire("c"))
                    .describedAs("요청 ${i + 1}")
                    .isTrue()
            }
            assertThat(limiter.tryAcquire("c")).isFalse()
        }
    }

    @Nested
    @DisplayName("윈도우 슬라이딩")
    inner class Sliding {

        @Test
        fun `오래된 요청이 윈도우를 벗어나면 자리가 생긴다`() {
            val limiter = SlidingWindowLogRateLimiter(limit = 3, windowSizeMillis = 300)

            repeat(3) { limiter.tryAcquire("c") }
            assertThat(limiter.tryAcquire("c")).isFalse()

            // 윈도우가 지나가면 모든 기록이 만료됨
            Thread.sleep(350)

            assertThat(limiter.tryAcquire("c")).isTrue()
        }

        @Test
        fun `요청이 하나씩 만료되면 하나씩 통과한다`() {
            val limiter = SlidingWindowLogRateLimiter(limit = 2, windowSizeMillis = 400)

            // t=0: 2개 소진
            assertThat(limiter.tryAcquire("c")).isTrue()
            Thread.sleep(200)
            // t=200: 두 번째
            assertThat(limiter.tryAcquire("c")).isTrue()
            assertThat(limiter.tryAcquire("c")).isFalse()

            // t=450: 첫 번째(t=0)만 만료 → 자리 하나
            Thread.sleep(250)
            assertThat(limiter.tryAcquire("c")).isTrue()
            // 두 번째(t=200)는 아직 유효 → 다시 꽉 참
            assertThat(limiter.tryAcquire("c")).isFalse()
        }
    }

    @Nested
    @DisplayName("Fixed Window와의 차이 — 경계 문제 없음")
    inner class NoBoundarySpike {

        @Test
        fun `윈도우 경계를 걸쳐도 limit을 초과하지 않는다`() {
            val windowSize = 500L
            val limit = 5
            val limiter = SlidingWindowLogRateLimiter(limit = limit, windowSizeMillis = windowSize)

            // Fixed Window라면 2배가 통과했을 시나리오를 재현
            val now = System.currentTimeMillis()
            val untilBoundary = windowSize - (now % windowSize)
            Thread.sleep(untilBoundary - 50)

            val before = (1..5).count { limiter.tryAcquire("c") }
            assertThat(before).isEqualTo(5)

            Thread.sleep(60)   // 시계 기준 윈도우 경계를 넘김

            // Fixed Window는 여기서 5개가 더 통과했지만,
            // Sliding Window Log는 이전 요청들이 아직 윈도우 안에 있으므로 거부
            val after = (1..5).count { limiter.tryAcquire("c") }
            assertThat(after).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("클라이언트 격리")
    inner class ClientIsolation {

        @Test
        fun `서로 다른 클라이언트는 독립적인 로그를 가진다`() {
            val limiter = SlidingWindowLogRateLimiter(limit = 2, windowSizeMillis = 10_000)

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
        fun `동시 접근 시 정확히 limit만큼만 허용된다`() {
            val limit = 100
            val limiter = SlidingWindowLogRateLimiter(limit = limit, windowSizeMillis = 60_000)

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

            assertThat(allowed.get()).isEqualTo(limit)
        }
    }

    @Nested
    @DisplayName("입력 검증")
    inner class Validation {

        @Test
        fun `limit이 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { SlidingWindowLogRateLimiter(limit = 0, windowSizeMillis = 1000) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `windowSizeMillis가 0 이하이면 예외를 던진다`() {
            assertThatThrownBy { SlidingWindowLogRateLimiter(limit = 10, windowSizeMillis = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}