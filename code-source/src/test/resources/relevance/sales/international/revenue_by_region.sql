-- Revenue, cost and profit per region.
SELECT
  region,
  ROUND(SUM(revenue), 2) AS revenue,
  ROUND(SUM(cost), 2) AS cost,
  ROUND(SUM(profit), 2) AS profit
FROM international_sales
GROUP BY region
ORDER BY revenue DESC;
