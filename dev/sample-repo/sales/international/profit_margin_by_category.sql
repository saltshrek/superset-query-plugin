-- Profit margin per product category, best first.
SELECT
  product_category,
  SUM(quantity) AS units,
  ROUND(SUM(revenue), 2) AS revenue,
  ROUND(100.0 * SUM(profit) / SUM(revenue), 1) AS margin_pct
FROM international_sales
GROUP BY product_category
ORDER BY margin_pct DESC;
