-- Fall in under-five mortality (per 1,000 births) between 1990 and 2013,
-- by region.

WITH by_year AS (
  SELECT
    region,
    strftime('%Y', year) AS year,
    AVG(SH_DYN_MORT) AS mortality
  FROM wb_health_population
  WHERE strftime('%Y', year) IN ('1990', '2013')
  GROUP BY 1, 2
)
SELECT
  then_.region,
  ROUND(then_.mortality, 1) AS mortality_1990,
  ROUND(now_.mortality, 1) AS mortality_2013,
  ROUND(100.0 * (then_.mortality - now_.mortality) / then_.mortality, 1) AS reduction_pct
FROM by_year AS then_
JOIN by_year AS now_
  ON now_.region = then_.region AND now_.year = '2013'
WHERE then_.year = '1990'
ORDER BY reduction_pct DESC;
