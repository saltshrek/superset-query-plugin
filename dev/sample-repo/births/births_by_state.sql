-- Total recorded births per US state, largest first.
SELECT
  state,
  SUM(num) AS births
FROM birth_names
GROUP BY state
ORDER BY births DESC;
