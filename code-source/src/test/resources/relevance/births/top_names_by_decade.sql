-- The five most popular names for each gender in every decade.

WITH by_decade AS (
  SELECT
    (CAST(strftime('%Y', ds) AS INTEGER) / 10) * 10 AS decade,
    gender,
    name,
    SUM(num) AS births
  FROM birth_names
  GROUP BY 1, 2, 3
),
ranked AS (
  SELECT
    *,
    ROW_NUMBER() OVER (PARTITION BY decade, gender ORDER BY births DESC) AS rnk
  FROM by_decade
)
SELECT decade, gender, rnk, name, births
FROM ranked
WHERE rnk <= 5
ORDER BY decade, gender, rnk;
