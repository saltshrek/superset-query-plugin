-- Share of each month's customers who didn't order again the next month.

WITH active AS (
  SELECT DISTINCT customer_name, year * 12 + month AS month_number
  FROM cleaned_sales_data
)
SELECT
  (this_month.month_number - 1) / 12 AS year,
  (this_month.month_number - 1) % 12 + 1 AS month,
  COUNT(*) AS active_customers,
  ROUND(1.0 * COUNT(*) FILTER (WHERE next_month.customer_name IS NULL) / COUNT(*), 2) AS churn_rate
FROM active AS this_month
LEFT JOIN active AS next_month
  ON next_month.customer_name = this_month.customer_name
  AND next_month.month_number = this_month.month_number + 1
GROUP BY this_month.month_number
ORDER BY this_month.month_number;
