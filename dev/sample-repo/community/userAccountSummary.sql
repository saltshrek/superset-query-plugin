-- Users by kind of account: people, bots and deleted accounts.
SELECT
  COUNT(*) AS accounts,
  SUM(CASE WHEN is_bot = 1 THEN 1 ELSE 0 END) AS bots,
  SUM(CASE WHEN deleted = 1 THEN 1 ELSE 0 END) AS deleted,
  SUM(CASE WHEN is_admin = 1 THEN 1 ELSE 0 END) AS admins,
  SUM(CASE WHEN COALESCE(is_bot, 0) = 0 AND COALESCE(deleted, 0) = 0 THEN 1 ELSE 0 END) AS active_people
FROM users;
