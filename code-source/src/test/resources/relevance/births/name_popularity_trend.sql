-- Births per year for one name. Change the name in the WHERE clause.
SELECT
  strftime('%Y', ds) AS year,
  SUM(num_boys) AS boys,
  SUM(num_girls) AS girls,
  SUM(num) AS total
FROM birth_names
WHERE name = 'Jordan'
GROUP BY 1
ORDER BY 1;
