-- リアクションの並び順(押された順)を、SQLite固有の rowid ではなく、通常の列で持つ。
-- 既存の並びは、rowid の順のまま引き継ぐ。
CREATE TABLE reactions_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id),
  emoji TEXT NOT NULL,
  UNIQUE (message_id, user_id, emoji)
);
INSERT INTO reactions_new (message_id, user_id, emoji) SELECT message_id, user_id, emoji FROM reactions ORDER BY rowid;
DROP TABLE reactions;
ALTER TABLE reactions_new RENAME TO reactions;
