-- Each publisher's share of global sales. Publishers outside the top ten
-- are grouped as 'Other'.

-- Global sales per publisher, in millions of units.
WITH by_publisher AS (
  SELECT publisher, SUM(global_sales) AS sales
  FROM video_game_sales
  GROUP BY publisher
),
-- Publishers ranked by sales, best first.
ranked AS (
  SELECT *, RANK() OVER (ORDER BY sales DESC) AS rnk
  FROM by_publisher
)
SELECT
  CASE WHEN rnk <= 10 THEN publisher ELSE 'Other' END AS publisher,
  ROUND(SUM(sales), 2) AS sales_millions,
  ROUND(100.0 * SUM(sales) / (SELECT SUM(sales) FROM by_publisher), 1) AS share_pct
FROM ranked
GROUP BY 1
ORDER BY MIN(rnk);
