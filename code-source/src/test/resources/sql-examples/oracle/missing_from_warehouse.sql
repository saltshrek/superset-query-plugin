-- Products sold in the last quarter that the reporting warehouse doesn't
-- know about yet.
SELECT product_id
FROM sales.order_lines
WHERE order_date >= ADD_MONTHS(TRUNC(SYSDATE, 'Q'), -3)
MINUS
SELECT product_id
FROM dw.dim_product@reporting_link
