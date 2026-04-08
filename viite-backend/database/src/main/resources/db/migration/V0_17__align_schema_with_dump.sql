-- Add columns required by restore dumps (idempotent safety migration)
ALTER TABLE complementary_link_table
    ADD COLUMN IF NOT EXISTS vvh_id character varying(30);