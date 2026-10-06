-- SQLite版(db/migration/sqlite/V1__init.sql)と同じ構造。時刻は、SQLite版と同じ形式の文字列で持つ(例: 2026-10-05T10:30:00.123Z)
CREATE TABLE users (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT '',
  avatar_url TEXT
);

CREATE TABLE workspaces (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name TEXT NOT NULL,
  owner_id BIGINT NOT NULL REFERENCES users(id)
);

CREATE TABLE workspace_members (
  workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  PRIMARY KEY (workspace_id, user_id)
);

-- is_dm=1 はDM(メンバー2名)。DMもチャンネルとして扱う
CREATE TABLE channels (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workspace_id BIGINT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  is_private INTEGER NOT NULL DEFAULT 0,
  is_dm INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE channel_members (
  channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  last_read_message_id BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (channel_id, user_id)
);

CREATE TABLE messages (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  parent_id BIGINT REFERENCES messages(id),
  content TEXT NOT NULL DEFAULT '',
  attachment_url TEXT,
  attachment_type TEXT,
  created_at TEXT NOT NULL DEFAULT to_char((now() AT TIME ZONE 'UTC'), 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
  edited_at TEXT,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_messages_channel ON messages(channel_id, id);
CREATE INDEX idx_messages_parent ON messages(parent_id);

CREATE TABLE reactions (
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  emoji TEXT NOT NULL,
  PRIMARY KEY (message_id, user_id, emoji)
);

CREATE TABLE mentions (
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  PRIMARY KEY (message_id, user_id)
);
