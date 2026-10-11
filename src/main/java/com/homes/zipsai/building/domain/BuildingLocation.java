package com.homes.zipsai.building.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.Point;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Building_locations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildingLocation {

    @Id
    @Column(name = "building_id")
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false, updatable = false)
    private Building building;

    @Column(name = "location", nullable = false)
    private Point location;

    public BuildingLocation(Building building, Point location) {
        this.building = Objects.requireNonNull(building, "building");
        Objects.requireNonNull(location, "location");
        if (location.isEmpty() || location.getSRID() != 4326) {
            throw new IllegalArgumentException("location must be a non-empty WGS 84 point");
        }
        double longitude = location.getX();
        double latitude = location.getY();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude <= -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("location coordinates are out of range");
        }
        this.location = location;
    }
}
