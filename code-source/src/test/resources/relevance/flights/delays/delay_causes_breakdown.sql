-- Total minutes of delay by cause. The causes are separate columns, so
-- they're stacked into rows with UNION ALL first.

WITH causes AS (
  SELECT 'Air system' AS cause, AIR_SYSTEM_DELAY AS minutes FROM flights
  UNION ALL SELECT 'Security', SECURITY_DELAY FROM flights
  UNION ALL SELECT 'Airline', AIRLINE_DELAY FROM flights
  UNION ALL SELECT 'Late aircraft', LATE_AIRCRAFT_DELAY FROM flights
  UNION ALL SELECT 'Weather', WEATHER_DELAY FROM flights
)
SELECT
  cause,
  SUM(minutes) AS delay_minutes,
  ROUND(100.0 * SUM(minutes) / SUM(SUM(minutes)) OVER (), 1) AS pct_of_delay
FROM causes
WHERE minutes > 0
GROUP BY cause
ORDER BY delay_minutes DESC;
