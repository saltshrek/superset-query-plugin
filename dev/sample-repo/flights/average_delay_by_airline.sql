-- Average departure and arrival delay per airline, in minutes.
SELECT
  AIRLINE AS airline,
  COUNT(*) AS flights,
  ROUND(AVG(DEPARTURE_DELAY), 1) AS avg_departure_delay,
  ROUND(AVG(ARRIVAL_DELAY), 1) AS avg_arrival_delay
FROM flights
WHERE CANCELLED = 0
GROUP BY AIRLINE
ORDER BY avg_arrival_delay DESC;
