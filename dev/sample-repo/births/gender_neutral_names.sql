-- Names given to both boys and girls, where neither gender has more than
-- 80% of the births.
SELECT
  name,
  SUM(num_boys) AS boys,
  SUM(num_girls) AS girls,
  ROUND(1.0 * SUM(num_girls) / SUM(num), 2) AS girl_share
FROM birth_names
GROUP BY name
HAVING SUM(num) >= 5000
  AND 1.0 * SUM(num_girls) / SUM(num) BETWEEN 0.2 AND 0.8
ORDER BY SUM(num) DESC;
