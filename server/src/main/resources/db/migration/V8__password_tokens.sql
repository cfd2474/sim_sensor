-- Chunk 4.1: one-time links to reset a password ("Forgot password", admin "email reset link")
-- or to set it for a new account (invite). Only the SHA-256 of the token is stored.
CREATE TABLE password_tokens (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    instructor_id uuid        NOT NULL REFERENCES instructors (id) ON DELETE CASCADE,
    token_hash    text        NOT NULL UNIQUE,
    purpose       text        NOT NULL CHECK (purpose IN ('reset', 'invite')),
    expires_at    timestamptz NOT NULL,
    used_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX password_tokens_instructor_idx ON password_tokens (instructor_id);
