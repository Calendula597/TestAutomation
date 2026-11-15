CREATE TABLE evidence_policy (
  project_id BIGINT PRIMARY KEY REFERENCES project(id),
  retention_days INT NOT NULL,
  updated_by BIGINT NOT NULL REFERENCES app_user(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE evidence_pruned (
  id BIGSERIAL PRIMARY KEY,
  storage_key TEXT NOT NULL UNIQUE,
  project_id BIGINT REFERENCES project(id),
  pruned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_pruned_project ON evidence_pruned(project_id, pruned_at);
