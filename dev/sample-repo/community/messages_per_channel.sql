-- Messages per Slack channel, with when each was last active.
SELECT
  name AS channel,
  COUNT(*) AS messages,
  MAX(ts) AS last_message
FROM messages_channels
GROUP BY name
ORDER BY messages DESC;
