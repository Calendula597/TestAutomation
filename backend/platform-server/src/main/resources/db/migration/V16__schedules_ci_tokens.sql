CREATE TABLE execution_schedule (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  case_version_id BIGINT NOT NULL REFERENCES test_case_version(id),
  environment_id BIGINT NOT NULL REFERENCES environment(id),
  name VARCHAR(120) NOT NULL,
  cron_expression VARCHAR(120) NOT NULL,
  time_zone VARCHAR(64) NOT NULL,
  allow_overlap BOOLEAN NOT NULL DEFAULT false,
  enabled BOOLEAN NOT NULL DEFAULT true,
  next_run_at TIMESTAMPTZ NOT NULL,
  last_fired_at TIMESTAMPTZ,
  created_by BIGINT NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, case_version_id, environment_id, cron_expression, time_zone)
);

CREATE INDEX execution_schedule_due_idx ON execution_schedule(next_run_at, id) WHERE enabled;

ALTER TABLE execution_job ADD COLUMN schedule_id BIGINT REFERENCES execution_schedule(id);
ALTER TABLE execution_job ADD COLUMN idempotency_key VARCHAR(128);
CREATE UNIQUE INDEX execution_job_idempotency_idx ON execution_job(project_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
CREATE INDEX execution_job_schedule_active_idx ON execution_job(schedule_id, status)
  WHERE schedule_id IS NOT NULL;

CREATE TABLE ci_token (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  name VARCHAR(120) NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  token_prefix VARCHAR(20) NOT NULL,
  scopes TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  last_used_at TIMESTAMPTZ,
  created_by BIGINT NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, name)
);
