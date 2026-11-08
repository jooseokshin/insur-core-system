package com.insur.insurcoresystem.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 토큰 버킷 계산 검증. 시간은 가짜 nanoTime 으로 움직인다. */
class InMemoryRateLimitStoreTest {

    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(0);
    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore(now::get);
    /** 3개까지 몰아 보낼 수 있고 분당 60개(1초에 1개) 찬다 */
    private final RateLimitProperties.Limit limit = new RateLimitProperties.Limit(3, 60);

    @Test
    void 버킷_크기만큼_허용하고_다음은_거절한다() {
        assertThat(store.tryConsume("k", limit).remaining()).isEqualTo(2);
        assertThat(store.tryConsume("k", limit).remaining()).isEqualTo(1);
        assertThat(store.tryConsume("k", limit).remaining()).isZero();
        RateLimitStore.Decision denied = store.tryConsume("k", limit);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void 시간이_지나면_분당_속도로_다시_찬다() {
        for (int i = 0; i < 3; i++) {
            store.tryConsume("k", limit);
        }
        now.addAndGet(SECOND / 2);
        assertThat(store.tryConsume("k", limit).allowed()).isFalse();
        now.addAndGet(SECOND / 2);
        assertThat(store.tryConsume("k", limit).allowed()).isTrue();
        assertThat(store.tryConsume("k", limit).allowed()).isFalse();
        // 오래 쉬어도 버킷 크기 이상은 쌓이지 않는다
        now.addAndGet(3600 * SECOND);
        for (int i = 0; i < 3; i++) {
            assertThat(store.tryConsume("k", limit).allowed()).isTrue();
        }
        assertThat(store.tryConsume("k", limit).allowed()).isFalse();
    }

    @Test
    void 키마다_버킷이_따로다() {
        for (int i = 0; i < 3; i++) {
            store.tryConsume("a", limit);
        }
        assertThat(store.tryConsume("a", limit).allowed()).isFalse();
        assertThat(store.tryConsume("b", limit).allowed()).isTrue();
    }

    @Test
    void 대기_시간은_부족한_만큼을_초_단위로_올림한다() {
        RateLimitProperties.Limit slow = new RateLimitProperties.Limit(1, 2); // 30초에 1개
        store.tryConsume("k", slow);
        now.addAndGet(10 * SECOND);
        assertThat(store.tryConsume("k", slow).retryAfterSeconds()).isEqualTo(20);
    }

    @Test
    void 가득_찬_버킷은_정리되고_쓰던_버킷은_남는다() {
        for (int i = 0; i < 500; i++) {
            store.tryConsume("idle-" + i, limit);
        }
        now.addAndGet(10 * SECOND); // idle 버킷은 모두 다시 가득 찬다
        for (int i = 0; i < 524; i++) {
            store.tryConsume("busy", limit); // 1024번째 호출에서 정리
        }
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.tryConsume("busy", limit).allowed()).isFalse();
    }
}
