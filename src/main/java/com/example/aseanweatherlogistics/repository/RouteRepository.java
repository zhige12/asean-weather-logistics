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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;

@Repository
public class RouteRepository {
    /**
     * 路网文件位置。默认是已入库的中国广西—越北走廊；指到另一份走廊产物就换一个运行区域。
     * 这一项存在的前提是「算路引擎与走廊无关」已被验证（见 tools/corridors/）：
     * 同一个引擎用广西 extract 单独跑出的第二走廊路网，必须能在不改代码的情况下被加载。
     * 写法两种都支持：classpath 相对路径（默认），或 file: / 绝对路径读仓库外的生成产物。
     */
    private final String networkFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouteRepository(@Value("${route.network-file:data/vietnam-road-network.json}") String networkFile) {
        this.networkFile = networkFile;
    }

    /** classpath 与文件系统两种取法：第二走廊由脚本生成在 tools/data/ 下，不该为了跑一次验证而入库。 */
    Resource resolveNetworkResource() {
        String loc = networkFile.trim();
        if (loc.startsWith("file:") || loc.startsWith("/") || (loc.length() > 2 && loc.charAt(1) == ':')) {
            return new FileSystemResource(loc.startsWith("file:") ? loc.substring(5) : loc);
        }
        return new ClassPathResource(loc);
    }

    public RoadNetworkData loadRoadNetwork() {
        Resource resource = resolveNetworkResource();
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
            // 报错里必须带当前生效的路网位置：换走廊时最常见的毛病是路径写错而不是数据坏
            throw new IllegalStateException("Failed to load road network data from " + networkFile, e);
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
