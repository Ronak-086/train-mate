-- V3: Fix foreign keys for cascading user deletion (DELETE /api/me).
-- When a user deletes their account, all personal content (comments, reactions,
-- conversations, messages, reviews, reports, crowd reports) cascades cleanly.

ALTER TABLE request_comments
  DROP CONSTRAINT IF EXISTS request_comments_user_id_fkey,
  ADD CONSTRAINT request_comments_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE reactions
  DROP CONSTRAINT IF EXISTS reactions_user_id_fkey,
  ADD CONSTRAINT reactions_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE conversations
  DROP CONSTRAINT IF EXISTS conversations_requester_id_fkey,
  ADD CONSTRAINT conversations_requester_id_fkey
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
  DROP CONSTRAINT IF EXISTS conversations_helper_id_fkey,
  ADD CONSTRAINT conversations_helper_id_fkey
    FOREIGN KEY (helper_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE messages
  DROP CONSTRAINT IF EXISTS messages_sender_id_fkey,
  ADD CONSTRAINT messages_sender_id_fkey
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE reviews
  DROP CONSTRAINT IF EXISTS reviews_reviewer_id_fkey,
  ADD CONSTRAINT reviews_reviewer_id_fkey
    FOREIGN KEY (reviewer_id) REFERENCES users(id) ON DELETE CASCADE,
  DROP CONSTRAINT IF EXISTS reviews_reviewee_id_fkey,
  ADD CONSTRAINT reviews_reviewee_id_fkey
    FOREIGN KEY (reviewee_id) REFERENCES users(id) ON DELETE CASCADE,
  DROP CONSTRAINT IF EXISTS reviews_conversation_id_fkey,
  ADD CONSTRAINT reviews_conversation_id_fkey
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE;

ALTER TABLE reports
  DROP CONSTRAINT IF EXISTS reports_reporter_id_fkey,
  ADD CONSTRAINT reports_reporter_id_fkey
    FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE,
  DROP CONSTRAINT IF EXISTS reports_target_user_id_fkey,
  ADD CONSTRAINT reports_target_user_id_fkey
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE,
  DROP CONSTRAINT IF EXISTS reports_request_id_fkey,
  ADD CONSTRAINT reports_request_id_fkey
    FOREIGN KEY (request_id) REFERENCES requests(id) ON DELETE CASCADE,
  DROP CONSTRAINT IF EXISTS reports_comment_id_fkey,
  ADD CONSTRAINT reports_comment_id_fkey
    FOREIGN KEY (comment_id) REFERENCES request_comments(id) ON DELETE CASCADE;

ALTER TABLE crowd_reports
  DROP CONSTRAINT IF EXISTS crowd_reports_user_id_fkey,
  ADD CONSTRAINT crowd_reports_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
