-- Revenue per quarter, and the change from the quarter before.

WITH quarterly AS (
  SELECT year, quarter, SUM(sales) AS revenue
  FROM cleaned_sales_data
  GROUP BY year, quarter
)
SELECT
  year,
  quarter,
  ROUND(revenue, 2) AS revenue,
  ROUND(100.0 * (revenue - LAG(revenue) OVER w) / LAG(revenue) OVER w, 1) AS pct_change
FROM quarterly
WINDOW w AS (ORDER BY year, quarter)
ORDER BY year, quarter;
