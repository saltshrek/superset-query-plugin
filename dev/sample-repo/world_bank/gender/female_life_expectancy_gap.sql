-- How many more years women can expect to live than men, by region.
SELECT
  region,
  ROUND(AVG(SP_DYN_LE00_FE_IN), 1) AS female,
  ROUND(AVG(SP_DYN_LE00_MA_IN), 1) AS male,
  ROUND(AVG(SP_DYN_LE00_FE_IN - SP_DYN_LE00_MA_IN), 1) AS gap_years
FROM wb_health_population
WHERE strftime('%Y', year) = '2012'
GROUP BY region
ORDER BY gap_years DESC;
