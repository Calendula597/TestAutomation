-- T04 requirements: versioned requirement assets with acceptance criteria.
-- Versions are append-only; historical rows are never updated or deleted.

CREATE TABLE requirement (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project (id),
  title VARCHAR(200) NOT NULL,
  current_version INT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX requirement_project_idx ON requirement (project_id);

CREATE TABLE requirement_version (
  id BIGSERIAL PRIMARY KEY,
  requirement_id BIGINT NOT NULL REFERENCES requirement (id),
  version INT NOT NULL,
  body TEXT NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  acceptance_criteria TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (requirement_id, version)
);

CREATE INDEX requirement_version_req_idx ON requirement_version (requirement_id);
