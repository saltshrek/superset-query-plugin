-- Names with the biggest jump in births from one year to the next.

WITH yearly AS (
  SELECT name, strftime('%Y', ds) AS year, SUM(num) AS births
  FROM birth_names
  GROUP BY 1, 2
),
with_previous AS (
  SELECT
    *,
    LAG(births) OVER (PARTITION BY name ORDER BY year) AS previous_births
  FROM yearly
)
SELECT
  name,
  year,
  previous_births,
  births,
  births - previous_births AS increase
FROM with_previous
WHERE previous_births IS NOT NULL
ORDER BY increase DESC
LIMIT 25;
