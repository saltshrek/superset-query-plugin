-- Share of each country's revenue by deal size, using conditional
-- aggregation to pivot deal sizes into columns.
SELECT
  country,
  ROUND(SUM(sales), 2) AS revenue,
  ROUND(100.0 * SUM(sales) FILTER (WHERE deal_size = 'Small') / SUM(sales), 1) AS small_pct,
  ROUND(100.0 * SUM(sales) FILTER (WHERE deal_size = 'Medium') / SUM(sales), 1) AS medium_pct,
  ROUND(100.0 * SUM(sales) FILTER (WHERE deal_size = 'Large') / SUM(sales), 1) AS large_pct
FROM cleaned_sales_data
GROUP BY country
ORDER BY revenue DESC;
