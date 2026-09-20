package com.example.aseanweatherlogistics.repository;

import com.example.aseanweatherlogistics.model.entity.RoadEdge;
import com.example.aseanweatherlogistics.model.entity.RoadNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

@Repository
public class RouteRepository {
    private static final String NETWORK_FILE = "data/vietnam-road-network.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoadNetworkData loadRoadNetwork() {
        ClassPathResource resource = new ClassPathResource(NETWORK_FILE);
        try (InputStream inputStream = resource.getInputStream()) {
            RoadNetworkData data = objectMapper.readValue(inputStream, RoadNetworkData.class);
            if (data.getNodes() == null) {
                data.setNodes(Collections.emptyList());
            }
            if (data.getEdges() == null) {
                data.setEdges(Collections.emptyList());
            }
            return data;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load road network data from " + NETWORK_FILE, e);
        }
    }

    @Getter
    @Setter
    public static class RoadNetworkData {
        @JsonProperty("nodes")
        private List<RoadNode> nodes;
        @JsonProperty("edges")
        private List<RoadEdge> edges;
    }
}
