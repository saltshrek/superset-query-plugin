-- Everyone below a manager, with their depth in the hierarchy and the
-- chain of names above them.
SELECT
  e.employee_id,
  LPAD(' ', 2 * (LEVEL - 1)) || e.last_name AS name,
  LEVEL AS depth,
  SYS_CONNECT_BY_PATH(e.last_name, ' / ') AS chain
FROM hr.employees e
START WITH e.manager_id IS NULL
CONNECT BY PRIOR e.employee_id = e.manager_id
ORDER SIBLINGS BY e.last_name
