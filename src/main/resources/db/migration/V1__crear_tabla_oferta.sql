-- Ofertas de productos aptos para restricciones alimentarias, alimentadas por el scraper vía Kafka.
CREATE TABLE oferta (
    id                   BIGSERIAL PRIMARY KEY,
    supermercado         VARCHAR(40)  NOT NULL,
    sku                  VARCHAR(64)  NOT NULL,
    nombre               VARCHAR(300) NOT NULL,
    marca                VARCHAR(120),
    categoria            VARCHAR(120),
    -- Códigos de Restriccion: SIN_GLUTEN, SIN_LACTOSA, APLV, VEGANO
    restricciones        TEXT[]       NOT NULL,
    precio_normal        INTEGER      NOT NULL CHECK (precio_normal > 0),
    precio_oferta        INTEGER      NOT NULL CHECK (precio_oferta > 0),
    porcentaje_descuento INTEGER      NOT NULL CHECK (porcentaje_descuento BETWEEN 0 AND 100),
    url_producto         VARCHAR(500) NOT NULL,
    url_imagen           VARCHAR(500),
    vigente_hasta        DATE,
    scrapeado_en         TIMESTAMPTZ  NOT NULL,
    actualizado_en       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_oferta_supermercado_sku UNIQUE (supermercado, sku)
);

CREATE INDEX idx_oferta_restricciones ON oferta USING GIN (restricciones);
CREATE INDEX idx_oferta_supermercado ON oferta (supermercado);
CREATE INDEX idx_oferta_scrapeado_en ON oferta (scrapeado_en);
