-- France's births per department and year, unpivoted from one column per
-- year into one row per year.
SELECT DEPT_ID AS department, 2003 AS year, "2003" AS births FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2004, "2004" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2005, "2005" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2006, "2006" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2007, "2007" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2008, "2008" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2009, "2009" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2010, "2010" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2011, "2011" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2012, "2012" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2013, "2013" FROM birth_france_by_region
UNION ALL SELECT DEPT_ID, 2014, "2014" FROM birth_france_by_region
ORDER BY department, year;
