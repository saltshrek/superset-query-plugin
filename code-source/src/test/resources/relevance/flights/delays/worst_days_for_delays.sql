-- The days with the highest average arrival delay, and how many flights
-- were cancelled on them.
SELECT
  date(ds) AS day,
  COUNT(*) AS flights,
  SUM(CANCELLED) AS cancelled,
  ROUND(AVG(ARRIVAL_DELAY), 1) AS avg_arrival_delay
FROM flights
GROUP BY 1
ORDER BY avg_arrival_delay DESC
LIMIT 10;
