-- =====================================================================
-- V9: Seed admin roles and the first (super) admin account
-- =====================================================================
-- SECURITY: the admin's email and password are NOT written in this file.
-- They are Flyway placeholders, filled in at migration time from
-- configuration / environment variables:
--     seed_admin_email
--     seed_admin_password
-- Flyway refuses to run this script if either value is missing.
--
-- The password is hashed inside PostgreSQL with BCrypt (pgcrypto,
-- cost 10, "$2a$" format), which Spring Security's BCryptPasswordEncoder
-- can verify. Only the hash is stored.
--
-- The account starts with password status MUST_CHANGE, so the admin is
-- forced to replace the seeded password on first login.
--
-- ON CONFLICT ... DO NOTHING keeps the script safe to re-run.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ---------------------------------------------------------------------
-- Roles (RC-6)
-- ---------------------------------------------------------------------
INSERT INTO public.ec_role (rolename, roledescription)
VALUES
    ('SUPER_ADMIN', 'Full access, including managing other admins'),
    ('ADMIN',       'Store management: products, categories, inventory, orders, coupons, reviews')
ON CONFLICT (rolename) DO NOTHING;


-- ---------------------------------------------------------------------
-- First admin profile
-- ---------------------------------------------------------------------
INSERT INTO public.ec_admin (adminroleid, adminfirstname, adminlastname, adminemail)
SELECT roleid, 'System', 'Administrator', '${seed_admin_email}'
FROM public.ec_role
WHERE rolename = 'SUPER_ADMIN'
ON CONFLICT (adminemail) DO NOTHING;


-- ---------------------------------------------------------------------
-- First admin credentials (1:1 with the profile above)
-- ---------------------------------------------------------------------
INSERT INTO public.ec_admin_auth (adminauthadminid, adminauthpasswordhash, adminauthpasswordstatus)
SELECT adminid, crypt('${seed_admin_password}', gen_salt('bf', 10)), 'MUST_CHANGE'
FROM public.ec_admin
WHERE adminemail = '${seed_admin_email}'
ON CONFLICT (adminauthadminid) DO NOTHING;
