-- Customers whose first order was in each month.

WITH first_orders AS (
  SELECT customer_name, MIN(order_date) AS first_order_date
  FROM cleaned_sales_data
  GROUP BY customer_name
)
SELECT
  strftime('%Y-%m', first_order_date) AS month,
  COUNT(*) AS new_customers
FROM first_orders
GROUP BY 1
ORDER BY 1;
