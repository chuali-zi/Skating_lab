-- Fresh Java schema. Do not run the obsolete db/schema/00*.sql alongside it.
-- vector extension is provisioned by the database administrator, not the API role.
CREATE TABLE sc_actor (
 id uuid PRIMARY KEY, display_name text NOT NULL
);
CREATE TABLE sc_athlete (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES sc_actor(id),
 memory_revision bigint NOT NULL DEFAULT 0, privacy_epoch bigint NOT NULL DEFAULT 0,
 knowledge_revision bigint NOT NULL DEFAULT 0
);
INSERT INTO sc_actor VALUES ('00000000-0000-0000-0000-000000000001','Local development athlete');
INSERT INTO sc_athlete(id,actor_id) VALUES
 ('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-000000000001');

CREATE TABLE conversation_session (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL REFERENCES sc_athlete(id),
 actor_id uuid NOT NULL REFERENCES sc_actor(id), revision bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,athlete_id)
);
CREATE TABLE conversation_message (
 id uuid PRIMARY KEY, session_id uuid NOT NULL, athlete_id uuid NOT NULL,
 actor_id uuid NOT NULL REFERENCES sc_actor(id), request_id text NOT NULL,
 content text NOT NULL, ai_excluded boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(session_id,athlete_id) REFERENCES conversation_session(id,athlete_id),
 UNIQUE(athlete_id,request_id)
);
CREATE TABLE saved_plan (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL REFERENCES sc_athlete(id),
 title text NOT NULL, kind text NOT NULL, revision bigint NOT NULL DEFAULT 1,
 body jsonb NOT NULL, context_invalidated boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE recommendation_run (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL REFERENCES sc_athlete(id), session_id uuid,
 status text NOT NULL, request_id text NOT NULL, request jsonb NOT NULL,
 result jsonb, error_code text, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(athlete_id,request_id),
 FOREIGN KEY(session_id,athlete_id) REFERENCES conversation_session(id,athlete_id)
);

CREATE TABLE memory_source (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL REFERENCES sc_athlete(id),
 actor_id uuid NOT NULL REFERENCES sc_actor(id), content text,
 scope_id text, status text NOT NULL DEFAULT 'pending', revision bigint NOT NULL DEFAULT 1,
 captured_memory_revision bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(id,athlete_id)
);
CREATE TABLE memory_claim (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL REFERENCES sc_athlete(id),
 source_id uuid NOT NULL, request_id text,
 kind text NOT NULL, subject_key text, statement text NOT NULL,
 scope_type text NOT NULL CHECK(scope_type IN ('global','session','competition')),
 scope_id text, evidence_kind text NOT NULL, status text NOT NULL DEFAULT 'active',
 revision bigint NOT NULL DEFAULT 1, observed_at timestamptz NOT NULL DEFAULT now(),
 valid_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(source_id,athlete_id) REFERENCES memory_source(id,athlete_id),
 CHECK(scope_type = 'global' OR scope_id IS NOT NULL),
 UNIQUE(athlete_id,request_id)
);
CREATE INDEX memory_current ON memory_claim(athlete_id,status,scope_type,scope_id);
CREATE TABLE memory_claim_history (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL REFERENCES sc_athlete(id),
 claim_id uuid NOT NULL REFERENCES memory_claim(id) ON DELETE CASCADE,
 revision bigint NOT NULL, body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_source (
 id uuid PRIMARY KEY, athlete_id uuid REFERENCES sc_athlete(id), actor_id uuid REFERENCES sc_actor(id),
 family_id uuid NOT NULL, revision bigint NOT NULL DEFAULT 1,
 title text NOT NULL, source_kind text NOT NULL, source_url text, competition_key text,
 status text NOT NULL, request_id text, raw_text text, storage_key text,
 media_type text, content_hash text, error_code text,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(family_id,revision), UNIQUE(athlete_id,request_id)
);
CREATE INDEX knowledge_applicability ON knowledge_source(athlete_id,competition_key,status);
CREATE TABLE knowledge_block (
 id uuid PRIMARY KEY, source_id uuid NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
 ordinal integer NOT NULL, text text NOT NULL, locator text NOT NULL,
 parent_ordinal integer, search_text text NOT NULL,
 search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple',search_text)) STORED,
 embedding vector, embedding_model text, embedding_dimensions integer,
 UNIQUE(source_id,ordinal)
);
CREATE INDEX knowledge_lexical ON knowledge_block USING gin(search_vector);
-- Dispatcher contains scheduling metadata only. Content access is always tenant-scoped.
CREATE TABLE knowledge_job (
 id uuid PRIMARY KEY, source_id uuid NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
 athlete_id uuid NOT NULL REFERENCES sc_athlete(id), actor_id uuid NOT NULL REFERENCES sc_actor(id),
 status text NOT NULL DEFAULT 'pending', attempts integer NOT NULL DEFAULT 0,
 lease_until timestamptz, error_code text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(source_id)
);
CREATE INDEX knowledge_jobs_pending ON knowledge_job(status,lease_until);

-- Public catalog contains original editorial metadata/links, not copied manuals.
CREATE TABLE knowledge_catalog (
 id text PRIMARY KEY, title text NOT NULL, publisher text NOT NULL, url text NOT NULL,
 category text NOT NULL, season text, access_note text NOT NULL,
 summary text NOT NULL, verified_on date NOT NULL, ready_for_scoring boolean NOT NULL DEFAULT false
);

ALTER TABLE sc_athlete ENABLE ROW LEVEL SECURITY;
ALTER TABLE sc_athlete FORCE ROW LEVEL SECURITY;
CREATE POLICY athlete_scope ON sc_athlete USING (
 id::text = current_setting('app.athlete_id',true)
 AND actor_id::text = current_setting('app.actor_id',true)
);

DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['conversation_session','conversation_message','saved_plan',
   'recommendation_run','memory_source','memory_claim','memory_claim_history'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_scope ON %I USING (athlete_id::text = current_setting(''app.athlete_id'',true)) WITH CHECK (athlete_id::text = current_setting(''app.athlete_id'',true))',t);
 END LOOP;
END $$;

ALTER TABLE knowledge_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_source FORCE ROW LEVEL SECURITY;
CREATE POLICY source_read ON knowledge_source FOR SELECT USING (
 athlete_id IS NULL OR athlete_id::text = current_setting('app.athlete_id',true)
);
CREATE POLICY source_write ON knowledge_source FOR ALL USING (
 athlete_id::text = current_setting('app.athlete_id',true)
) WITH CHECK (athlete_id::text = current_setting('app.athlete_id',true));
ALTER TABLE knowledge_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_block FORCE ROW LEVEL SECURITY;
CREATE POLICY block_read ON knowledge_block FOR SELECT USING (
 EXISTS(SELECT 1 FROM knowledge_source s WHERE s.id = source_id)
);
CREATE POLICY block_write ON knowledge_block FOR ALL USING (
 EXISTS(SELECT 1 FROM knowledge_source s WHERE s.id = source_id
  AND s.athlete_id::text = current_setting('app.athlete_id',true))
) WITH CHECK (
 EXISTS(SELECT 1 FROM knowledge_source s WHERE s.id = source_id
  AND s.athlete_id::text = current_setting('app.athlete_id',true))
);
