-- Shared UI text, independent of the container filesystem. No business figures are stored here.
CREATE TABLE ui_copy_override (
    copy_key varchar(200) PRIMARY KEY,
    copy_value text NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_by bigint NOT NULL REFERENCES app_user(id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (length(copy_value) BETWEEN 1 AND 10000)
);
