-- Sales per genre in each region, stacked into rows from the per-region
-- columns.

WITH regional AS (
  SELECT genre, 'North America' AS region, na_sales AS sales FROM video_game_sales
  UNION ALL SELECT genre, 'Europe', eu_sales FROM video_game_sales
  UNION ALL SELECT genre, 'Japan', jp_sales FROM video_game_sales
  UNION ALL SELECT genre, 'Other', other_sales FROM video_game_sales
)
SELECT genre, region, ROUND(SUM(sales), 2) AS sales_millions
FROM regional
GROUP BY genre, region
ORDER BY genre, sales_millions DESC;
