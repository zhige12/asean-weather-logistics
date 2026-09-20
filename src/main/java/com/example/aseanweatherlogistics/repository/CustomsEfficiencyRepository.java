package com.example.aseanweatherlogistics.repository;

import com.example.aseanweatherlogistics.model.entity.CustomsEfficiencyEntry;
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
public class CustomsEfficiencyRepository {
    private static final String FILE = "data/customs-efficiency.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<CustomsEfficiencyEntry> loadAll() {
        ClassPathResource resource = new ClassPathResource(FILE);
        try (InputStream inputStream = resource.getInputStream()) {
            CustomsEfficiencyData data = objectMapper.readValue(inputStream, CustomsEfficiencyData.class);
            if (data.getPorts() == null) {
                return Collections.emptyList();
            }
            return data.getPorts();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load customs efficiency data from " + FILE, e);
        }
    }

    @Getter
    @Setter
    public static class CustomsEfficiencyData {
        @JsonProperty("ports")
        private List<CustomsEfficiencyEntry> ports;
    }
}
