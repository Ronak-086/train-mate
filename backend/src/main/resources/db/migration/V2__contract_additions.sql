-- Additions the API contract needs beyond spec section 9 (each is listed in the README).

-- Posts have a headline in the designs ("Upper berth for lower berth"). The spec only has `note`.
ALTER TABLE requests ADD COLUMN title TEXT NOT NULL DEFAULT '' CHECK (length(title) <= 80);

-- "Mark done" needs both people to agree before a conversation becomes DONE.
ALTER TABLE conversations
  ADD COLUMN requester_done BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN helper_done    BOOLEAN NOT NULL DEFAULT FALSE;

-- Cheap lookups used by the API.
CREATE INDEX idx_conversations_requester ON conversations (requester_id);
CREATE INDEX idx_conversations_helper    ON conversations (helper_id);
CREATE INDEX idx_reactions_request       ON reactions (request_id);
