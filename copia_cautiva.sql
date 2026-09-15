--
-- PostgreSQL database dump
--

\restrict WGmats9iGE1o09LhmPSdcSDlNCYWb9wYrTGgSU1stAh5hrPushP6yFEtq4qhUQz

-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

ALTER TABLE IF EXISTS ONLY public.ubicacion_en_vivo DROP CONSTRAINT IF EXISTS ubicacion_en_vivo_usuario_id_fkey;
ALTER TABLE IF EXISTS ONLY public.ubicacion_en_vivo DROP CONSTRAINT IF EXISTS ubicacion_en_vivo_turno_id_fkey;
ALTER TABLE IF EXISTS ONLY public.turnos_laborales DROP CONSTRAINT IF EXISTS turnos_laborales_vehiculo_id_fkey;
ALTER TABLE IF EXISTS ONLY public.turnos_laborales DROP CONSTRAINT IF EXISTS turnos_laborales_usuario_id_fkey;
ALTER TABLE IF EXISTS ONLY public.perfiles DROP CONSTRAINT IF EXISTS perfiles_id_fkey;
ALTER TABLE IF EXISTS ONLY public.historial_ubicaciones DROP CONSTRAINT IF EXISTS historial_ubicaciones_usuario_id_fkey;
ALTER TABLE IF EXISTS ONLY public.historial_ubicaciones DROP CONSTRAINT IF EXISTS historial_ubicaciones_turno_id_fkey;
DROP INDEX IF EXISTS public.idx_turnos_usuario_estado;
DROP INDEX IF EXISTS public.idx_historial_usuario_fecha;
DROP INDEX IF EXISTS public.idx_historial_turno;
ALTER TABLE IF EXISTS ONLY public.vehiculos DROP CONSTRAINT IF EXISTS vehiculos_placa_key;
ALTER TABLE IF EXISTS ONLY public.vehiculos DROP CONSTRAINT IF EXISTS vehiculos_pkey;
ALTER TABLE IF EXISTS ONLY public.ubicacion_en_vivo DROP CONSTRAINT IF EXISTS ubicacion_en_vivo_pkey;
ALTER TABLE IF EXISTS ONLY public.turnos_laborales DROP CONSTRAINT IF EXISTS turnos_laborales_pkey;
ALTER TABLE IF EXISTS ONLY public.perfiles DROP CONSTRAINT IF EXISTS perfiles_pkey;
ALTER TABLE IF EXISTS ONLY public.historial_ubicaciones DROP CONSTRAINT IF EXISTS historial_ubicaciones_pkey;
DROP TABLE IF EXISTS public.vehiculos;
DROP TABLE IF EXISTS public.ubicacion_en_vivo;
DROP TABLE IF EXISTS public.turnos_laborales;
DROP TABLE IF EXISTS public.perfiles;
DROP TABLE IF EXISTS public.historial_ubicaciones;
DROP FUNCTION IF EXISTS public.crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text);
DROP FUNCTION IF EXISTS public.crear_perfil_nuevo_usuario();
DROP SCHEMA IF EXISTS public;
--
-- Name: public; Type: SCHEMA; Schema: -; Owner: pg_database_owner
--

CREATE SCHEMA public;


ALTER SCHEMA public OWNER TO pg_database_owner;

