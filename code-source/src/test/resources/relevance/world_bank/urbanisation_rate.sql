-- Share of the population living in cities, per region, every ten years.
SELECT
  region,
  strftime('%Y', year) AS year,
  ROUND(100.0 * SUM(SP_URB_TOTL) / SUM(SP_POP_TOTL), 1) AS urban_pct
FROM wb_health_population
WHERE strftime('%Y', year) IN ('1970', '1980', '1990', '2000', '2010')
  AND SP_URB_TOTL IS NOT NULL
  AND SP_POP_TOTL IS NOT NULL
GROUP BY region, 2
ORDER BY region, year;
