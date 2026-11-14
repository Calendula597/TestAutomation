-- T08 manual testing: plans over immutable READY case versions, resumable
-- runs with step-level results, and aggregated reports.

CREATE TABLE test_plan (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project (id),
  name VARCHAR(100) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE test_plan_item (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL REFERENCES test_plan (id),
  order_index INT NOT NULL,
  case_version_id BIGINT NOT NULL REFERENCES test_case_version (id)
);

CREATE INDEX test_plan_item_plan_idx ON test_plan_item (plan_id);

CREATE TABLE manual_run (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL REFERENCES test_plan (id),
  status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
  executed_by BIGINT NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);

CREATE TABLE manual_run_item (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES manual_run (id) ON DELETE CASCADE,
  plan_item_id BIGINT NOT NULL REFERENCES test_plan_item (id),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  actual_result TEXT,
  note TEXT,
  evidence_url TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX manual_run_item_run_idx ON manual_run_item (run_id);
