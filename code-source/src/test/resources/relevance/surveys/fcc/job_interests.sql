-- Which developer roles respondents are interested in.
SELECT 'Full-stack' AS role, SUM(job_intr_fllstck) AS interested FROM "FCC 2018 Survey"
UNION ALL SELECT 'Front-end', SUM(job_intr_frntend) FROM "FCC 2018 Survey"
UNION ALL SELECT 'Back-end', SUM(job_intr_backend) FROM "FCC 2018 Survey"
UNION ALL SELECT 'Data science', SUM(job_intr_datasci) FROM "FCC 2018 Survey"
UNION ALL SELECT 'Data engineering', SUM(job_intr_dataengn) FROM "FCC 2018 Survey"
UNION ALL SELECT 'Mobile', SUM(job_intr_mobile) FROM "FCC 2018 Survey"
UNION ALL SELECT 'DevOps', SUM(job_intr_devops) FROM "FCC 2018 Survey"
UNION ALL SELECT 'Game development', SUM(job_intr_gamedev) FROM "FCC 2018 Survey"
UNION ALL SELECT 'UX', SUM(job_intr_ux_engn) FROM "FCC 2018 Survey"
UNION ALL SELECT 'Security', SUM(job_intr_infosec) FROM "FCC 2018 Survey"
ORDER BY interested DESC;
