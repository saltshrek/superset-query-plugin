-- Each project's phases with start and end dates. Times are stored as
-- milliseconds since the epoch.
SELECT
  project,
  phase,
  status,
  priority,
  datetime(start_time / 1000, 'unixepoch') AS started,
  datetime(end_time / 1000, 'unixepoch') AS ended,
  ROUND((end_time - start_time) / 3600000.0, 1) AS hours
FROM project_management
ORDER BY project, start_time;
