-- New community members per day, smoothed with a 7-day rolling average.
SELECT
  date(date) AS day,
  new_members,
  ROUND(AVG(new_members) OVER (
    ORDER BY date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
  ), 1) AS rolling_7_day_avg
FROM new_members_daily
ORDER BY day;
