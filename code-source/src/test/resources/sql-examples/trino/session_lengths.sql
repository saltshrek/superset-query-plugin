-- Session lengths per user, splitting a user's events into sessions
-- wherever 30 minutes pass without one.

WITH ordered AS (
  SELECT
    user_id,
    event_time,
    CASE
      WHEN date_diff('minute', lag(event_time) OVER w, event_time) > 30 THEN 1
      ELSE 0
    END AS new_session
  FROM iceberg.analytics.events
  WINDOW w AS (PARTITION BY user_id ORDER BY event_time)
),
-- Numbers each user's sessions by counting the session starts so far.
numbered AS (
  SELECT *, sum(new_session) OVER (PARTITION BY user_id ORDER BY event_time) AS session
  FROM ordered
)
SELECT
  user_id,
  session,
  min(event_time) AS started,
  date_diff('second', min(event_time), max(event_time)) AS seconds
FROM numbered
GROUP BY user_id, session
