-- Countries with the largest share of people aged 65 and over, against
-- their share of children under 15.
SELECT
  country_name,
  region,
  ROUND(SP_POP_65UP_TO_ZS, 1) AS aged_65_plus_pct,
  ROUND(SP_POP_0014_TO_ZS, 1) AS under_15_pct
FROM wb_health_population
WHERE strftime('%Y', year) = '2014'
  AND SP_POP_65UP_TO_ZS IS NOT NULL
ORDER BY aged_65_plus_pct DESC
LIMIT 20;
