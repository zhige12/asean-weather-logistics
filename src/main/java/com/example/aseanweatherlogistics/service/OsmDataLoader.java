package com.example.aseanweatherlogistics.service;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.PointList;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OsmDataLoader {

    private static final Logger logger = LoggerFactory.getLogger(OsmDataLoader.class);

    @Value("${osm.pbf.path:D:/idea/vietnam-260811.osm.pbf}")
    private String osmPbfPath;

    @Value("${osm.graph.cache.path:D:/idea/graph-cache}")
    private String graphCachePath;

    @Value("${osm.loader.enabled:false}")
    private boolean osmLoaderEnabled;

    private GraphHopper hopper;
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osm-loader");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        if (osmLoaderEnabled) {
            startAsyncLoad();
        }
    }

    public synchronized void startAsyncLoad() {
        if (loaded.get() || loading.get()) {
            logger.info("OSM loader already loaded or loading");
            return;
        }
        loading.set(true);
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting async load of OSM PBF: {}", osmPbfPath);
                if (!Files.exists(Path.of(osmPbfPath))) {
                    logger.warn("OSM PBF file not found: {}", osmPbfPath);
                    loaded.set(false);
                    return;
                }

                GraphHopper graphHopper = new GraphHopper();
                graphHopper.setOSMFile(osmPbfPath);
                graphHopper.setGraphHopperLocation(graphCachePath);
                graphHopper.setEncodedValuesString("car_access,car_average_speed");
                graphHopper.setProfiles(new Profile("car").setWeighting("fastest"));
                graphHopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));
                graphHopper.importOrLoad();
                this.hopper = graphHopper;
                loaded.set(true);
                logger.info("OSM PBF loaded successfully");
            } catch (Throwable e) {
                logger.error("Failed to load OSM PBF", e);
                loaded.set(false);
            } finally {
                loading.set(false);
            }
        }, executor);
    }

    public synchronized void stopLoader() {
        try {
            if (hopper != null) {
                hopper.close();
                hopper = null;
            }
        } catch (Exception e) {
            logger.warn("Error closing GraphHopper instance", e);
        } finally {
            loaded.set(false);
            loading.set(false);
        }
    }

    @PreDestroy
    public void close() {
        stopLoader();
        executor.shutdownNow();
    }

    public Map<String, Object> getRoute(double fromLat, double fromLon, double toLat, double toLon) {
        ensureLoaded();
        GHRequest request = new GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile("car")
                .setLocale(Locale.US);

        GHResponse response = hopper.route(request);
        if (response.hasErrors()) {
            throw new IllegalStateException("Route query failed: " + response.getErrors());
        }

        PointList points = response.getBest().getPoints();
        List<Map<String, Double>> coordinates = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Map<String, Double> point = new LinkedHashMap<>();
            point.put("lat", points.getLat(i));
            point.put("lon", points.getLon(i));
            coordinates.add(point);
        }

        InstructionList instructions = response.getBest().getInstructions();
        List<String> instructionTexts = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            instructionTexts.add(instructions.get(i).getTurnDescription(instructions.getTr()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinates", coordinates);
        result.put("distance_km", response.getBest().getDistance() / 1000.0d);
        result.put("time_min", response.getBest().getTime() / 60000.0d);
        result.put("instructions", instructionTexts);
        return result;
    }

    private void ensureLoaded() {
        if (!loaded.get() || hopper == null) {
            throw new IllegalStateException("OSM network is not loaded yet");
        }
    }

    public boolean isLoaded() {
        return loaded.get();
    }

    public boolean isLoading() {
        return loading.get();
    }

    public boolean isOsmLoaderEnabled() {
        return osmLoaderEnabled;
    }
}
