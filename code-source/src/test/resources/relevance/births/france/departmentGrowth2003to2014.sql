-- Change in births per French department between 2003 and 2014.
SELECT
  DEPT_ID AS department,
  "2003" AS births_2003,
  "2014" AS births_2014,
  ROUND(100.0 * ("2014" - "2003") / "2003", 1) AS pct_change
FROM birth_france_by_region
ORDER BY pct_change DESC;
