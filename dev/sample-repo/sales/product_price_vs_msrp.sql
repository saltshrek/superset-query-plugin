-- Average selling price against list price (MSRP) per product.
SELECT
  product_code,
  product_line,
  msrp,
  ROUND(AVG(price_each), 2) AS avg_price,
  ROUND(100.0 * (AVG(price_each) - msrp) / msrp, 1) AS pct_vs_msrp
FROM cleaned_sales_data
GROUP BY product_code, product_line, msrp
ORDER BY pct_vs_msrp;
