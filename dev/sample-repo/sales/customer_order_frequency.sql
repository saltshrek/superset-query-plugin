-- How many customers placed one order, two to five, or more.

WITH orders_per_customer AS (
  SELECT customer_name, COUNT(DISTINCT order_number) AS orders
  FROM cleaned_sales_data
  GROUP BY customer_name
)
SELECT
  CASE
    WHEN orders = 1 THEN '1'
    WHEN orders <= 5 THEN '2-5'
    ELSE '6+'
  END AS orders_placed,
  COUNT(*) AS customers
FROM orders_per_customer
GROUP BY 1
ORDER BY MIN(orders);
