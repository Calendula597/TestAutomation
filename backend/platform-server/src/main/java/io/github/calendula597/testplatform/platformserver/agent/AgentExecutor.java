package io.github.calendula597.testplatform.platformserver.agent;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Dispatches agent analysis off the request thread. The workflow itself is
 * transactional and re-reads its state, so refresh/restart/cancel are safe.
 */
@Component
public class AgentExecutor {

    private final AgentOrchestrationService orchestration;

    public AgentExecutor(AgentOrchestrationService orchestration) {
        this.orchestration = orchestration;
    }

    @Async
    public void submit(Long sessionId) {
        orchestration.runAnalysisAsync(sessionId);
    }
}
