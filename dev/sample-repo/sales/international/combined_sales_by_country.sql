-- Revenue per country from both sales datasets, stacked with UNION ALL and
-- then totalled. The source column shows where each figure came from.

WITH combined AS (
  SELECT country, 'classic models' AS source, sales AS revenue
  FROM cleaned_sales_data
  UNION ALL
  SELECT country, 'international', revenue
  FROM international_sales
)
SELECT
  country,
  ROUND(SUM(revenue) FILTER (WHERE source = 'classic models'), 2) AS classic_models,
  ROUND(SUM(revenue) FILTER (WHERE source = 'international'), 2) AS international,
  ROUND(SUM(revenue), 2) AS total
FROM combined
GROUP BY country
ORDER BY total DESC;
