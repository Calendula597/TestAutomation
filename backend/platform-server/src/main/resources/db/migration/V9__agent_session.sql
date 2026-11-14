-- T10 agent clarification sessions and async generation tasks.
-- Sessions persist across refresh and service restart.

CREATE TABLE agent_session (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project (id),
  requirement_id BIGINT NOT NULL REFERENCES requirement (id),
  status VARCHAR(24) NOT NULL DEFAULT 'ANALYZING',
  error_message TEXT,
  model VARCHAR(100),
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX agent_session_project_idx ON agent_session (project_id);

CREATE TABLE clarification_question (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL REFERENCES agent_session (id),
  order_index INT NOT NULL,
  question TEXT NOT NULL,
  blocking BOOLEAN NOT NULL DEFAULT true,
  answer TEXT,
  answered BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE coverage_point (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL REFERENCES agent_session (id),
  description TEXT NOT NULL,
  technique VARCHAR(32),
  is_assumption BOOLEAN NOT NULL DEFAULT false,
  confirmed BOOLEAN NOT NULL DEFAULT false,
  user_note TEXT
);

CREATE TABLE generation_task (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL REFERENCES agent_session (id),
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  phase VARCHAR(32),
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
