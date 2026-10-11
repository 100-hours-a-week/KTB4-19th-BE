CREATE TABLE Building_locations (
    building_id BIGINT NOT NULL,
    location POINT NOT NULL SRID 4326,
    PRIMARY KEY (building_id),
    CONSTRAINT fk_building_locations_building FOREIGN KEY (building_id) REFERENCES Buildings(building_id),
    SPATIAL INDEX idx_building_locations_location (location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
