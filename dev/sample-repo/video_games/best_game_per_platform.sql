-- The best-selling game on each platform.

WITH ranked AS (
  SELECT
    platform,
    name,
    global_sales,
    RANK() OVER (PARTITION BY platform ORDER BY global_sales DESC) AS rnk
  FROM video_game_sales
)
SELECT platform, name, global_sales
FROM ranked
WHERE rnk = 1
ORDER BY global_sales DESC;
