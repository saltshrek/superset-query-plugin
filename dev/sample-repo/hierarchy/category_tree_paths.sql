-- Every node of a hierarchy with its full path from the root, built with a
-- recursive CTE.

WITH RECURSIVE tree AS (
  SELECT id, name, count, name AS path, 0 AS depth
  FROM hierarchical_dataset
  WHERE parent IS NULL
  UNION ALL
  SELECT child.id, child.name, child.count, tree.path || ' / ' || child.name, tree.depth + 1
  FROM hierarchical_dataset AS child
  JOIN tree ON child.parent = tree.id
)
SELECT path, depth, count
FROM tree
ORDER BY path;
