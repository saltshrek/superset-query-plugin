-- The best-selling games worldwide, in millions of units.
SELECT rank, name, platform, CAST(year AS INTEGER) AS year, publisher, global_sales
FROM video_game_sales
ORDER BY global_sales DESC
LIMIT 25;
