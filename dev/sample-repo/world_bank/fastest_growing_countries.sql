-- Countries whose population grew most between 1990 and 2014. Two copies
-- of the table are joined, one per year.
SELECT
  start.country_name,
  start.region,
  CAST(start.SP_POP_TOTL AS INTEGER) AS population_1990,
  CAST(finish.SP_POP_TOTL AS INTEGER) AS population_2014,
  ROUND(100.0 * (finish.SP_POP_TOTL - start.SP_POP_TOTL) / start.SP_POP_TOTL, 1) AS growth_pct
FROM wb_health_population AS start
JOIN wb_health_population AS finish
  ON finish.country_code = start.country_code
WHERE strftime('%Y', start.year) = '1990'
  AND strftime('%Y', finish.year) = '2014'
  AND start.SP_POP_TOTL > 1000000
ORDER BY growth_pct DESC
LIMIT 20;
