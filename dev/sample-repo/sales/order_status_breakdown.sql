-- Orders and revenue by status, for spotting cancellations and disputes.
SELECT
  status,
  COUNT(DISTINCT order_number) AS orders,
  ROUND(SUM(sales), 2) AS revenue
FROM cleaned_sales_data
GROUP BY status
ORDER BY orders DESC;