--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: pg_database_owner
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: crear_perfil_nuevo_usuario(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.crear_perfil_nuevo_usuario() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    AS $$
BEGIN
    INSERT INTO public.perfiles (id, nombre, apellido, rol)
    VALUES (
        NEW.id,
        COALESCE(NEW.raw_user_meta_data->>'nombre', 'Nuevo'),
        COALESCE(NEW.raw_user_meta_data->>'apellido', 'Usuario'),
        COALESCE(NEW.raw_user_meta_data->>'rol', 'CONDUCTOR')
    )
    ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.crear_perfil_nuevo_usuario() OWNER TO postgres;

--
-- Name: crear_usuario_sistema(text, text, text, text, text, text); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    AS $$
DECLARE
    v_user_id UUID;
    v_encrypted_pw TEXT;
BEGIN
    v_user_id := gen_random_uuid();
    v_encrypted_pw := crypt(p_password, gen_salt('bf'));

    -- Registro en el módulo de Auth de Supabase
    INSERT INTO auth.users (
        instance_id,
        id,
        aud,
        role,
        email,
        encrypted_password,
        email_confirmed_at,
        raw_app_meta_data,
        raw_user_meta_data,
        created_at,
        updated_at
    ) VALUES (
        '00000000-0000-0000-0000-000000000000',
        v_user_id,
        'authenticated',
        'authenticated',
        p_email,
        v_encrypted_pw,
        NOW(),
        '{"provider":"email","providers":["email"]}'::jsonb,
        json_build_object('nombre', p_nombre, 'apellido', p_apellido, 'rol', p_rol)::jsonb,
        NOW(),
        NOW()
    );

    -- Identidad para permitir login nativo con correo y contraseña
    INSERT INTO auth.identities (
        id,
        user_id,
        identity_data,
        provider,
        provider_id,
        last_sign_in_at,
        created_at,
        updated_at
    ) VALUES (
        gen_random_uuid(),
        v_user_id,
        format('{"sub":"%s","email":"%s"}', v_user_id::text, p_email)::jsonb,
        'email',
        v_user_id::text,
        NOW(),
        NOW(),
        NOW()
    );

    -- Inserción o actualización en la tabla pública de perfiles
    INSERT INTO public.perfiles (id, nombre, apellido, telefono, rol, activo)
    VALUES (v_user_id, p_nombre, p_apellido, p_telefono, p_rol, TRUE)
    ON CONFLICT (id) DO UPDATE 
    SET nombre = EXCLUDED.nombre,
        apellido = EXCLUDED.apellido,
        telefono = EXCLUDED.telefono,
        rol = EXCLUDED.rol;

    RETURN v_user_id;
END;
$$;


ALTER FUNCTION public.crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text) OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: historial_ubicaciones; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.historial_ubicaciones (
    id bigint NOT NULL,
    usuario_id uuid NOT NULL,
    turno_id uuid NOT NULL,
    latitud numeric(10,7) NOT NULL,
    longitud numeric(10,7) NOT NULL,
    velocidad_kmh numeric(5,2) DEFAULT 0.00,
    direccion numeric(5,2),
    nivel_bateria integer,
    fecha_gps timestamp with time zone NOT NULL,
    fecha_recibido timestamp with time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    datos_extra jsonb DEFAULT '{}'::jsonb
);


ALTER TABLE public.historial_ubicaciones OWNER TO postgres;

--
-- Name: historial_ubicaciones_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.historial_ubicaciones ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.historial_ubicaciones_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: perfiles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.perfiles (
    id uuid NOT NULL,
    nombre character varying(100) NOT NULL,
    apellido character varying(100) NOT NULL,
    telefono character varying(25),
    rol character varying(25) DEFAULT 'CONDUCTOR'::character varying NOT NULL,
    activo boolean DEFAULT true,
    metadatos jsonb DEFAULT '{}'::jsonb,
    creado_el timestamp with time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    actualizado_el timestamp with time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    CONSTRAINT perfiles_rol_check CHECK (((rol)::text = ANY ((ARRAY['ADMINISTRADOR'::character varying, 'CONDUCTOR'::character varying, 'SUPERVISOR'::character varying])::text[])))
);


ALTER TABLE public.perfiles OWNER TO postgres;

--
-- Name: turnos_laborales; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.turnos_laborales (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    usuario_id uuid NOT NULL,
    vehiculo_id uuid,
    inicio_programado timestamp with time zone NOT NULL,
    fin_programado timestamp with time zone NOT NULL,
    inicio_real timestamp with time zone,
    fin_real timestamp with time zone,
    estado character varying(25) DEFAULT 'PROGRAMADO'::character varying,
    notas text,
    creado_el timestamp with time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    CONSTRAINT turnos_laborales_estado_check CHECK (((estado)::text = ANY ((ARRAY['PROGRAMADO'::character varying, 'EN_PROGRESO'::character varying, 'FINALIZADO'::character varying, 'CANCELADO'::character varying])::text[])))
);


ALTER TABLE public.turnos_laborales OWNER TO postgres;

--
-- Name: ubicacion_en_vivo; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.ubicacion_en_vivo (
    usuario_id uuid NOT NULL,
    turno_id uuid,
    latitud numeric(10,7) NOT NULL,
    longitud numeric(10,7) NOT NULL,
    velocidad_kmh numeric(5,2) DEFAULT 0.00,
    direccion numeric(5,2),
    nivel_bateria integer,
    en_movimiento boolean DEFAULT false,
    ultima_actualizacion timestamp with time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    CONSTRAINT ubicacion_en_vivo_nivel_bateria_check CHECK (((nivel_bateria >= 0) AND (nivel_bateria <= 100)))
);


