package com.example.ratelimiter.algorithm

import com.devegoist.kotlinratelimiter.core.RateLimiter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Leaky Bucket 알고리즘.
 *
 * ## 동작 원리
 * 요청이 버킷(큐)에 쌓이고, 일정 속도(leakRate)로 빠져나간다.
 * 버킷이 가득 차면 새 요청은 버려진다(overflow).
 *
 * ```
 *      요청 도착 (불규칙)
 *       ↓ ↓  ↓↓↓
 *    ┌──────────┐
 *    │ ■ ■ ■    │  ← 대기 중인 요청 (capacity까지)
 *    └─────┬────┘
 *          ↓ leakRate로 일정하게 누출
 *      ·  ·  ·  ·   ← 처리 (항상 균일한 간격)
 * ```
 *
 * ## Token Bucket과의 차이
 * - Token Bucket: 토큰이 쌓임 → 버스트 허용
 * - Leaky Bucket: 요청이 쌓임 → 버스트 불허, 출력이 항상 일정
 *
 * ## 가상 큐 방식
 * 실제 Queue와 워커 스레드를 두지 않고, "경과 시간만큼 누출됐다"고
 * 계산하는 방식으로 구현했다. Token Bucket의 lazy refill과 같은 아이디어.
 *
 * 실제 큐 방식(BlockingQueue + 워커 스레드)은 요청을 대기시켰다가
 * 처리하는 비동기 구조에 적합하며, 즉시 허용/거부를 판단하는
 * HTTP rate limiter에는 가상 큐 방식이 맞다.
 *
 * ## 실무 사용 사례
 * - 백엔드 처리량이 고정된 경우 (DB 커넥션 풀, 외부 API 호출 제한)
 * - 트래픽 셰이핑이 필요한 네트워크 장비
 *
 * @param capacity 버킷에 담을 수 있는 최대 요청 수 (큐 크기)
 * @param leakRate 초당 처리(누출)되는 요청 수
 */
class LeakyBucketRateLimiter(
    private val capacity: Long,
    private val leakRate: Double
) : RateLimiter {

    init {
        require(capacity > 0) { "capacity는 양수여야 합니다: $capacity" }
        require(leakRate > 0) { "leakRate는 양수여야 합니다: $leakRate" }
    }

    /**
     * 버킷 상태 스냅샷.
     *
     * Token Bucket의 Bucket과 구조는 같지만 의미가 반대다.
     * - Token Bucket의 tokens: 남은 "권한" (많을수록 여유)
     * - Leaky Bucket의 queueSize: 대기 중인 "요청량" (많을수록 혼잡)
     */
    private data class Bucket(
        val queueSize: Double,      // 현재 버킷에 차 있는 물의 양 (= 대기 중인 요청 수)
        val lastLeakNanos: Long
    )

    private val buckets = ConcurrentHashMap<String, AtomicReference<Bucket>>()

    override fun tryAcquire(key: String): Boolean {
        val bucketRef = buckets.computeIfAbsent(key) {
            // 신규 클라이언트 → 빈 버킷으로 시작 (Token Bucket과 반대!)
            AtomicReference(Bucket(queueSize = 0.0, lastLeakNanos = System.nanoTime()))
        }

        while (true) {
            val current = bucketRef.get()
            val now = System.nanoTime()

            // 경과 시간만큼 큐에서 빠져나갔다고 계산
            val elapsedSeconds = (now - current.lastLeakNanos) / 1_000_000_000.0
            val leaked = (current.queueSize - elapsedSeconds * leakRate)
                .coerceAtLeast(0.0)

            // 물 1단위를 추가했을 때 버킷이 넘치는지 확인
            if (leaked + 1.0 > capacity) {
                return false  // overflow → 요청 버림
            }

            val updated = Bucket(queueSize = leaked + 1.0, lastLeakNanos = now)

            if (bucketRef.compareAndSet(current, updated)) {
                return true
            }
        }
    }

    override fun algorithmName(): String =
        "LeakyBucket(capacity=$capacity, leakRate=$leakRate/s)"
}