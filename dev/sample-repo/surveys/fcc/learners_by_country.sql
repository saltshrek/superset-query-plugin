-- Where the freeCodeCamp 2018 survey's respondents live.
SELECT
  country_live AS country,
  COUNT(*) AS respondents,
  ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM "FCC 2018 Survey"
WHERE country_live IS NOT NULL
GROUP BY country_live
ORDER BY respondents DESC
LIMIT 25;
