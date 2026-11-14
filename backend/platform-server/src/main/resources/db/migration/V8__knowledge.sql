-- T09 knowledge base: versioned documents (global / project scope) with
-- chunking and an optional pgvector embedding for hybrid retrieval.

CREATE TABLE knowledge_document (
  id BIGSERIAL PRIMARY KEY,
  scope VARCHAR(16) NOT NULL,
  project_id BIGINT REFERENCES project (id),
  title VARCHAR(200) NOT NULL,
  current_version INT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX knowledge_document_project_idx ON knowledge_document (project_id);

CREATE TABLE knowledge_version (
  id BIGSERIAL PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES knowledge_document (id),
  version INT NOT NULL,
  body TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_id, version)
);

CREATE TABLE knowledge_chunk (
  id BIGSERIAL PRIMARY KEY,
  version_id BIGINT NOT NULL REFERENCES knowledge_version (id),
  chunk_index INT NOT NULL,
  heading VARCHAR(500),
  content TEXT NOT NULL,
  embedding vector(1536),
  embedding_model VARCHAR(100),
  indexed BOOLEAN NOT NULL DEFAULT false,
  index_error TEXT,
  UNIQUE (version_id, chunk_index)
);
