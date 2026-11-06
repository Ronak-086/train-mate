-- Schema from the project spec, section 9, unchanged.
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- for gen_random_uuid()

-- USERS
CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid  TEXT UNIQUE NOT NULL,
  display_name  TEXT NOT NULL,
  email         TEXT,
  trust_score   INT NOT NULL DEFAULT 0,
  is_blocked    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- STATIC RAIL DATA
CREATE TABLE stations (
  code TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE trains (
  train_no  TEXT PRIMARY KEY,
  name      TEXT NOT NULL,
  from_stn  TEXT REFERENCES stations(code),
  to_stn    TEXT REFERENCES stations(code),
  run_days  TEXT                      -- e.g. 'MTWTFSS'
);

CREATE TABLE train_schedule (
  train_no     TEXT REFERENCES trains(train_no),
  seq          INT  NOT NULL,
  station_code TEXT REFERENCES stations(code),
  sched_arr    TIME,
  sched_dep    TIME,
  day_offset   INT NOT NULL DEFAULT 0,
  PRIMARY KEY (train_no, seq)
);

-- USER TRIPS
CREATE TABLE trips (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  train_no      TEXT NOT NULL REFERENCES trains(train_no),
  journey_date  DATE NOT NULL,
  boarding_stn  TEXT NOT NULL REFERENCES stations(code),
  dest_stn      TEXT NOT NULL REFERENCES stations(code),
  travel_class  TEXT NOT NULL,        -- SL, 3A, 2A, 1A, CC, 2S ...
  coach         TEXT NOT NULL,
  seat_no       INT,
  berth_type    TEXT,                 -- LOWER, MIDDLE, UPPER, SIDE_LOWER, SIDE_UPPER, WINDOW ...
  pnr_hash      TEXT,                 -- salted SHA-256; never store the raw PNR
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, train_no, journey_date)
);
CREATE INDEX idx_trips_train_date ON trips (train_no, journey_date);

-- POSTS (SWAP, HELP, ASK, SHARE, ALERT)
CREATE TABLE requests (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  trip_id        UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  train_no       TEXT NOT NULL,       -- denormalised for fast board queries
  journey_date   DATE NOT NULL,
  coach          TEXT NOT NULL,
  kind           TEXT NOT NULL CHECK (kind IN ('SWAP','HELP','ASK','SHARE','ALERT')),
  scope          TEXT NOT NULL DEFAULT 'COACH' CHECK (scope IN ('COACH','NEARBY','TRAIN')),
  category       TEXT,
  is_urgent      BOOLEAN NOT NULL DEFAULT FALSE,
  is_hidden      BOOLEAN NOT NULL DEFAULT FALSE,
  offering_berth TEXT,
  wants_berth    TEXT,
  group_size     INT DEFAULT 1,
  note           TEXT CHECK (length(note) <= 500),
  status         TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','MATCHED','CLOSED','EXPIRED')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_requests_board ON requests (train_no, journey_date, coach, status);
CREATE INDEX idx_requests_scope ON requests (train_no, journey_date, scope, status);

-- PUBLIC THREADS
CREATE TABLE request_comments (
  id          BIGSERIAL PRIMARY KEY,
  request_id  UUID NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES users(id),
  body        TEXT NOT NULL CHECK (length(body) <= 500),
  is_hidden   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_comments_request ON request_comments (request_id, id);

CREATE TABLE reactions (
  request_id  UUID NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES users(id),
  type        TEXT NOT NULL CHECK (type IN ('CONFIRMED','HELPFUL','OUTDATED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (request_id, user_id, type)
);

-- PRIVATE RESPONSES + CHAT
CREATE TABLE conversations (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id    UUID NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
  requester_id  UUID NOT NULL REFERENCES users(id),
  helper_id     UUID NOT NULL REFERENCES users(id),
  status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DECLINED','DONE')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (request_id, helper_id)
);

CREATE TABLE messages (
  id               BIGSERIAL PRIMARY KEY,
  conversation_id  UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id        UUID NOT NULL REFERENCES users(id),
  body             TEXT NOT NULL CHECK (length(body) <= 1000),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_conv ON messages (conversation_id, id);

-- TRUST AND SAFETY
CREATE TABLE reviews (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id  UUID NOT NULL REFERENCES conversations(id),
  reviewer_id      UUID NOT NULL REFERENCES users(id),
  reviewee_id      UUID NOT NULL REFERENCES users(id),
  rating           INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (conversation_id, reviewer_id)
);

CREATE TABLE reports (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id      UUID NOT NULL REFERENCES users(id),
  target_user_id   UUID NOT NULL REFERENCES users(id),
  request_id       UUID REFERENCES requests(id),
  comment_id       BIGINT REFERENCES request_comments(id),
  reason           TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (reporter_id, request_id, comment_id)
);

CREATE TABLE blocks (
  blocker_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id)
);

-- TRAIN STATUS
CREATE TABLE train_status_snapshot (
  id              BIGSERIAL PRIMARY KEY,
  train_no        TEXT NOT NULL,
  journey_date    DATE NOT NULL,
  current_station TEXT,
  delay_min       INT,
  observed_at     TIMESTAMPTZ,
  fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  source          TEXT NOT NULL,        -- provider name | CROWD
  raw_json        JSONB                 -- optional, keep small or null
);
CREATE INDEX idx_snapshot_latest ON train_status_snapshot (train_no, journey_date, fetched_at DESC);

CREATE TABLE api_usage (
  provider  TEXT NOT NULL,
  day       DATE NOT NULL,
  calls     INT  NOT NULL DEFAULT 0,
  PRIMARY KEY (provider, day)
);

CREATE TABLE crowd_reports (            -- Phase 2
  id            BIGSERIAL PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id),
  train_no      TEXT NOT NULL,
  journey_date  DATE NOT NULL,
  station_code  TEXT NOT NULL,
  reported_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_crowd_recent ON crowd_reports (train_no, journey_date, reported_at DESC);
