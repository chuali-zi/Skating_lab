-- The dispatcher can see scheduling metadata. Bytes, observations and reviews use the same tenant scope as plans.
ALTER TABLE knowledge_job ADD COLUMN indexed_model text;
CREATE TABLE video_dispatch (
 id uuid PRIMARY KEY, actor_id uuid NOT NULL REFERENCES sc_actor(id), athlete_id uuid NOT NULL REFERENCES sc_athlete(id),
 source_id uuid REFERENCES knowledge_source(id), status text NOT NULL CHECK(status IN ('queued','running','completed','failed')),
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, UNIQUE(id,athlete_id)
);
CREATE UNIQUE INDEX video_one_active ON video_dispatch(athlete_id) WHERE status IN ('queued','running');
CREATE INDEX video_queued ON video_dispatch(status,created_at);
CREATE TABLE video_evidence (
 id uuid PRIMARY KEY, athlete_id uuid NOT NULL, clip bytea, result jsonb, error text,
 FOREIGN KEY(id,athlete_id) REFERENCES video_dispatch(id,athlete_id) ON DELETE CASCADE
);
CREATE TABLE video_frame (
 job_id uuid NOT NULL, athlete_id uuid NOT NULL, idx integer NOT NULL CHECK(idx BETWEEN 0 AND 19), jpeg bytea NOT NULL,
 PRIMARY KEY(job_id,idx), FOREIGN KEY(job_id,athlete_id) REFERENCES video_dispatch(id,athlete_id) ON DELETE CASCADE
);
CREATE TABLE video_review (
 id uuid PRIMARY KEY, job_id uuid NOT NULL, athlete_id uuid NOT NULL, observation_index integer NOT NULL CHECK(observation_index BETWEEN 0 AND 3),
 decision text NOT NULL CHECK(decision IN ('accept','correct','ignore')), capability_note text, revision bigint NOT NULL DEFAULT 1,
 reviewed_at timestamptz NOT NULL DEFAULT now(), UNIQUE(job_id,observation_index),
 CHECK((decision='ignore' AND capability_note IS NULL) OR (decision<>'ignore' AND length(btrim(capability_note)) BETWEEN 1 AND 400)),
 FOREIGN KEY(job_id,athlete_id) REFERENCES video_dispatch(id,athlete_id) ON DELETE CASCADE
);
DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['video_evidence','video_frame','video_review'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_scope ON %I USING (athlete_id::text=current_setting(''app.athlete_id'',true)) WITH CHECK (athlete_id::text=current_setting(''app.athlete_id'',true))',t);
 END LOOP;
END $$;
