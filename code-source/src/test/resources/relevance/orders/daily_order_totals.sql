-- Orders and revenue per day.
SELECT
  date(order_date) AS day,
  COUNT(DISTINCT order_number) AS orders,
  ROUND(SUM(sales), 2) AS revenue
FROM cleaned_sales_data
GROUP BY 1
ORDER BY 1;
