-- Each state's share of all births, using a window over the grouped totals.
SELECT
  state,
  SUM(num) AS births,
  ROUND(100.0 * SUM(num) / SUM(SUM(num)) OVER (), 2) AS pct_of_total
FROM birth_names
GROUP BY state
ORDER BY births DESC;
