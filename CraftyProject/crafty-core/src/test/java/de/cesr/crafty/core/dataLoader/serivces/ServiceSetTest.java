package de.cesr.crafty.core.dataLoader.serivces;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.cli.Config;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.dataLoader.ProjectLoader;

public class ServiceSetTest {

    @TempDir
    Path tempDir;

    private Object servicesListBackup;
    private Object serviceMetadataBackup;
    private Map<String, Boolean> penaliseOversupplyBackup;
    private Map<String, Boolean> marketGoodBackup;
    private Config configBackup;
    private boolean selfConsumptionBackup;

    @BeforeEach
    void backupStatics() throws Exception {
        // loadServiceList() replaces the services list, so the list object itself is put back after.
        servicesListBackup = staticField(ServiceSet.class, "servicesList").get(null);
        serviceMetadataBackup = staticField(ProjectLoader.class, "serviceMetadata").get(null);
        penaliseOversupplyBackup = new HashMap<>(ServiceSet.getPenalise_Oversupply());
        marketGoodBackup = new HashMap<>(ServiceSet.getMarketGood());
        configBackup = ConfigLoader.config;
        if (ConfigLoader.config == null) {
            ConfigLoader.config = new Config();
        }
        selfConsumptionBackup = ConfigLoader.config.use_self_consumption;
    }

    @AfterEach
    void restoreStatics() throws Exception {
        ConfigLoader.config.use_self_consumption = selfConsumptionBackup;
        ConfigLoader.config = configBackup;
        staticField(ServiceSet.class, "servicesList").set(null, servicesListBackup);
        staticField(ProjectLoader.class, "serviceMetadata").set(null, serviceMetadataBackup);
        ServiceSet.getPenalise_Oversupply().clear();
        ServiceSet.getPenalise_Oversupply().putAll(penaliseOversupplyBackup);
        ServiceSet.getMarketGood().clear();
        ServiceSet.getMarketGood().putAll(marketGoodBackup);
        CustomLogger.shutdownRunFileLoggers();
    }

    // -----------------------------
    // Market_good
    // -----------------------------

    @Test
    void parseMarketGood_acceptsYesOrNoInAnyCase_andNothingElse() {
        assertEquals(Boolean.TRUE, ServiceSet.parseMarketGood("Yes"));
        assertEquals(Boolean.TRUE, ServiceSet.parseMarketGood("yes"));
        assertEquals(Boolean.TRUE, ServiceSet.parseMarketGood(" YES "));
        assertEquals(Boolean.FALSE, ServiceSet.parseMarketGood("No"));
        assertEquals(Boolean.FALSE, ServiceSet.parseMarketGood("no"));

        assertNull(ServiceSet.parseMarketGood(""));
        assertNull(ServiceSet.parseMarketGood(null));
        assertNull(ServiceSet.parseMarketGood("Y"));
        assertNull(ServiceSet.parseMarketGood("1"));
        assertNull(ServiceSet.parseMarketGood("Maybe"));
    }

    @Test
    void loadServiceList_readsMarketGood_whenSelfConsumptionIsOn() throws Exception {
        ConfigLoader.config.use_self_consumption = true;
        load("Name,Penalise_Oversupply,Market_good", "Crops,Yes,Yes", "Pasture,No,yes", "Carbon,No,No");

        assertEquals(List.of("Crops", "Pasture", "Carbon"), ServiceSet.getServicesList());
        assertTrue(ServiceSet.isMarketGood("Crops"));
        assertTrue(ServiceSet.isMarketGood("Pasture"));
        assertFalse(ServiceSet.isMarketGood("Carbon"));
        assertFalse(ServiceSet.isMarketGood("NotAService"));
    }

    @Test
    void loadServiceList_withoutTheColumn_hasNoMarketGoods_whenSelfConsumptionIsOff() throws Exception {
        ConfigLoader.config.use_self_consumption = false;
        load("Name,Penalise_Oversupply", "Crops,Yes", "Carbon,No");

        assertTrue(ServiceSet.getMarketGood().isEmpty());
        assertFalse(ServiceSet.isMarketGood("Crops"));
    }

    @Test
    void loadServiceList_keepsNoMarketGoodFlagsFromAnEarlierFile() throws Exception {
        load("Name,Market_good", "Crops,Yes", "Carbon,No");
        assertTrue(ServiceSet.isMarketGood("Crops"));

        load("Name,Market_good", "Wood,Yes", "Carbon,No");
        assertFalse(ServiceSet.isMarketGood("Crops"), "Crops is not in the second file");
        assertEquals(Map.of("Wood", true, "Carbon", false), ServiceSet.getMarketGood());
    }

    // =========================================================
    // Helpers
    // =========================================================

    /** Writes a Services.csv with the given lines and loads it. */
    private void load(String... lines) throws Exception {
        Path file = Files.createTempFile(tempDir, "Services", ".csv");
        Files.write(file, List.of(lines));
        staticField(ProjectLoader.class, "serviceMetadata").set(null, file);
        ServiceSet.loadServiceList();
    }

    private static Field staticField(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
