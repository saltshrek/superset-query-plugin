-- How a late arrival carries over to the same aircraft's next departure.
-- LAG looks at each tail number's previous flight that day.

WITH legs AS (
  SELECT
    TAIL_NUMBER,
    ds,
    SCHEDULED_DEPARTURE,
    DEPARTURE_DELAY,
    LAG(ARRIVAL_DELAY) OVER (
      PARTITION BY TAIL_NUMBER, ds ORDER BY SCHEDULED_DEPARTURE
    ) AS previous_arrival_delay
  FROM flights
  WHERE CANCELLED = 0 AND TAIL_NUMBER IS NOT NULL
)
SELECT
  CASE
    WHEN previous_arrival_delay <= 0 THEN 'previous leg on time'
    WHEN previous_arrival_delay <= 30 THEN 'previous leg up to 30 min late'
    ELSE 'previous leg over 30 min late'
  END AS previous_leg,
  COUNT(*) AS departures,
  ROUND(AVG(DEPARTURE_DELAY), 1) AS avg_departure_delay
FROM legs
WHERE previous_arrival_delay IS NOT NULL
GROUP BY 1
ORDER BY avg_departure_delay;
