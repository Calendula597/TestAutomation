-- T05 functional test cases: one row per case, ordered structured steps,
-- optimistic locking on the case row for concurrent-save detection.

CREATE TABLE test_case (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project (id),
  requirement_id BIGINT REFERENCES requirement (id),
  title VARCHAR(200) NOT NULL,
  priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX test_case_project_idx ON test_case (project_id);

CREATE TABLE test_case_step (
  id BIGSERIAL PRIMARY KEY,
  case_id BIGINT NOT NULL REFERENCES test_case (id) ON DELETE CASCADE,
  step_order INT NOT NULL,
  action TEXT NOT NULL,
  test_data TEXT,
  expected TEXT NOT NULL,
  UNIQUE (case_id, step_order)
);

CREATE INDEX test_case_step_case_idx ON test_case_step (case_id);
