-- The twenty customers who spent the most, with where they are.
SELECT
  customer_name,
  country,
  city,
  COUNT(DISTINCT order_number) AS orders,
  ROUND(SUM(sales), 2) AS revenue
FROM cleaned_sales_data
GROUP BY customer_name, country, city
ORDER BY revenue DESC
LIMIT 20;
