-- When each platform's games were released, and how much they sold.
SELECT
  platform,
  CAST(MIN(year) AS INTEGER) AS first_year,
  CAST(MAX(year) AS INTEGER) AS last_year,
  COUNT(*) AS games,
  ROUND(SUM(global_sales), 2) AS sales_millions
FROM video_game_sales
WHERE year IS NOT NULL
GROUP BY platform
ORDER BY first_year, platform;
