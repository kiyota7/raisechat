-- リアクションの並び順(押された順)を持つ列(SQLite版のV2と同じ目的)。既存の行にも、順に番号が付く
ALTER TABLE reactions ADD COLUMN id BIGINT GENERATED ALWAYS AS IDENTITY;
