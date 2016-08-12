CREATE ROLE postgres;
ALTER ROLE postgres WITH login;
GRANT ALL ON SCHEMA public TO postgres;