-- The best-selling product in each country, by revenue.
SELECT country, product_name, revenue
FROM (
  SELECT
    country,
    product_name,
    SUM(revenue) AS revenue,
    RANK() OVER (PARTITION BY country ORDER BY SUM(revenue) DESC) AS rnk
  FROM international_sales
  GROUP BY country, product_name
)
WHERE rnk = 1
ORDER BY revenue DESC;
