-- Each product line's best quarter by revenue.

WITH quarterly AS (
  SELECT product_line, year, quarter, SUM(sales) AS revenue
  FROM cleaned_sales_data
  GROUP BY product_line, year, quarter
),
ranked AS (
  SELECT
    *,
    RANK() OVER (PARTITION BY product_line ORDER BY revenue DESC) AS rnk
  FROM quarterly
)
SELECT product_line, year, quarter, ROUND(revenue, 2) AS revenue
FROM ranked
WHERE rnk = 1
ORDER BY revenue DESC;
