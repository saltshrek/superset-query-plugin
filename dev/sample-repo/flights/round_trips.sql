-- Routes flown in both directions, with the flights each way. A self-join
-- matches each route with its return.

WITH routes AS (
  SELECT ORIGIN_AIRPORT AS origin, DESTINATION_AIRPORT AS destination, COUNT(*) AS flights
  FROM flights
  GROUP BY 1, 2
)
SELECT
  outbound.origin,
  outbound.destination,
  outbound.flights AS outbound_flights,
  inbound.flights AS return_flights
FROM routes AS outbound
JOIN routes AS inbound
  ON inbound.origin = outbound.destination
  AND inbound.destination = outbound.origin
WHERE outbound.origin < outbound.destination
ORDER BY outbound.flights + inbound.flights DESC;
