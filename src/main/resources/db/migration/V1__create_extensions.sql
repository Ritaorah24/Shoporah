-- =====================================================================
-- V1: PostgreSQL extensions required by the E-commerce schema
-- =====================================================================
-- citext  : case-insensitive text (emails, codes, SKUs, statuses, names)
-- pg_trgm : trigram index for partial-word product name search
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
