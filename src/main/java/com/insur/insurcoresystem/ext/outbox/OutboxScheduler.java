package com.insur.insurcoresystem.ext.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 대기열을 주기적으로 보낸다 ({@code app.ext.outbox.interval-ms}, 기본 10초). {@code app.ext.outbox.scheduler-enabled=false} 면 끈다
 * (테스트는 끄고 {@link OutboxSender#sendDue()} 를 직접 부른다).
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ext.outbox.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxSender sender;

    @Scheduled(fixedDelayString = "${app.ext.outbox.interval-ms:10000}", initialDelayString = "${app.ext.outbox.interval-ms:10000}")
    public void run() {
        sender.sendDue();
    }
}
