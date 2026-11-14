-- T13 environments: plain variables, encrypted secret references and
-- target allowlists kept in versioned snapshots. Secret values are stored
-- as application-side AES-GCM ciphertext, never as plaintext.

CREATE TABLE environment (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project (id),
  name VARCHAR(100) NOT NULL,
  current_version INT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, name)
);

CREATE INDEX environment_project_idx ON environment (project_id);

CREATE TABLE environment_version (
  id BIGSERIAL PRIMARY KEY,
  environment_id BIGINT NOT NULL REFERENCES environment (id),
  version INT NOT NULL,
  -- JSON object key -> plain value; readable configuration, not secret.
  plain_variables TEXT NOT NULL,
  -- JSON object key -> AES-GCM ciphertext ("v1:<iv>:<ciphertext>").
  secrets TEXT NOT NULL,
  -- JSON array of allowed target origins ("scheme://host[:port]").
  targets TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (environment_id, version)
);
