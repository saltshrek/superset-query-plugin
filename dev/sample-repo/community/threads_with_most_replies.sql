-- The threads that got the most replies, and who started them. LEFT JOIN
-- keeps threads whose author isn't in the users table.
SELECT
  threads.ts AS started,
  COALESCE(users.real_name, users.name, threads.user) AS started_by,
  CAST(threads.reply_count AS INTEGER) AS replies,
  CAST(threads.reply_users_count AS INTEGER) AS people_replying,
  substr(threads.text, 1, 80) AS opening_text
FROM threads
LEFT JOIN users ON users.id = threads.user
ORDER BY threads.reply_count DESC
LIMIT 20;
