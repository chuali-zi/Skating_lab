ALTER TABLE memory_claim ADD CONSTRAINT memory_claim_tenant_identity UNIQUE(id,athlete_id);
ALTER TABLE memory_claim ADD COLUMN supersedes_id uuid;
ALTER TABLE memory_claim ADD CONSTRAINT memory_predecessor_same_athlete
 FOREIGN KEY(supersedes_id,athlete_id) REFERENCES memory_claim(id,athlete_id)
 ON DELETE SET NULL (supersedes_id);
