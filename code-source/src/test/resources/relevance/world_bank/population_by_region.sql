-- Total population per region in the latest year with data.

WITH latest AS (
  SELECT MAX(year) AS year
  FROM wb_health_population
  WHERE SP_POP_TOTL IS NOT NULL
)
SELECT
  region,
  COUNT(*) AS countries,
  CAST(SUM(SP_POP_TOTL) AS INTEGER) AS population
FROM wb_health_population
JOIN latest USING (year)
GROUP BY region
ORDER BY population DESC;
