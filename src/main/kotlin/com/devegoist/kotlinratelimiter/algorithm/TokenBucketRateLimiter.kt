package com.devegoist.kotlinratelimiter.algorithm

import com.devegoist.kotlinratelimiter.core.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Token Bucket 알고리즘.
 *
 * ## 동작 원리
 * 버킷에 토큰이 일정 속도(refillRate)로 채워지고, 요청마다 토큰 1개를 소비한다.
 * 버킷이 비어있으면 요청을 거부한다.
 * 토큰이 capacity까지 쌓일 수 있기 때문에 짧은 버스트 트래픽을 허용한다.
 *
 * ```
 *   ┌────────────────┐
 *   │  ● ● ● ● ●    │  ← capacity (최대 토큰)
 *   │  ● ● ●        │  ← 현재 토큰
 *   └──────┬─────────┘
 *          │ 요청당 1개 소비
 *          ▼
 *    [Request] → 허용 / 거부
 *
 *   충전: 초당 refillRate개 (capacity 초과 불가)
 * ```
 *
 * ## Lazy Refill
 * 토큰 충전을 위해 백그라운드 스레드를 돌리지 않는다.
 * 대신 tryAcquire() 호출 시점에 "마지막 호출 이후 경과 시간 × 충전 속도"로 계산한다.
 * 클라이언트가 10만 명이어도 타이머가 0개 → 메모리/CPU 효율적.
 *
 * ## 스레드 안전성
 * 버킷 상태를 불변 객체(data class)로 관리하고, AtomicReference + CAS 루프로 갱신한다.
 * synchronized 같은 락 없이 논블로킹으로 동시성을 처리한다.
 *
 * ## 실무 사용 사례
 * AWS API Gateway, Stripe API, GitHub API 등 대부분의 API rate limiting에서 사용.
 *
 * @param capacity   버킷 최대 토큰 수 (= 허용 가능한 최대 버스트 크기)
 * @param refillRate 초당 충전되는 토큰 수
 */
class TokenBucketRateLimiter(
    private val capacity: Long,
    private val refillRate: Double
) : RateLimiter {

    init {
        require(capacity > 0) { "capacity는 양수여야 합니다: $capacity" }
        require(refillRate > 0) { "refillRate는 양수여야 합니다: $refillRate" }
    }

    /**
     * 버킷의 순간 상태를 담는 불변 스냅샷.
     *
     * data class로 만든 이유:
     * - 불변이라 CAS 교체 시 방어적 복사가 필요 없다.
     * - equals/hashCode가 자동 생성되어 AtomicReference.compareAndSet()이 정확히 동작한다.
     */
    private data class Bucket(
        val tokens: Double,
        val lastRefillNanos: Long
    )

    /**
     * 클라이언트별 버킷 저장소.
     *
     * 2단계 동시성 제어:
     * - ConcurrentHashMap: 서로 다른 키(클라이언트) 간에는 락 경합 없음
     * - AtomicReference: 같은 키의 동시 요청은 CAS 루프로 처리
     */
    private val buckets = ConcurrentHashMap<String, AtomicReference<Bucket>>()

    override fun tryAcquire(key: String): Boolean {
        val bucketRef = buckets.computeIfAbsent(key) {
            // 신규 클라이언트 → 풀 버킷으로 시작
            AtomicReference(Bucket(tokens = capacity.toDouble(), lastRefillNanos = System.nanoTime()))
        }

        // CAS 루프: 다른 스레드가 먼저 갱신했으면 최신 상태로 재시도
        while (true) {
            val current = bucketRef.get()
            val now = System.nanoTime()

            // ── Lazy Refill ──
            // 마지막 접근 이후 경과 시간만큼 토큰 충전 (capacity 초과 방지)
            val elapsedSeconds = (now - current.lastRefillNanos) / 1_000_000_000.0
            val refilled = (current.tokens + elapsedSeconds * refillRate)
                .coerceAtMost(capacity.toDouble())


            // 토큰 부족 -> 거부
            if (refilled < 1.0) {
                return false
            }

            // 토큰 1개 소비한 새 상태 생성 (불변 -> 새 객체)
            val updated = Bucket(tokens = refilled - 1.0, lastRefillNanos = now)

            // CAS: current가 아직 바뀌지 않았을 때만 updated로 교체
            // 성공하면 true 반환, 실패하면 while 루프에서 재시도
            if (bucketRef.compareAndSet(current, updated)) {
                return true
            }
        }
    }

    override fun algorithmName(): String =
        "TokenBucket(capacity=$capacity, refillRate=$refillRate)"
}