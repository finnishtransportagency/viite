DO $block$
DECLARE
    -- APP on aina publicissa
    app_schema CONSTANT TEXT := 'public';

    dev_users_csv TEXT := COALESCE(NULLIF(current_setting('app.dev_users', true), ''), '');
    base_password_prefix TEXT := COALESCE(NULLIF(current_setting('app.base_password_prefix', true), ''), 'CHANGE_ME_');

    dev_users TEXT[] := CASE
        WHEN dev_users_csv = '' THEN ARRAY[]::TEXT[]
        ELSE string_to_array(dev_users_csv, ',')
    END;

    dev_user TEXT;
    dev_password TEXT;

    -- “primary” db user (oletus viite_dev)
    primary_db_user TEXT := COALESCE(NULLIF(current_setting('app.primary_db_user', true), ''), 'viite_dev');
BEGIN
    -- Varmista PostGIS
    IF NOT EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'postgis'
    ) THEN
        RAISE EXCEPTION
            'PostGIS extension puuttuu tietokannasta. Aja admin-oikeuksin: CREATE EXTENSION IF NOT EXISTS postgis;';
    END IF;

    -- Primary DB user: public käyttö + search_path public
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = primary_db_user) THEN
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I;', primary_db_user);
        EXECUTE format(
            'ALTER ROLE %I IN DATABASE %I SET search_path = public;',
            primary_db_user, current_database()
        );
        RAISE NOTICE 'Primary DB user % search_path => public', primary_db_user;
    ELSE
        RAISE NOTICE 'Primary DB user % ei löytynyt rooleista, ohitetaan', primary_db_user;
    END IF;

    -- Dev users
    FOREACH dev_user IN ARRAY dev_users LOOP
        dev_user := btrim(dev_user);
        IF dev_user = '' THEN
            CONTINUE;
        END IF;

        dev_password := base_password_prefix || dev_user;
        RAISE NOTICE 'Käsitellään käyttäjä: %', dev_user;

        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = dev_user) THEN
            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L;', dev_user, dev_password);
            RAISE NOTICE '  Luo rooli % ja aseta salasana', dev_user;
        ELSE
            RAISE NOTICE '  Rooli % on jo olemassa, ohitetaan', dev_user;
        END IF;

        -- Oma skeema käyttäjälle
        EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I;', dev_user);
        RAISE NOTICE '  Skeema % luotu/olemassa', dev_user;

        -- Täydet oikeudet omaan skeemaan
        EXECUTE format('GRANT USAGE, CREATE ON SCHEMA %I TO %I;', dev_user, dev_user);

        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES TO %I;',
            dev_user, dev_user
        );
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO %I;',
            dev_user, dev_user
        );

        -- Read-only publiciin (app-taulut + PostGIS)
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I;', dev_user);
        EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I;', dev_user);
        EXECUTE format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO %I;', dev_user);

        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO %I;',
            dev_user
        );
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO %I;',
            dev_user
        );

        -- search_path: oma skeema ensin, sitten public
        EXECUTE format(
            'ALTER ROLE %I IN DATABASE %I SET search_path = %I, public;',
            dev_user, current_database(), dev_user
        );
    END LOOP;
END
$block$;
