-- Revenue per month, with a running total for each year.

WITH monthly AS (
  SELECT year, month, SUM(sales) AS revenue
  FROM cleaned_sales_data
  GROUP BY year, month
)
SELECT
  year,
  month,
  ROUND(revenue, 2) AS revenue,
  ROUND(SUM(revenue) OVER (PARTITION BY year ORDER BY month), 2) AS year_to_date
FROM monthly
ORDER BY year, month;
