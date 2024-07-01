CREATE TABLE IF NOT EXISTS siirtotiedosto (
                                               id serial,
                                               uuid varchar,
                                               window_start bigint not null,
                                               window_end bigint not null,
                                               run_start timestamp with time zone,
                                               run_end timestamp with time zone,
                                               info jsonb, --ainakin tilastot tiedostoihin päätyneistä entiteettimääristä tyypeittäin, esim. {"entityTotals": {"suoritus": 300, "arvosana": 13}}
                                               success boolean,
                                               error_message varchar, -- Tyhjä string, jos mikään ei mennyt vikaan
                                               PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS siirtotiedosto_id_seq START 0;

COMMENT ON COLUMN siirtotiedosto.window_start IS 'Siirtotiedosto-operaation aikaikkunan alkuhetki (unixtime)';
COMMENT ON COLUMN siirtotiedosto.window_end IS 'Siirtotiedosto-operaation aikaikkunan loppuhetki (unixtime)';
COMMENT ON COLUMN siirtotiedosto.run_start IS 'Siirtotiedosto-operaation suorituksen alkuaika';
COMMENT ON COLUMN siirtotiedosto.run_end IS 'Siirtotiedosto-operaation suorituksen loppuaika';
COMMENT ON COLUMN siirtotiedosto.info IS 'Tietoja tallennetuista entiteeteistä, mm. lukumäärät';
COMMENT ON COLUMN siirtotiedosto.error_message IS 'null, jos mikään ei mennyt vikaan';

--todo, what are the initial values? This is just something to get the poc started.
INSERT INTO siirtotiedosto(id, uuid, window_start, window_end, run_start, run_end, info, success, error_message)
VALUES (nextval('siirtotiedosto_id_seq'), '57be2612-ba79-429e-a93e-c38346f1d62d',  0, 1719401507582, now(), now(), '{"entityTotals": {}}'::jsonb, true, null) ON CONFLICT DO NOTHING;