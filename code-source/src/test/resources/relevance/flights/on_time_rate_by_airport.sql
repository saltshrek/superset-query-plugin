-- Share of departures from each airport that left within 15 minutes of
-- schedule. Airports with few flights are left out.
SELECT
  ORIGIN_AIRPORT AS airport,
  AIRPORT AS name,
  COUNT(*) AS departures,
  ROUND(100.0 * SUM(CASE WHEN DEPARTURE_DELAY <= 15 THEN 1 ELSE 0 END) / COUNT(*), 1) AS on_time_pct
FROM flights
WHERE CANCELLED = 0
GROUP BY ORIGIN_AIRPORT, AIRPORT
HAVING COUNT(*) >= 200
ORDER BY on_time_pct;
