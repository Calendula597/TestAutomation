package io.github.calendula597.testplatform.platformserver.worker;

import java.time.Instant;

/**
 * Worker-side run control supplied to executors. The worker loop derives
 * the deadline from the job timeout and polls cancellation (via heartbeat
 * responses) between steps, so long browser sessions cannot outrun the
 * platform's timeout and cancel semantics.
 */
public interface ExecutionControl {

    /** Absolute instant after which the run must abort as TIMED_OUT. */
    Instant deadline();

    /** True when the control plane has asked this attempt to cancel. */
    boolean cancelled();

    static ExecutionControl none() {
        return new ExecutionControl() {
            @Override
            public Instant deadline() {
                return null;
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }
}
