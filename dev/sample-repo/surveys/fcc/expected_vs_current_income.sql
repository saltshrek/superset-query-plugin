-- What respondents earned last year against what they expect to earn in
-- their first developer job, by age band.
SELECT
  CASE
    WHEN age < 25 THEN 'under 25'
    WHEN age < 35 THEN '25-34'
    WHEN age < 45 THEN '35-44'
    ELSE '45 and over'
  END AS age_band,
  COUNT(*) AS respondents,
  ROUND(AVG(last_yr_income)) AS avg_income_last_year,
  ROUND(AVG(expected_earn)) AS avg_expected_earnings
FROM "FCC 2018 Survey"
WHERE age IS NOT NULL
  AND last_yr_income BETWEEN 1 AND 500000
  AND expected_earn BETWEEN 1 AND 500000
GROUP BY 1
ORDER BY MIN(age);
