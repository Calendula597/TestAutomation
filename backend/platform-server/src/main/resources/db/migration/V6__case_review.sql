-- T06 review & publish: immutable published snapshots of test cases.
-- Rows here are append-only; updates and deletes are never issued.

CREATE TABLE test_case_version (
  id BIGSERIAL PRIMARY KEY,
  case_id BIGINT NOT NULL REFERENCES test_case (id),
  version INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  priority VARCHAR(16) NOT NULL,
  requirement_id BIGINT,
  status VARCHAR(16) NOT NULL,
  steps_json TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  published_by BIGINT NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  publish_basis VARCHAR(500),
  origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (case_id, version)
);

CREATE INDEX test_case_version_case_idx ON test_case_version (case_id);
