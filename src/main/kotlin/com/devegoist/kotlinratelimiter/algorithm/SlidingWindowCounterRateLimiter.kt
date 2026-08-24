package com.devegoist.kotlinratelimiter.algorithm

import com.devegoist.kotlinratelimiter.core.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Sliding Window Counter 알고리즘.
 *
 * ## 동작 원리
 * Fixed Window처럼 고정 구간으로 나누되, 카운터를 이전/현재 두 개 유지한다.
 * 이전 윈도우의 카운트를 현재 진행률에 따라 가중 반영해서 추정치를 계산한다.
 *
 * ```
 * 추정치 = 이전 윈도우 카운트 × (1 - 현재 윈도우 진행률) + 현재 윈도우 카운트
 *
 * 제한 = 100회/분, 12:01:15 시점 (현재 윈도우 25% 진행)
 *
 *      이전(80회)        현재(30회)
 *   ┌──────────────┬──────────────┐
 * 12:00          12:01     ↑    12:02
 *            ┗━━━━━━━━━━━━━┛
 *              슬라이딩 윈도우
 *              (75%가 이전 윈도우에 걸침)
 *
 * 추정치 = 80 × 0.75 + 30 = 90 → 허용
 * ```
 *
 * ## 장점
 * - Fixed Window의 경계 문제를 크게 완화한다
 * - 메모리는 O(1) — 클라이언트당 카운터 2개
 * - Redis로 옮기기 쉽다 (카운터 두 개만 관리)
 *
 * ## 한계: 근사치
 * 이전 윈도우의 요청이 균등 분포한다고 가정한다.
 * 실제 분포가 한쪽에 몰려 있으면 오차가 발생하지만,
 * 실측에서는 오차율이 1% 미만으로 보고된다.
 *
 * ## 세 알고리즘의 위치
 * - Fixed Window:  메모리 O(1), 경계에서 2배 초과
 * - Sliding Log:   메모리 O(limit), 완벽히 정확
 * - Sliding Counter: 메모리 O(1), 근사적으로 정확  ← 실무 표준
 *
 * @param limit            윈도우당 허용 요청 수
 * @param windowSizeMillis 윈도우 크기 (밀리초)
 */
class SlidingWindowCounterRateLimiter(
    private val limit: Long,
    private val windowSizeMillis: Long
) : RateLimiter {

    init {
        require(limit > 0) { "limit은 양수여야 합니다: $limit" }
        require(windowSizeMillis > 0) { "windowSizeMillis는 양수여야 합니다: $windowSizeMillis" }
    }

    /**
     * 윈도우 상태 스냅샷.
     *
     * Fixed Window와 달리 이전 윈도우의 카운트도 함께 보관한다.
     * 이것이 경계 문제를 완화하는 핵심.
     */
    private data class Windows(
        val windowIndex: Long,      // 현재 윈도우 번호
        val currentCount: Long,     // 현재 윈도우에서 처리한 요청 수
        val previousCount: Long     // 직전 윈도우에서 처리한 요청 수
    )

    private val windows = ConcurrentHashMap<String, AtomicReference<Windows>>()

    override fun tryAcquire(key: String): Boolean {
        val windowRef = windows.computeIfAbsent(key) {
            AtomicReference(Windows(windowIndex = -1, currentCount = 0, previousCount = 0))
        }

        while (true) {
            val current = windowRef.get()
            val now = System.currentTimeMillis()
            val currentIndex = now / windowSizeMillis

            // ── 윈도우 전환 처리 ──
            // 현재 → 이전으로 밀어내기 (몇 칸 건너뛰었는지에 따라 다름)
            val (currentCount, previousCount) = when (currentIndex - current.windowIndex) {
                0L -> current.currentCount to current.previousCount        // 같은 윈도우
                1L -> 0L to current.currentCount                           // 한 칸 이동
                else -> 0L to 0L                                           // 오래 유휴 → 전부 만료
            }

            // ── 가중 추정치 계산 ──
            // 현재 윈도우가 얼마나 진행됐는가 (0.0 ~ 1.0)
            val elapsedRatio = (now % windowSizeMillis).toDouble() / windowSizeMillis
            // 슬라이딩 윈도우가 이전 윈도우와 겹치는 비율
            val previousWeight = 1.0 - elapsedRatio

            val estimated = previousCount * previousWeight + currentCount

            if (estimated >= limit) {
                return false
            }

            val updated = Windows(
                windowIndex = currentIndex,
                currentCount = currentCount + 1,
                previousCount = previousCount
            )

            if (windowRef.compareAndSet(current, updated)) {
                return true
            }
        }
    }

    override fun algorithmName(): String =
        "SlidingWindowCounter(limit=$limit, window=${windowSizeMillis}ms)"
}