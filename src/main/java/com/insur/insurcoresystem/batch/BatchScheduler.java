package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 배치 예약 실행. 일일 지급 대사는 매일 새벽 전날 것을 돌린다({@code app.batch.recon-cron}, 서울 시간).
 * 결산은 담당자가 결산 자료를 확인한 뒤 직접 실행하므로 예약하지 않는다. {@code app.batch.scheduler-enabled=false} 면 끈다.
 * 서버가 여러 대면 대사가 대수만큼 돌 수 있으니 한 대에서만 켜거나 ShedLock 같은 분산 잠금을 붙인다.
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.batch.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class BatchScheduler {

    private final BatchService batchService;
    private final Clock clock;

    /** 보험료 자동이체: 매일 오늘 출금일로 (등록 의뢰·결과, 출금 의뢰·결과) */
    @Scheduled(cron = "${app.batch.cms-cron:0 0 7 * * *}", zone = "Asia/Seoul")
    public void dailyCmsDebit() {
        LocalDate today = LocalDate.now(clock);
        try {
            BatchService.Execution e = batchService.runCmsDebit(today, "system");
            log.info("보험료 자동이체 배치: {} → {}", today, e.status());
        } catch (BusinessException e) {
            log.warn("보험료 자동이체 배치를 시작하지 못함: {}", e.getErrorMessage());
        }
    }

    @Scheduled(cron = "${app.batch.recon-cron:0 30 6 * * *}", zone = "Asia/Seoul")
    public void dailyRecon() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        try {
            BatchService.Execution e = batchService.runPayoutRecon(yesterday, "system");
            log.info("일일 지급 대사: {} → {}", yesterday, e.status());
        } catch (BusinessException e) {
            log.warn("일일 지급 대사를 시작하지 못함: {}", e.getErrorMessage());
        }
    }
}