ALTER TABLE public.ubicacion_en_vivo OWNER TO postgres;

--
-- Name: vehiculos; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.vehiculos (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    placa character varying(20) NOT NULL,
    marca character varying(50),
    modelo character varying(50),
    anio integer,
    estado character varying(20) DEFAULT 'ACTIVO'::character varying,
    metadatos jsonb DEFAULT '{}'::jsonb,
    creado_el timestamp with time zone DEFAULT timezone('utc'::text, now()) NOT NULL,
    CONSTRAINT vehiculos_estado_check CHECK (((estado)::text = ANY ((ARRAY['ACTIVO'::character varying, 'MANTENIMIENTO'::character varying, 'INACTIVO'::character varying])::text[])))
);


ALTER TABLE public.vehiculos OWNER TO postgres;

--
-- Data for Name: historial_ubicaciones; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.historial_ubicaciones (id, usuario_id, turno_id, latitud, longitud, velocidad_kmh, direccion, nivel_bateria, fecha_gps, fecha_recibido, datos_extra) FROM stdin;
\.


--
-- Data for Name: perfiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.perfiles (id, nombre, apellido, telefono, rol, activo, metadatos, creado_el, actualizado_el) FROM stdin;
5f007622-9a5a-4238-8dc7-b69164b3d299	Javier	Maldonado	+51987654321	CONDUCTOR	t	{}	2026-09-14 19:30:12.160385+00	2026-09-14 19:30:12.160385+00
9985abca-13c3-483a-bb0a-7944c293102e	Frank	Perez	+51987654322	CONDUCTOR	t	{}	2026-09-14 19:30:12.160385+00	2026-09-14 19:30:12.160385+00
7ca7b376-3e28-47a2-80ce-c9b47f44d7a9	Andy	Miranda	+51987654323	CONDUCTOR	t	{}	2026-09-14 19:30:12.160385+00	2026-09-14 19:30:12.160385+00
f422c50d-1cd5-410b-9a6c-f4fa1eaaff88	Martin	Administrador	+51987654320	ADMINISTRADOR	t	{}	2026-09-14 19:30:12.160385+00	2026-09-14 19:30:12.160385+00
\.


--
-- Data for Name: turnos_laborales; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.turnos_laborales (id, usuario_id, vehiculo_id, inicio_programado, fin_programado, inicio_real, fin_real, estado, notas, creado_el) FROM stdin;
\.


--
-- Data for Name: ubicacion_en_vivo; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.ubicacion_en_vivo (usuario_id, turno_id, latitud, longitud, velocidad_kmh, direccion, nivel_bateria, en_movimiento, ultima_actualizacion) FROM stdin;
\.


--
-- Data for Name: vehiculos; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.vehiculos (id, placa, marca, modelo, anio, estado, metadatos, creado_el) FROM stdin;
\.


--
-- Name: historial_ubicaciones_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.historial_ubicaciones_id_seq', 1, false);


--
-- Name: historial_ubicaciones historial_ubicaciones_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.historial_ubicaciones
    ADD CONSTRAINT historial_ubicaciones_pkey PRIMARY KEY (id);


--
-- Name: perfiles perfiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.perfiles
    ADD CONSTRAINT perfiles_pkey PRIMARY KEY (id);


--
-- Name: turnos_laborales turnos_laborales_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.turnos_laborales
    ADD CONSTRAINT turnos_laborales_pkey PRIMARY KEY (id);


--
-- Name: ubicacion_en_vivo ubicacion_en_vivo_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ubicacion_en_vivo
    ADD CONSTRAINT ubicacion_en_vivo_pkey PRIMARY KEY (usuario_id);


--
-- Name: vehiculos vehiculos_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vehiculos
    ADD CONSTRAINT vehiculos_pkey PRIMARY KEY (id);


--
-- Name: vehiculos vehiculos_placa_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vehiculos
    ADD CONSTRAINT vehiculos_placa_key UNIQUE (placa);


--
-- Name: idx_historial_turno; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_historial_turno ON public.historial_ubicaciones USING btree (turno_id);


--
-- Name: idx_historial_usuario_fecha; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_historial_usuario_fecha ON public.historial_ubicaciones USING btree (usuario_id, fecha_gps DESC);


