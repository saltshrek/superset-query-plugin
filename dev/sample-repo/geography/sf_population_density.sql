-- San Francisco zip codes by people per square mile.
SELECT
  zipcode,
  population,
  ROUND(area, 2) AS square_miles,
  ROUND(population / area) AS people_per_square_mile
FROM sf_population_polygons
WHERE area > 0
ORDER BY people_per_square_mile DESC;
