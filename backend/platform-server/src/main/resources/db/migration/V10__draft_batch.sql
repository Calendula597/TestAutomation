-- T11 draft batches produced by the agent: full provenance (model, prompt
-- version, tool calls, citations) and human review state.

CREATE TABLE draft_batch (
  id BIGSERIAL PRIMARY KEY,
  session_id BIGINT NOT NULL REFERENCES agent_session (id),
  project_id BIGINT NOT NULL,
  requirement_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW',
  model VARCHAR(100),
  prompt_version VARCHAR(32) NOT NULL,
  tool_summary TEXT,
  prompt_tokens INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE draft_batch_case (
  id BIGSERIAL PRIMARY KEY,
  batch_id BIGINT NOT NULL REFERENCES draft_batch (id),
  title VARCHAR(200) NOT NULL,
  priority VARCHAR(16),
  steps_json TEXT NOT NULL,
  technique VARCHAR(32),
  suggestion VARCHAR(16) NOT NULL DEFAULT 'NEW',
  duplicate_of_case_id BIGINT,
  knowledge_insufficient BOOLEAN NOT NULL DEFAULT false,
  citations_json TEXT NOT NULL
);

CREATE TABLE agent_tool_call (
  id BIGSERIAL PRIMARY KEY,
  batch_id BIGINT NOT NULL REFERENCES draft_batch (id),
  tool_name VARCHAR(64) NOT NULL,
  input_summary VARCHAR(500),
  output_summary VARCHAR(500),
  called_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
