-- Recorded births per year in the US names data and in France, side by side
-- for the years both cover.

-- US births per year, summed over every name.
WITH us AS (
  SELECT CAST(strftime('%Y', ds) AS INTEGER) AS year, SUM(num) AS births
  FROM birth_names
  GROUP BY 1
),
-- France's births per year. The table has a column per year, so each year
-- is selected separately and stacked into rows.
france AS (
  SELECT 2003 AS year, SUM("2003") AS births FROM birth_france_by_region
  UNION ALL SELECT 2004, SUM("2004") FROM birth_france_by_region
  UNION ALL SELECT 2005, SUM("2005") FROM birth_france_by_region
  UNION ALL SELECT 2006, SUM("2006") FROM birth_france_by_region
  UNION ALL SELECT 2007, SUM("2007") FROM birth_france_by_region
  UNION ALL SELECT 2008, SUM("2008") FROM birth_france_by_region
)
SELECT
  us.year,
  us.births AS us_births,
  france.births AS france_births
FROM us
JOIN france ON france.year = us.year
ORDER BY us.year;
