-- EP3: reglas de distribución, notificaciones a usuarios y registro de mensajes fallidos.

-- Reglas de enrutamiento por contenido: qué alertas van a qué destino de notificaciones.
CREATE TABLE regla_distribucion (
    id               BIGSERIAL    PRIMARY KEY,
    nombre           VARCHAR(60)  NOT NULL UNIQUE,
    destino_origen   VARCHAR(60)  NOT NULL,              -- clave del catálogo que se escucha (ej. alertas-precio)
    restriccion      VARCHAR(20),                        -- condición opcional: la oferta debe tener esta restricción
    descuento_minimo INTEGER      CHECK (descuento_minimo BETWEEN 0 AND 100), -- condición opcional (baja en %)
    tienda           VARCHAR(40),                        -- condición opcional: solo esa tienda
    topic_destino    VARCHAR(100) NOT NULL,              -- a dónde se reenvía si se cumple
    activa           BOOLEAN      NOT NULL DEFAULT TRUE,
    prioridad        INTEGER      NOT NULL DEFAULT 100,   -- menor = se evalúa antes
    creada_en        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizada_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_regla_activa ON regla_distribucion (destino_origen, activa, prioridad);

-- Restricciones que sigue cada usuario (sub de Cognito).
CREATE TABLE suscripcion (
    id          BIGSERIAL   PRIMARY KEY,
    usuario_sub VARCHAR(80) NOT NULL,
    restriccion VARCHAR(20) NOT NULL,
    creada_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_suscripcion UNIQUE (usuario_sub, restriccion)
);
CREATE INDEX idx_suscripcion_restriccion ON suscripcion (restriccion);

-- Notificaciones por usuario. La clave única (usuario, evento) hace idempotente el consumo.
CREATE TABLE notificacion (
    id          BIGSERIAL    PRIMARY KEY,
    usuario_sub VARCHAR(80)  NOT NULL,
    evento_id   VARCHAR(200) NOT NULL,
    regla       VARCHAR(60),
    titulo      VARCHAR(300) NOT NULL,
    detalle     VARCHAR(500),
    url         VARCHAR(500),
    leida       BOOLEAN      NOT NULL DEFAULT FALSE,
    creada_en   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_notificacion UNIQUE (usuario_sub, evento_id)
);
CREATE INDEX idx_notificacion_usuario ON notificacion (usuario_sub, creada_en DESC);

-- Traza de cada evento recibido por el consumidor de notificaciones (demuestra a qué consumidor llegó).
CREATE TABLE entrega_notificacion (
    evento_id     VARCHAR(200) PRIMARY KEY,
    topic         VARCHAR(100) NOT NULL,
    regla         VARCHAR(60),
    destinatarios INTEGER      NOT NULL,
    prueba        BOOLEAN      NOT NULL DEFAULT FALSE,
    recibida_en   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Mensajes apartados en la DLT, con lo necesario para diagnosticar y reprocesar.
CREATE TABLE mensaje_fallido (
    id               BIGSERIAL    PRIMARY KEY,
    topic_origen     VARCHAR(100),
    particion_origen INTEGER,
    offset_origen    BIGINT,
    grupo_origen     VARCHAR(100),
    clave            VARCHAR(200),
    contenido        TEXT,
    excepcion        VARCHAR(300),
    mensaje_error    TEXT,
    traza            TEXT,
    estado           VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'REPROCESADO', 'DESCARTADO')),
    recibido_en      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado_en   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_mensaje_fallido UNIQUE (topic_origen, particion_origen, offset_origen)
);
CREATE INDEX idx_mensaje_fallido_estado ON mensaje_fallido (estado, recibido_en DESC);

-- Reglas iniciales: una por restricción, hacia los destinos iniciales de la familia notificaciones.
INSERT INTO regla_distribucion (nombre, destino_origen, restriccion, descuento_minimo, topic_destino, prioridad) VALUES
    ('alertas-sin-gluten',  'alertas-precio', 'SIN_GLUTEN',  20, 'notificaciones.sin-gluten',  10),
    ('alertas-sin-lactosa', 'alertas-precio', 'SIN_LACTOSA', 20, 'notificaciones.sin-lactosa', 20),
    ('alertas-aplv',        'alertas-precio', 'APLV',        20, 'notificaciones.aplv',        30),
    ('alertas-vegano',      'alertas-precio', 'VEGANO',      20, 'notificaciones.vegano',      40);
