-- The busiest routes, counting each direction separately.
SELECT
  ORIGIN_AIRPORT || ' → ' || DESTINATION_AIRPORT AS route,
  CITY || ' → ' || CITY_DEST AS cities,
  COUNT(*) AS flights,
  MAX(DISTANCE) AS distance_miles
FROM flights
GROUP BY ORIGIN_AIRPORT, DESTINATION_AIRPORT, CITY, CITY_DEST
ORDER BY flights DESC
LIMIT 20;
