-- Revenue from repeat customers compared with one-time customers.

WITH customers AS (
  SELECT
    customer_name,
    COUNT(DISTINCT order_number) AS orders,
    SUM(sales) AS revenue
  FROM cleaned_sales_data
  GROUP BY customer_name
)
SELECT
  CASE WHEN orders > 1 THEN 'repeat' ELSE 'one-time' END AS customer_type,
  COUNT(*) AS customers,
  ROUND(SUM(revenue), 2) AS revenue,
  ROUND(AVG(revenue), 2) AS revenue_per_customer
FROM customers
GROUP BY 1;
