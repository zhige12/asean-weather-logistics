package com.example.aseanweatherlogistics.repository;

import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
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
public class KnowledgeBaseRepository {
    private static final String FILE = "data/knowledge-base.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<KnowledgeEntry> loadAll() {
        ClassPathResource resource = new ClassPathResource(FILE);
        try (InputStream inputStream = resource.getInputStream()) {
            KnowledgeBaseData data = objectMapper.readValue(inputStream, KnowledgeBaseData.class);
            if (data.getEntries() == null) {
                return Collections.emptyList();
            }
            return data.getEntries();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load knowledge base data from " + FILE, e);
        }
    }

    @Getter
    @Setter
    public static class KnowledgeBaseData {
        @JsonProperty("entries")
        private List<KnowledgeEntry> entries;
    }
}
