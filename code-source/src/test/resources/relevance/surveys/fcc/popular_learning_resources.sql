-- How many respondents use each learning resource. Each resource is its
-- own 0/1 column, so they're stacked with UNION ALL.

WITH resources AS (
  SELECT 'freeCodeCamp' AS resource, rsrc_fcc AS used FROM "FCC 2018 Survey"
  UNION ALL SELECT 'MDN', rsrc_mdn FROM "FCC 2018 Survey"
  UNION ALL SELECT 'Stack Overflow', rsrc_so FROM "FCC 2018 Survey"
  UNION ALL SELECT 'Udemy', rsrc_udemy FROM "FCC 2018 Survey"
  UNION ALL SELECT 'Coursera', rsrc_coursera FROM "FCC 2018 Survey"
  UNION ALL SELECT 'edX', rsrc_edx FROM "FCC 2018 Survey"
  UNION ALL SELECT 'Codecademy', rsrc_codeacdm FROM "FCC 2018 Survey"
  UNION ALL SELECT 'Khan Academy', rsrc_khan_acdm FROM "FCC 2018 Survey"
)
SELECT resource, SUM(used) AS users
FROM resources
GROUP BY resource
ORDER BY users DESC;
