-- Jobs, people and invitations filed their department as typed text, beside a
-- department_id that almost nothing set. From here the id is what counts, and
-- the text is a copy of the department's name that the API keeps in step (the
-- public job board still filters on it).
--
-- 1. Let go of any department that belongs to another company: the API used to
--    take a department id without checking whose it was.
-- 2. Give every distinct name a company has typed a department row, matching
--    names case-insensitively, so "Engineering" and "engineering" become one.
-- 3. Point each row at its department, and make its text the department's name.

-- 1
UPDATE jobs j SET department_id = NULL
  FROM departments d
 WHERE j.department_id = d.id
   AND d.organization_id <> j.organization_id;

UPDATE users u SET department_id = NULL
  FROM departments d
 WHERE u.department_id = d.id
   AND (u.organization_id IS NULL OR d.organization_id <> u.organization_id);

UPDATE user_invitations i SET department_id = NULL
  FROM departments d
 WHERE i.department_id = d.id
   AND d.organization_id <> i.organization_id;

-- 2
INSERT INTO departments (organization_id, name, is_active, created_at, updated_at)
SELECT DISTINCT ON (organization_id, lower(name))
       organization_id, name, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM (
        SELECT organization_id, trim(department) AS name
          FROM jobs
         WHERE department_id IS NULL AND trim(coalesce(department, '')) <> ''
        UNION ALL
        SELECT organization_id, trim(department)
          FROM users
         WHERE department_id IS NULL AND organization_id IS NOT NULL
           AND trim(coalesce(department, '')) <> ''
        UNION ALL
        SELECT organization_id, trim(department)
          FROM user_invitations
         WHERE department_id IS NULL AND trim(coalesce(department, '')) <> ''
       ) typed
 WHERE NOT EXISTS (
        SELECT 1 FROM departments d
         WHERE d.organization_id = typed.organization_id
           AND lower(d.name) = lower(typed.name)
       )
 -- Of the spellings a company used, keep the capitalised one, whatever the
 -- database's collation.
 ORDER BY organization_id, lower(name), name COLLATE "C";

-- 3
UPDATE jobs j SET department_id = d.id
  FROM departments d
 WHERE j.department_id IS NULL AND trim(coalesce(j.department, '')) <> ''
   AND d.organization_id = j.organization_id
   AND lower(d.name) = lower(trim(j.department));

UPDATE users u SET department_id = d.id
  FROM departments d
 WHERE u.department_id IS NULL AND u.organization_id IS NOT NULL
   AND trim(coalesce(u.department, '')) <> ''
   AND d.organization_id = u.organization_id
   AND lower(d.name) = lower(trim(u.department));

UPDATE user_invitations i SET department_id = d.id
  FROM departments d
 WHERE i.department_id IS NULL AND trim(coalesce(i.department, '')) <> ''
   AND d.organization_id = i.organization_id
   AND lower(d.name) = lower(trim(i.department));

UPDATE jobs j SET department = d.name
  FROM departments d
 WHERE j.department_id = d.id AND j.department IS DISTINCT FROM d.name;

UPDATE users u SET department = d.name
  FROM departments d
 WHERE u.department_id = d.id AND u.department IS DISTINCT FROM d.name;

UPDATE user_invitations i SET department = d.name
  FROM departments d
 WHERE i.department_id = d.id AND i.department IS DISTINCT FROM d.name;
