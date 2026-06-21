-- Restore per-user uniqueness of category names (dropped during the category refactor in V4).
-- Backstops the application-level dedup in CategoryService against races/duplicates.
ALTER TABLE categories ADD CONSTRAINT uc_categories_user_name UNIQUE (user_id, name);
