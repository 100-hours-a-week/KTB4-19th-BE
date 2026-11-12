package com.homes.zipsai.building.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker.Outcome;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BuildingCoordinateRetryScheduler {

    private final BuildingCoordinateRetryWorker buildingCoordinateRetryWorker;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(cron = "${app.building.coordinate-retry.cron:0 0 * * * *}", zone = "Asia/Seoul",
        scheduler = "buildingCoordinateRetryTaskScheduler")
    public void retryCoordinates() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (buildingCoordinateRetryWorker.executeNext() != Outcome.CONTINUE) {
                    return;
                }
            }
        } finally {
            running.set(false);
        }
    }
}
