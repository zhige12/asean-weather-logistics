package com.example.aseanweatherlogistics.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路网装载入口用例（第二走廊可行性的落地点）。
 * <p>
 * 「同一引擎换数据即换国家」这句主张要能被核，前提是路网文件本身是可换的：
 * 改造前它是写死在 {@code static final} 里的一个 classpath 路径，换走廊等于改代码。
 * 这里锁三件事：默认值仍是仓库里那条走廊、换一份文件（含仓库外的生成产物）确实能加载、
 * 路径写错时报错要指到当前生效的位置。
 */
class RouteRepositoryTest {

    private static final String MINIMAL_NETWORK =
            "{\"nodes\":[{\"id\":\"A\",\"name\":\"甲\",\"latitude\":10.0,\"longitude\":100.0},"
                    + "{\"id\":\"B\",\"name\":\"乙\",\"latitude\":11.0,\"longitude\":101.0}],"
                    + "\"edges\":[{\"id\":\"E1\",\"fromNodeId\":\"A\",\"toNodeId\":\"B\",\"distanceKm\":120,"
                    + "\"speedKmh\":100,\"customs\":false,\"name\":\"测试边\",\"roadType\":\"motorway\"}]}";

    @Test
    void defaultLocation_stillLoadsShippedCorridor() {
        // 默认值必须与改造前指向同一个文件，否则「换走廊靠配置」会把现有演示拖崩
        RouteRepository repo = new RouteRepository("data/vietnam-road-network.json");
        RouteRepository.RoadNetworkData data = repo.loadRoadNetwork();
        assertTrue(data.getNodes().size() > 1000, "默认走廊应是完整路网，实际节点 " + data.getNodes().size());
        assertTrue(data.getEdges().stream().anyMatch(e -> "E4".equals(e.getId())),
                "默认走廊必须含友谊关口岸虚拟边（跨境绕行剧本依赖它）");
        assertInstanceOf(ClassPathResource.class, repo.resolveNetworkResource(), "默认写法走 classpath");
    }

    @Test
    void filePrefix_loadsNetworkOutsideClasspath(@TempDir Path dir) throws IOException {
        // 第二走廊由脚本生成在 tools/data/ 下：不该为了跑一次验证就把 30MB 路网塞进仓库
        Path generated = dir.resolve("second-corridor-road-network.json");
        Files.writeString(generated, MINIMAL_NETWORK, StandardCharsets.UTF_8);

        RouteRepository repo = new RouteRepository("file:" + generated.toAbsolutePath());
        assertInstanceOf(FileSystemResource.class, repo.resolveNetworkResource());
        RouteRepository.RoadNetworkData data = repo.loadRoadNetwork();
        assertEquals(2, data.getNodes().size(), "换进来的走廊应被原样加载");
        assertEquals("测试边", data.getEdges().get(0).getName());
    }

    @Test
    void bareAbsolutePath_isReadFromFileSystem(@TempDir Path dir) throws IOException {
        // 不带 file: 前缀的绝对路径也认：Windows 的 X:\ 与 *nix 的 / 两种写法都覆盖
        Path generated = dir.resolve("abs-corridor.json");
        Files.writeString(generated, MINIMAL_NETWORK, StandardCharsets.UTF_8);

        RouteRepository repo = new RouteRepository(generated.toAbsolutePath().toString());
        assertEquals(1, repo.loadRoadNetwork().getEdges().size());
    }

    @Test
    void missingFile_errorNamesTheEffectiveLocation() {
        RouteRepository repo = new RouteRepository("data/does-not-exist-corridor.json");
        IllegalStateException ex = assertThrows(IllegalStateException.class, repo::loadRoadNetwork);
        // 换走廊最常见的毛病是路径写错而不是数据坏，报错必须把当前生效的位置说出来
        assertTrue(ex.getMessage().contains("data/does-not-exist-corridor.json"),
                "报错应包含当前配置的路径，实际：" + ex.getMessage());
    }
}
