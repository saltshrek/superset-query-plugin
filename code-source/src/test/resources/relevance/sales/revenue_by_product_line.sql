-- Revenue, orders and units per product line.
SELECT
  product_line,
  COUNT(DISTINCT order_number) AS orders,
  SUM(quantity_ordered) AS units,
  ROUND(SUM(sales), 2) AS revenue
FROM cleaned_sales_data
GROUP BY product_line
ORDER BY revenue DESC;
