-- How often each tag appears on orders, from the array of tags each order
-- carries.
SELECT
  lower(tag) AS tag,
  count(*) AS orders,
  count_if(o.status = 'returned') AS returned
FROM iceberg.sales.orders AS o
CROSS JOIN UNNEST(o.tags) AS t (tag)
WHERE cardinality(o.tags) > 0
GROUP BY lower(tag)
HAVING count(*) >= 10
ORDER BY orders DESC
LIMIT 50
