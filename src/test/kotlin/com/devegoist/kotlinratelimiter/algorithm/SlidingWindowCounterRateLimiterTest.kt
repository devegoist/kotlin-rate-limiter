package com.devegoist.kotlinratelimiter.algorithm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("SlidingWindowCounterRateLimiter")
class SlidingWindowCounterRateLimiterTest {

    @Nested
    @DisplayName("기본 동작")
    inner class BasicBehavior {

        @Test
        fun `윈도우 안에서 limit만큼 요청이 허용된다`() {
            val limiter = SlidingWindowCounterRateLimiter(limit = 5, windowSizeMillis = 10_000)

            repeat(5) { i ->
                assertThat(limiter.tryAcquire("c"))
                    .describedAs("요청 ${i + 1}")
                    .isTrue()
            }
            assertThat(limiter.tryAcquire("c")).isFalse()
        }
    }

    @Nested
    @DisplayName("경계 문제 완화")
    inner class BoundaryMitigation {

        @Test
        fun `윈도우 경계 직후에는 이전 카운트가 거의 그대로 반영된다`() {
            val windowSize = 1000L
            val limiter = SlidingWindowCounterRateLimiter(limit = 5, windowSizeMillis = windowSize)

            // 윈도우 끝자락으로 이동
            val now = System.currentTimeMillis()
            val untilBoundary = windowSize - (now % windowSize)
            Thread.sleep(untilBoundary - 50)

            // 5개 소진
            val before = (1..5).count { limiter.tryAcquire("c") }
            assertThat(before).isEqualTo(5)

            // 경계를 막 넘김 (진행률 ~1%)
            Thread.sleep(60)

            // Fixed Window라면 5개가 더 통과했겠지만,
            // 이전 윈도우 가중치가 거의 1.0이라 통과하지 못한다
            val after = (1..5).count { limiter.tryAcquire("c") }
            assertThat(after).isLessThanOrEqualTo(1)
        }

        @Test
        fun `윈도우 중반에는 이전 카운트가 절반만 반영된다`() {
            val windowSize = 1000L
            val limiter = SlidingWindowCounterRateLimiter(limit = 10, windowSizeMillis = windowSize)

            // 윈도우 시작 지점으로 정렬
            val now = System.currentTimeMillis()
            Thread.sleep(windowSize - (now % windowSize) + 10)

            // 현재 윈도우에서 10개 소진
            repeat(10) { limiter.tryAcquire("c") }
            assertThat(limiter.tryAcquire("c")).isFalse()

            // 다음 윈도우의 절반 지점으로 이동
            Thread.sleep(windowSize / 2 + windowSize - 10)

            // 추정치 = 10 × 0.5 + 0 = 5 → 5개 정도 여유
            val allowed = (1..10).count { limiter.tryAcquire("c") }
            assertThat(allowed).isBetween(4, 6)
        }
    }

    @Nested
    @DisplayName("윈도우 전환")
    inner class WindowRotation {

        @Test
        fun `두 윈도우 이상 유휴하면 카운트가 모두 만료된다`() {
            val windowSize = 200L
            val limiter = SlidingWindowCounterRateLimiter(limit = 3, windowSizeMillis = windowSize)

            repeat(3) { limiter.tryAcquire("c") }
            assertThat(limiter.tryAcquire("c")).isFalse()

            // 두 윈도우 이상 대기 → 이전/현재 모두 리셋
            Thread.sleep(windowSize * 3)

            val allowed = (1..3).count { limiter.tryAcquire("c") }
            assertThat(allowed).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("클라이언트 격리")
    inner class ClientIsolation {

        @Test
        fun `서로 다른 클라이언트는 독립적인 카운터를 가진다`() {
            val limiter = SlidingWindowCounterRateLimiter(limit = 2, windowSizeMillis = 10_000)

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
        fun `동시 접근 시 limit을 초과하지 않는다`() {
            val limit = 100L
            val limiter = SlidingWindowCounterRateLimiter(limit = limit, windowSizeMillis = 60_000)

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

            assertThat(allowed.get()).isEqualTo(limit.toInt())
        }
    }

    @Nested
    @DisplayName("입력 검증")
    inner class Validation {

        @Test
        fun `limit이 0 이하이면 예외를 던진다`() {
            assertThatThrownBy {
                SlidingWindowCounterRateLimiter(limit = 0, windowSizeMillis = 1000)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `windowSizeMillis가 0 이하이면 예외를 던진다`() {
            assertThatThrownBy {
                SlidingWindowCounterRateLimiter(limit = 10, windowSizeMillis = 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}