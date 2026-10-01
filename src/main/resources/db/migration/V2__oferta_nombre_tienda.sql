-- Nombre visible de la tienda, enviado por el scraper (permite sumar tiendas sin cambiar la API)
ALTER TABLE oferta ADD COLUMN supermercado_nombre VARCHAR(80);
