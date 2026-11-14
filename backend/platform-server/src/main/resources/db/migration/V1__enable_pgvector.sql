-- T01 baseline: enable pgvector for future knowledge embeddings (PRD #1).
-- The pinned pgvector/pgvector image ships this extension.
CREATE EXTENSION IF NOT EXISTS vector;
