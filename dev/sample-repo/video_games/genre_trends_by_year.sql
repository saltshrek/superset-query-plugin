-- Global sales per genre per release year, for a stacked chart.
SELECT
  CAST(year AS INTEGER) AS year,
  genre,
  ROUND(SUM(global_sales), 2) AS sales_millions
FROM video_game_sales
WHERE year BETWEEN 1990 AND 2016
GROUP BY 1, 2
ORDER BY 1, 3 DESC;
