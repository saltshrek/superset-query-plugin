-- Daily active users over the dashboard's time range, with the change
-- from the day before.

-- One row per day.
WITH daily AS (
  SELECT
    date_trunc('day', event_time) AS day,
    approx_distinct(user_id) AS users
  FROM iceberg.analytics.events
  WHERE event_time >= from_iso8601_timestamp('{{ from_dttm }}')
    AND event_time < from_iso8601_timestamp('{{ to_dttm }}')
    {% if filter_values('platform') %}
    AND platform IN {{ filter_values('platform') | where_in }}
    {% endif %}
  GROUP BY 1
)
SELECT
  day,
  users,
  users - lag(users) OVER (ORDER BY day) AS change
FROM daily
ORDER BY day
