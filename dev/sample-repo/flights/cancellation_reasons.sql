-- Cancelled flights by reason, with the codes spelled out.
SELECT
  CASE CANCELLATION_REASON
    WHEN 'A' THEN 'Airline'
    WHEN 'B' THEN 'Weather'
    WHEN 'C' THEN 'National Air System'
    WHEN 'D' THEN 'Security'
    ELSE 'Unknown'
  END AS reason,
  COUNT(*) AS cancelled_flights
FROM flights
WHERE CANCELLED = 1
GROUP BY 1
ORDER BY cancelled_flights DESC;
