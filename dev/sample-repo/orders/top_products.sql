-- Ten best-selling products by quantity.
SELECT
  product_code,
  product_line,
  SUM(quantity_ordered) AS units_sold
FROM cleaned_sales_data
GROUP BY product_code, product_line
ORDER BY units_sold DESC
LIMIT 10;
