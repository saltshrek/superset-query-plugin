-- Messages by day of week and hour (UTC), for a heatmap.
SELECT
  CASE strftime('%w', ts)
    WHEN '0' THEN 'Sun' WHEN '1' THEN 'Mon' WHEN '2' THEN 'Tue'
    WHEN '3' THEN 'Wed' WHEN '4' THEN 'Thu' WHEN '5' THEN 'Fri'
    ELSE 'Sat'
  END AS weekday,
  CAST(strftime('%H', ts) AS INTEGER) AS hour,
  COUNT(*) AS messages
FROM messages_channels
GROUP BY strftime('%w', ts), 2
ORDER BY strftime('%w', ts), hour;
