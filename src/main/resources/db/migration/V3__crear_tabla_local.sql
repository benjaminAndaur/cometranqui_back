-- Restaurantes, cafés y tiendas con opciones para restricciones alimentarias (topic locales.scrapeados).
CREATE TABLE local (
    id             VARCHAR(80)      PRIMARY KEY,   -- ej. osm-node-123456
    fuente         VARCHAR(20)      NOT NULL,
    nombre         VARCHAR(200)     NOT NULL,
    tipo           VARCHAR(40),
    direccion      VARCHAR(200),
    comuna         VARCHAR(80),
    latitud        DOUBLE PRECISION NOT NULL,
    longitud       DOUBLE PRECISION NOT NULL,
    sitio_web      VARCHAR(300),
    telefono       VARCHAR(60),
    horario        VARCHAR(300),
    url_fuente     VARCHAR(300),
    scrapeado_en   TIMESTAMPTZ      NOT NULL,
    actualizado_en TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Tabla puente: nivel de seguridad por restricción (1 Exclusivo, 2 Opción Segura, 3 Opción Básica)
CREATE TABLE local_restriccion (
    local_id    VARCHAR(80) NOT NULL REFERENCES local (id) ON DELETE CASCADE,
    restriccion VARCHAR(20) NOT NULL,
    nivel       INTEGER     NOT NULL CHECK (nivel BETWEEN 1 AND 3),
    PRIMARY KEY (local_id, restriccion)
);

CREATE INDEX idx_local_restriccion ON local_restriccion (restriccion, nivel);
CREATE INDEX idx_local_comuna ON local (lower(comuna));
