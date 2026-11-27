-- Additional dispatch metadata is per organization; original request stays unchanged.
ALTER TABLE request_target ADD COLUMN dispatch_requested_at timestamptz;
ALTER TABLE request_target ADD COLUMN dispatch_due_at timestamptz;
ALTER TABLE request_target ADD COLUMN dispatch_message text;
ALTER TABLE request_target ADD COLUMN dispatch_requested_by bigint REFERENCES app_user(id);
