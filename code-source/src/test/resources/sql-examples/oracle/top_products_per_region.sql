-- The three best-selling products in each region, with the region's
-- other products listed alongside.

-- Sales per product and region, ranked within the region.
WITH ranked AS (
  SELECT
    s.region,
    p.product_name,
    SUM(s.quantity) AS units,
    RANK() OVER (PARTITION BY s.region ORDER BY SUM(s.quantity) DESC) AS rnk
  FROM sales.order_lines s
  JOIN sales.products p ON p.product_id = s.product_id
  GROUP BY s.region, p.product_name
)
SELECT
  region,
  LISTAGG(CASE WHEN rnk <= 3 THEN product_name END, ', ')
    WITHIN GROUP (ORDER BY rnk) AS top_three,
  COUNT(CASE WHEN rnk > 3 THEN 1 END) AS others
FROM ranked
GROUP BY region
ORDER BY region
FETCH FIRST 20 ROWS ONLY
