-- Total revenue per sales territory, highest first.
SELECT
  COALESCE(territory, 'Americas') AS territory,
  COUNT(DISTINCT country) AS countries,
  ROUND(SUM(sales), 2) AS revenue
FROM cleaned_sales_data
GROUP BY 1
ORDER BY revenue DESC;
