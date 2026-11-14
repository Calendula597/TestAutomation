package io.github.calendula597.testplatform.platformserver.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reclaims timed-out and abandoned attempts: jobs past their timeout
 * become TIMED_OUT, silent workers lose their leases, and only
 * side-effect-free jobs are requeued automatically.
 */
@Component
public class WorkerLeaseSweeper {

    private final JobQueueService jobQueueService;

    public WorkerLeaseSweeper(JobQueueService jobQueueService) {
        this.jobQueueService = jobQueueService;
    }

    @Scheduled(fixedDelayString = "${app.worker.sweep-interval-ms:5000}", initialDelay = 1000)
    public void sweep() {
        jobQueueService.sweepOnce();
    }
}
