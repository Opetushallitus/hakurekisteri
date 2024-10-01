CREATE ROLE postgres;
ALTER ROLE postgres WITH login;
GRANT ALL ON SCHEMA public TO postgres;

CREATE ROLE oph;
ALTER ROLE oph WITH login;
GRANT ALL ON SCHEMA public TO oph;