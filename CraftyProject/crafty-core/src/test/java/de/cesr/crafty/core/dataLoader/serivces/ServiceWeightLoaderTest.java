package de.cesr.crafty.core.dataLoader.serivces;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.crafty.Region;
import de.cesr.crafty.core.crafty.Service;
import de.cesr.crafty.core.dataLoader.land.CellsLoader;
import de.cesr.crafty.core.updaters.Timestep;

/** Which regions get the default weights (1 for every service), when weights are read per region from a folder. */
public class ServiceWeightLoaderTest {

    @TempDir
    Path tempDir;

    private Config configBefore;
    private ConcurrentHashMap<String, Region> regionsBefore;
    private List<String> servicesBefore;
    private int startBefore;
    private int endBefore;
    private int sizeBefore;

    @BeforeEach
    void setUp() {
        configBefore = ConfigLoader.config;
        regionsBefore = CellsLoader.regions;
        servicesBefore = new ArrayList<>(ServiceSet.getServicesList());
        startBefore = Timestep.getStartYear();
        endBefore = Timestep.getEndtYear();
        sizeBefore = Timestep.getSize();

        ConfigLoader.config = new Config();
        ServiceSet.getServicesList().clear();
        ServiceSet.getServicesList().addAll(List.of("S1", "S2"));
        Timestep.setStartYear(1990);
        Timestep.setEndtYear(1991);
        Timestep.setSize(2);
        CellsLoader.regions = new ConcurrentHashMap<>();
        for (String name : List.of("Alpha", "Beta", "Gamma")) {
            Region r = new Region(name);
            r.getServicesHash().put("S1", new Service("S1"));
            r.getServicesHash().put("S2", new Service("S2"));
            CellsLoader.regions.put(name, r);
        }
    }

    @AfterEach
    void tearDown() {
        ConfigLoader.config = configBefore;
        CellsLoader.regions = regionsBefore;
        ServiceSet.getServicesList().clear();
        ServiceSet.getServicesList().addAll(servicesBefore);
        Timestep.setStartYear(startBefore);
        Timestep.setEndtYear(endBefore);
        Timestep.setSize(sizeBefore);
    }

    @Test
    void aRegionWithNoFileOfItsOwnIsRecordedAndGetsWeight1() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("weights"));
        Files.writeString(folder.resolve("weights_Alpha.csv"), "S1,S2\n10,20\n11,21\n");
        Files.writeString(folder.resolve("weights_Gamma.csv"), "S1,S2\n30,40\n31,41\n");
        ConfigLoader.config.service_utility_weights_path = folder.toString();

        ServiceWeightLoader.updateRegionsWeight();

        assertEquals(Set.of("Beta"), ServiceWeightLoader.getRegionsWithDefaultWeights());
        assertEquals(11.0, CellsLoader.regions.get("Alpha").getServicesHash().get("S1").getWeights().get(1991));
        assertEquals(41.0, CellsLoader.regions.get("Gamma").getServicesHash().get("S2").getWeights().get(1991));
        assertEquals(1.0, CellsLoader.regions.get("Beta").getServicesHash().get("S2").getWeights().get(1990),
                "the default weight");
    }

    @Test
    void theRecordIsRemadeAtEachLoad() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("weights"));
        Files.writeString(folder.resolve("weights_Alpha.csv"), "S1,S2\n10,20\n11,21\n");
        ConfigLoader.config.service_utility_weights_path = folder.toString();
        ServiceWeightLoader.updateRegionsWeight();
        assertEquals(Set.of("Beta", "Gamma"), ServiceWeightLoader.getRegionsWithDefaultWeights());

        Files.writeString(folder.resolve("weights_Beta.csv"), "S1,S2\n1,2\n1,2\n");
        Files.writeString(folder.resolve("weights_Gamma.csv"), "S1,S2\n1,2\n1,2\n");
        ServiceWeightLoader.updateRegionsWeight();

        assertEquals(Set.of(), ServiceWeightLoader.getRegionsWithDefaultWeights());
    }

    @Test
    void oneFileForEveryRegionGivesNoDefaults() throws Exception {
        Path file = tempDir.resolve("weights_global.csv");
        Files.writeString(file, "S1,S2\n10,20\n11,21\n");
        ConfigLoader.config.service_utility_weights_path = file.toString();

        ServiceWeightLoader.updateRegionsWeight();

        assertEquals(Set.of(), ServiceWeightLoader.getRegionsWithDefaultWeights());
        assertEquals(10.0, CellsLoader.regions.get("Beta").getServicesHash().get("S1").getWeights().get(1990));
    }
}
