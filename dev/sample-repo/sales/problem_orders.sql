-- Cancelled orders, largest first.
SELECT
  order_number,
  customer_name,
  ROUND(SUM(sales), 2) AS order_value
FROM cleaned_sales_data
WHERE status = 'Cancelled'
GROUP BY order_number, customer_name
ORDER BY order_value DESC;

-- Customers with more than one order that's disputed or on hold, and how
-- much those orders are worth.

-- One row per problem order.
WITH problem_orders AS (
  SELECT order_number, customer_name, SUM(sales) AS order_value
  FROM cleaned_sales_data
  WHERE status IN ('Disputed', 'On Hold')
  GROUP BY order_number, customer_name
)
SELECT
  customer_name,
  COUNT(*) AS problem_orders,
  ROUND(SUM(order_value), 2) AS value_at_risk
FROM problem_orders
GROUP BY customer_name
HAVING COUNT(*) > 1
ORDER BY value_at_risk DESC;
