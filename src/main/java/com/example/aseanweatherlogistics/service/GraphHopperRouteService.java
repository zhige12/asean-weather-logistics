package com.example.aseanweatherlogistics.service;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GraphHopperRouteService {
    private final OsmDataLoader osmDataLoader;

    public GraphHopperRouteService(OsmDataLoader osmDataLoader) {
        this.osmDataLoader = osmDataLoader;
    }

    public Map<String, Object> getRoute(double fromLat, double fromLon, double toLat, double toLon) {
        if (!osmDataLoader.isOsmLoaderEnabled()) {
            return Map.of("error", "OSM loader disabled");
        }
        if (!osmDataLoader.isLoaded()) {
            return Map.of("error", "OSM data not loaded yet");
        }
        try {
            return osmDataLoader.getRoute(fromLat, fromLon, toLat, toLon);
        } catch (Exception e) {
            return Map.of("error", "route failed: " + e.getMessage());
        }
    }
}
