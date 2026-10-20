package com.homes.zipsai.building.scheduler;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.building.service.RuleDocumentIndexingWorker;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnBooleanProperty("app.rule-document.indexing-scheduler.enabled")
@RequiredArgsConstructor
public class RuleDocumentIndexingScheduler {

    private final RuleDocumentIndexingWorker ruleDocumentIndexingWorker;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "ruleDocumentIndexingOutbox", lockAtMostFor = "15m")
    public void sendPendingIndexing() {
        ruleDocumentIndexingWorker.processDue();
    }
}
