CREATE TABLE report_import (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  language VARCHAR(40) NOT NULL,
  original_filename VARCHAR(120) NOT NULL,
  storage_key TEXT NOT NULL,
  created_by BIGINT NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_report_import_project ON report_import(project_id, id);
