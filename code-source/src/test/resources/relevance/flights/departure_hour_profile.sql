-- Flights and average delay by scheduled departure hour. Delays build up
-- through the day.
SELECT
  SCHEDULED_DEPARTURE / 100 AS hour,
  COUNT(*) AS flights,
  ROUND(AVG(DEPARTURE_DELAY), 1) AS avg_departure_delay
FROM flights
WHERE CANCELLED = 0
GROUP BY 1
ORDER BY 1;