--
-- Name: idx_turnos_usuario_estado; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_turnos_usuario_estado ON public.turnos_laborales USING btree (usuario_id, estado);


--
-- Name: historial_ubicaciones historial_ubicaciones_turno_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.historial_ubicaciones
    ADD CONSTRAINT historial_ubicaciones_turno_id_fkey FOREIGN KEY (turno_id) REFERENCES public.turnos_laborales(id) ON DELETE CASCADE;


--
-- Name: historial_ubicaciones historial_ubicaciones_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.historial_ubicaciones
    ADD CONSTRAINT historial_ubicaciones_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.perfiles(id) ON DELETE CASCADE;


--
-- Name: perfiles perfiles_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.perfiles
    ADD CONSTRAINT perfiles_id_fkey FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: turnos_laborales turnos_laborales_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.turnos_laborales
    ADD CONSTRAINT turnos_laborales_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.perfiles(id) ON DELETE CASCADE;


--
-- Name: turnos_laborales turnos_laborales_vehiculo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.turnos_laborales
    ADD CONSTRAINT turnos_laborales_vehiculo_id_fkey FOREIGN KEY (vehiculo_id) REFERENCES public.vehiculos(id) ON DELETE SET NULL;


--
-- Name: ubicacion_en_vivo ubicacion_en_vivo_turno_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ubicacion_en_vivo
    ADD CONSTRAINT ubicacion_en_vivo_turno_id_fkey FOREIGN KEY (turno_id) REFERENCES public.turnos_laborales(id) ON DELETE SET NULL;


--
-- Name: ubicacion_en_vivo ubicacion_en_vivo_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ubicacion_en_vivo
    ADD CONSTRAINT ubicacion_en_vivo_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.perfiles(id) ON DELETE CASCADE;


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

GRANT USAGE ON SCHEMA public TO postgres;
GRANT USAGE ON SCHEMA public TO anon;
GRANT USAGE ON SCHEMA public TO authenticated;
GRANT USAGE ON SCHEMA public TO service_role;


--
-- Name: FUNCTION crear_perfil_nuevo_usuario(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.crear_perfil_nuevo_usuario() TO anon;
GRANT ALL ON FUNCTION public.crear_perfil_nuevo_usuario() TO authenticated;
GRANT ALL ON FUNCTION public.crear_perfil_nuevo_usuario() TO service_role;


--
-- Name: FUNCTION crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text) TO anon;
GRANT ALL ON FUNCTION public.crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text) TO authenticated;
GRANT ALL ON FUNCTION public.crear_usuario_sistema(p_email text, p_password text, p_nombre text, p_apellido text, p_telefono text, p_rol text) TO service_role;


--
-- Name: TABLE historial_ubicaciones; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.historial_ubicaciones TO anon;
GRANT ALL ON TABLE public.historial_ubicaciones TO authenticated;
GRANT ALL ON TABLE public.historial_ubicaciones TO service_role;


--
-- Name: SEQUENCE historial_ubicaciones_id_seq; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON SEQUENCE public.historial_ubicaciones_id_seq TO anon;
GRANT ALL ON SEQUENCE public.historial_ubicaciones_id_seq TO authenticated;
GRANT ALL ON SEQUENCE public.historial_ubicaciones_id_seq TO service_role;


--
-- Name: TABLE perfiles; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.perfiles TO anon;
GRANT ALL ON TABLE public.perfiles TO authenticated;
GRANT ALL ON TABLE public.perfiles TO service_role;


--
-- Name: TABLE turnos_laborales; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.turnos_laborales TO anon;
GRANT ALL ON TABLE public.turnos_laborales TO authenticated;
GRANT ALL ON TABLE public.turnos_laborales TO service_role;


--
-- Name: TABLE ubicacion_en_vivo; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.ubicacion_en_vivo TO anon;
GRANT ALL ON TABLE public.ubicacion_en_vivo TO authenticated;
GRANT ALL ON TABLE public.ubicacion_en_vivo TO service_role;


--
-- Name: TABLE vehiculos; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.vehiculos TO anon;
GRANT ALL ON TABLE public.vehiculos TO authenticated;
GRANT ALL ON TABLE public.vehiculos TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: supabase_admin
--

ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: supabase_admin
--

ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: supabase_admin
--

ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO service_role;


--
-- PostgreSQL database dump complete
--

\unrestrict WGmats9iGE1o09LhmPSdcSDlNCYWb9wYrTGgSU1stAh5hrPushP6yFEtq4qhUQz

