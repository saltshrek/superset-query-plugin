-- Revenue by region and country, with subtotals per region and a grand
-- total.
SELECT
  coalesce(c.region, 'All regions') AS region,
  coalesce(c.country, 'All countries') AS country,
  sum(o.amount) FILTER (WHERE o.status <> 'cancelled') AS revenue,
  try_cast(avg(o.amount) AS decimal(12, 2)) AS average_order
FROM sales.orders o
JOIN sales.customers c ON c.id = o.customer_id
GROUP BY ROLLUP (c.region, c.country);

-- Orders whose amount isn't a whole number of cents, which shouldn't
-- happen.
SELECT id, amount
FROM sales.orders
WHERE amount * 100 <> floor(amount * 100)
