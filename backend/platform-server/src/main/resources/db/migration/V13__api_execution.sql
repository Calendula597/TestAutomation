-- T15 API execution: type-specific execution schema carried by test cases
-- and snapshotted immutably into published versions. case_type selects the
-- executor; execution_json holds the machine-readable request/assertions.

ALTER TABLE test_case ADD COLUMN case_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE test_case ADD COLUMN execution_json TEXT;

ALTER TABLE test_case_version ADD COLUMN case_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE test_case_version ADD COLUMN execution_json TEXT;
