CREATE TABLE Building_coordinate_retry_jobs (
    building_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_failure_count SMALLINT NOT NULL DEFAULT 0,
    claim_version BIGINT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    lease_expires_at DATETIME(6) NULL,
    last_error_type VARCHAR(30) NOT NULL,
    failure_reason VARCHAR(30) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (building_id),
    CONSTRAINT fk_coordinate_retry_jobs_building FOREIGN KEY (building_id) REFERENCES Buildings(building_id),
    CONSTRAINT chk_coordinate_retry_jobs_count CHECK (retry_failure_count BETWEEN 0 AND 3),
    CONSTRAINT chk_coordinate_retry_jobs_version CHECK (claim_version >= 0),
    CONSTRAINT chk_coordinate_retry_jobs_state CHECK (
        (CAST(status AS BINARY) = 'PENDING' AND next_retry_at IS NOT NULL
            AND lease_expires_at IS NULL AND failure_reason IS NULL)
        OR (CAST(status AS BINARY) = 'PROCESSING' AND next_retry_at IS NULL
            AND lease_expires_at IS NOT NULL AND failure_reason IS NULL)
        OR (CAST(status AS BINARY) = 'COMPLETED' AND next_retry_at IS NULL
            AND lease_expires_at IS NULL AND failure_reason IS NULL)
        OR (CAST(status AS BINARY) = 'FAILED' AND next_retry_at IS NULL
            AND lease_expires_at IS NULL AND failure_reason IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
