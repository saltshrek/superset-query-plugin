# Video games

Sales of **16,000+ games** by platform, genre and region, from the
[VGChartz dataset](https://www.kaggle.com/datasets/gregorut/videogamesales)
(`video_game_sales`). Figures are *millions of units*, not revenue, and
stop in 2016.

## Conventions

- Regions are `na_sales`, `eu_sales`, `jp_sales` and `other_sales`;
  `global_sales` is their sum.
- Use `rank` only within a year: it was assigned across the whole set.

## Known problems

Some 2017+ rows exist but are incomplete, so most queries filter
`year <= 2016`.
