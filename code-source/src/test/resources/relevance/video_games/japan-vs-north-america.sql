-- Genres that sell much better in Japan than in North America, relative
-- to each region's total.

WITH totals AS (
  SELECT SUM(jp_sales) AS jp_total, SUM(na_sales) AS na_total
  FROM video_game_sales
)
SELECT
  genre,
  ROUND(100.0 * SUM(jp_sales) / totals.jp_total, 1) AS share_of_japan_pct,
  ROUND(100.0 * SUM(na_sales) / totals.na_total, 1) AS share_of_na_pct
FROM video_game_sales
CROSS JOIN totals
GROUP BY genre
ORDER BY share_of_japan_pct - share_of_na_pct DESC;
