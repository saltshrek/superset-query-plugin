-- Life expectancy against income per person (GNP per capita), grouped
-- into income bands.
SELECT
  CASE
    WHEN NY_GNP_PCAP_CD < 1000 THEN '1: under $1k'
    WHEN NY_GNP_PCAP_CD < 4000 THEN '2: $1k-4k'
    WHEN NY_GNP_PCAP_CD < 12000 THEN '3: $4k-12k'
    ELSE '4: $12k and over'
  END AS income_band,
  COUNT(DISTINCT country_code) AS countries,
  ROUND(AVG(SP_DYN_LE00_IN), 1) AS avg_life_expectancy
FROM wb_health_population
WHERE strftime('%Y', year) = '2010'
  AND NY_GNP_PCAP_CD IS NOT NULL
  AND SP_DYN_LE00_IN IS NOT NULL
GROUP BY 1
ORDER BY 1;
