package com.insur.insurcoresystem.ext.outbox;

import com.insur.insurcoresystem.ext.fep.FepAdminService;
import com.insur.insurcoresystem.service.payout.PayoutSender;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 대외기관 쪽 예약 작업. {@code app.ext.outbox.scheduler-enabled=false} 면 모두 끈다 (테스트는 끄고 직접 부른다).
 * <ul>
 *     <li>전송 대기열(신용정보원 집중)과 지급(은행 이체·결과 조회)을 {@code app.ext.outbox.interval-ms}(기본 10초)마다 처리한다.</li>
 *     <li>개시한 기관의 회선을 {@code app.ext.fep.echo-interval-ms}(기본 60초)마다 확인한다.</li>
 * </ul>
 * 건별로 빨리 끝나는 큐 처리라 스프링 배치가 아니라 예약 실행으로 한다. 대량·기준일 단위 작업(결산·대사)은 스프링 배치다.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ext.outbox.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxSender sender;
    private final PayoutSender payoutSender;
    private final FepAdminService fepAdminService;

    @Scheduled(fixedDelayString = "${app.ext.outbox.interval-ms:10000}", initialDelayString = "${app.ext.outbox.interval-ms:10000}")
    public void run() {
        sender.sendDue();
        payoutSender.processDue();
    }

    @Scheduled(fixedDelayString = "${app.ext.fep.echo-interval-ms:60000}", initialDelayString = "${app.ext.fep.echo-interval-ms:60000}")
    public void echo() {
        fepAdminService.echoAll();
    }
}
