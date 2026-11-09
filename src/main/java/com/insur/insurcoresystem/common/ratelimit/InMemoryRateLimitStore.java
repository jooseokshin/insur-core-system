package com.insur.insurcoresystem.common.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 서버 메모리 토큰 버킷. 시간은 업무 시계(Clock)가 아니라 단조 증가하는 nanoTime 을 쓴다 (시계 조정에 흔들리지 않게).
 * 버킷이 쌓이지 않도록 일정 호출마다 가득 찬 버킷을 지운다. 가득 찬 버킷은 새로 만든 것과 같으므로 지워도 동작이 같다.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final int SWEEP_EVERY = 1024;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final LongSupplier nanoTime;

    public InMemoryRateLimitStore() {
        this(System::nanoTime);
    }

    InMemoryRateLimitStore(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    @Override
    public Decision tryConsume(String key, RateLimitProperties.Limit limit) {
        long now = nanoTime.getAsLong();
        if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
            buckets.values().removeIf(bucket -> bucket.isFull(now));
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(limit, now)).tryConsume(limit, now);
    }

    int size() {
        return buckets.size();
    }

    /**
     * 토큰은 정수로 센다. 토큰 1개 = NANOS_PER_MINUTE 단위라서, 경과 나노초 × 분당 개수가 그대로 더할 양이 된다 (부동소수 오차 없음).
     */
    private static final class Bucket {
        private static final long ONE = NANOS_PER_MINUTE;

        private long units;
        private long lastNanos;
        private RateLimitProperties.Limit limit;

        Bucket(RateLimitProperties.Limit limit, long now) {
            this.limit = limit;
            this.units = limit.capacity() * ONE;
            this.lastNanos = now;
        }

        synchronized Decision tryConsume(RateLimitProperties.Limit current, long now) {
            limit = current;
            refill(now);
            if (units >= ONE) {
                units -= ONE;
                return new Decision(true, units / ONE, 0);
            }
            long waitNanos = ceilDiv(ONE - units, limit.perMinute());
            return new Decision(false, 0, Math.max(1, ceilDiv(waitNanos, 1_000_000_000L)));
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return units >= limit.capacity() * ONE;
        }

        private void refill(long now) {
            long max = limit.capacity() * ONE;
            long missing = Math.max(0, max - units);
            // 가득 채우는 데 필요한 시간까지만 곱해서 long 넘침을 막는다
            long elapsed = Math.min(Math.max(0, now - lastNanos), ceilDiv(missing, limit.perMinute()));
            units = Math.min(max, units + elapsed * limit.perMinute());
            lastNanos = now;
        }

        private static long ceilDiv(long a, long b) {
            return (a + b - 1) / b;
        }
    }
}
