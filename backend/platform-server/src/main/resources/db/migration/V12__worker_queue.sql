-- T14 workers and the persistent queue: registered workers with scoped
-- credentials and declared capabilities, jobs with attempts, leases and
-- heartbeats in PostgreSQL so queued work survives restarts.

CREATE TABLE worker (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  -- JSON array of executor types this worker can run (DIAGNOSTIC, API, WEB...).
  capabilities TEXT NOT NULL,
  version VARCHAR(100),
  max_concurrency INT NOT NULL DEFAULT 1,
  -- SHA-256 hex of the bearer token; plaintext is shown once at registration.
  token_hash CHAR(64) NOT NULL UNIQUE,
  last_heartbeat_at TIMESTAMPTZ,
  registered_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE execution_job (
  id BIGSERIAL PRIMARY KEY,
  -- DIAGNOSTIC first; later executor types (API, WEB, ...).
  job_type VARCHAR(32) NOT NULL,
  -- Pinned worker when set; NULL means any capable worker.
  worker_id BIGINT REFERENCES worker (id),
  project_id BIGINT REFERENCES project (id),
  payload TEXT NOT NULL,
  -- QUEUED | RUNNING | PASSED | ASSERTION_FAILED | ERROR | CANCELLED | TIMED_OUT
  status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  -- Lost-lease recovery: side-effect-free jobs may be requeued, anything
  -- with uncertain external effects must wait for a user retry.
  requeue_on_lost BOOLEAN NOT NULL DEFAULT false,
  lease_seconds INT NOT NULL DEFAULT 30,
  timeout_seconds INT,
  created_by BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX execution_job_status_idx ON execution_job (status);

CREATE TABLE execution_attempt (
  id BIGSERIAL PRIMARY KEY,
  job_id BIGINT NOT NULL REFERENCES execution_job (id),
  attempt_number INT NOT NULL,
  worker_id BIGINT NOT NULL REFERENCES worker (id),
  lease_token VARCHAR(72) NOT NULL,
  lease_expires_at TIMESTAMPTZ NOT NULL,
  last_heartbeat_at TIMESTAMPTZ,
  status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  cancel_requested BOOLEAN NOT NULL DEFAULT false,
  result TEXT,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  UNIQUE (job_id, attempt_number)
);

CREATE INDEX execution_attempt_lease_idx ON execution_attempt (lease_expires_at)
  WHERE status = 'RUNNING';

CREATE TABLE worker_diagnostic (
  id BIGSERIAL PRIMARY KEY,
  worker_id BIGINT NOT NULL REFERENCES worker (id),
  job_id BIGINT REFERENCES execution_job (id),
  -- PASSED when the probes ran; the per-check ok flags carry the truth.
  status VARCHAR(16) NOT NULL,
  checks TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
