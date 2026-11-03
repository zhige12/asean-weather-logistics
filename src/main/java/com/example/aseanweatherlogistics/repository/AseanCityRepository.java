package com.example.aseanweatherlogistics.repository;

import com.example.aseanweatherlogistics.model.entity.AseanCity;
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

/** 东盟态势城市表加载器（包内静态数据，随源码分发，编译即可复现）。 */
@Repository
public class AseanCityRepository {
    private static final String FILE = "data/asean-cities.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<AseanCity> loadAll() {
        ClassPathResource resource = new ClassPathResource(FILE);
        try (InputStream inputStream = resource.getInputStream()) {
            AseanCityData data = objectMapper.readValue(inputStream, AseanCityData.class);
            if (data.getCities() == null) {
                return Collections.emptyList();
            }
            return data.getCities();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load ASEAN cities data from " + FILE, e);
        }
    }

    @Getter
    @Setter
    public static class AseanCityData {
        @JsonProperty("cities")
        private List<AseanCity> cities;
    }
}
