package de.cesr.crafty.core.dataLoader.afts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.cesr.crafty.core.ToyData;
import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.cli.CustomLogger;
import de.cesr.crafty.core.crafty.Aft;
import de.cesr.crafty.core.crafty.ManagerTypes;
import de.cesr.crafty.core.dataLoader.ProjectLoader;

class AFTsLoaderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Prepare fake project + metadata in a fresh temp directory
        ToyData toyData = new ToyData();
        toyData.resetStaticState(tempDir);
    }
    
    @AfterEach
    void tearDown() {
    	CustomLogger.shutdownRunFileLoggers();
    }

    @Test
    void constructor_initializesAftsFromMetaData_andAddsAbandoned() {

        Map<String, Aft> afts = AFTsLoader.getAftHash();

        // 3 from ToyData + 1 auto-added Abandoned = 4
        assertEquals(4, afts.size(), "Expected 3 metadata AFTs + auto 'Abandoned' AFT");

        // All labels from ToyData
        assertTrue(afts.containsKey("AFT1"));
        assertTrue(afts.containsKey("AFT2"));
        assertTrue(afts.containsKey("AFT3"));

        // Auto-added abandoned manager is always present
        assertTrue(afts.containsKey("Abandoned"),
                "Abandoned AFT should always be present");

        // Check some basic properties from CSV
        Aft aft1 = afts.get("AFT1");
        assertEquals(ManagerTypes.AFT, aft1.getType());

        Aft aft3 = afts.get("AFT3");
        assertEquals(ManagerTypes.AFT, aft3.getType());

        Aft abandoned = afts.get("Abandoned");
        assertEquals(ManagerTypes.Abandoned, abandoned.getType());
    }

    @Test
    void constructor_populatesActiveAftsHash() {
        // Act
        Map<String, Aft> active = AFTsLoader.getActivateAFTsHash();

        // Should not be empty, because default AFTs are interactive/active
        assertFalse(active.isEmpty(), "Active AFTs map should not be empty");
        assertTrue(active.keySet().contains("AFT1") ||
                   active.keySet().contains("AFT2") ||
                   active.keySet().contains("AFT3"),
                   "At least the normal AFTs should be active");
    }

    @Test
    void deterministicRandomAft_isIndependentOfCollectionOrder() {
        List<Aft> forward = List.of(new Aft("A"), new Aft("B"), new Aft("C"));
        List<Aft> reverse = List.of(forward.get(2), forward.get(1), forward.get(0));

        Aft first = AFTsLoader.getDeterministicRandomAFT(forward, 123L, 2030, 77L, 1L, 0);
        Aft second = AFTsLoader.getDeterministicRandomAFT(reverse, 123L, 2030, 77L, 1L, 0);

        assertNotNull(first);
        assertEquals(first.getLabel(), second.getLabel());
    }

    @Test
    void deterministicRandomAft_fromEmptyCollection_returnsNull() {
        Aft random = AFTsLoader.getDeterministicRandomAFT(new ArrayList<>(), 123L, 2030, 77L, 1L, 0);

        assertNull(random, "Random AFT from an empty collection should be null");
    }

    // -----------------------------
    // self_consumption (use_self_consumption)
    // -----------------------------

    @Test
    void parseSelfConsumption_readsBlankAsZero_andOnlyAcceptsZeroToOne() {
        assertEquals(0.0, AFTsLoader.parseSelfConsumption(""), 0.0);
        assertEquals(0.0, AFTsLoader.parseSelfConsumption("  "), 0.0);
        assertEquals(0.0, AFTsLoader.parseSelfConsumption("NaN"), 0.0);
        assertEquals(0.0, AFTsLoader.parseSelfConsumption(null), 0.0);
        assertEquals(0.0, AFTsLoader.parseSelfConsumption("0"), 0.0);
        assertEquals(0.5, AFTsLoader.parseSelfConsumption(" 0.5 "), 0.0);
        assertEquals(1.0, AFTsLoader.parseSelfConsumption("1.0"), 0.0);

        assertTrue(Double.isNaN(AFTsLoader.parseSelfConsumption("1.5")));
        assertTrue(Double.isNaN(AFTsLoader.parseSelfConsumption("-0.1")));
        assertTrue(Double.isNaN(AFTsLoader.parseSelfConsumption("abc")));
    }

    @Test
    void selfConsumptionSummary_listsTheMarketGoods_andTheSelfConsumingAftsInLabelOrder() {
        Aft swidden = new Aft("Swidden");
        swidden.setSelfConsumption(0.5);
        Aft logger = new Aft("Logger");
        logger.setSelfConsumption(1.0);
        Aft agri = new Aft("Agri");

        assertEquals("self consumption is on for market goods [Crops, Wood]: Logger 1.0, Swidden 0.5",
                AFTsLoader.selfConsumptionSummary(List.of(swidden, agri, logger), List.of("Crops", "Wood")));
        assertEquals("self consumption is on for market goods [Crops]: no AFT has self_consumption above 0",
                AFTsLoader.selfConsumptionSummary(List.of(agri), List.of("Crops")));
    }

    @Test
    void initializeAftList_readsSelfConsumption_onlyWhenTheSwitchIsOn() throws Exception {
        Path metaData = tempDir.resolve("AFTsMetaData_self_consumption.csv");
        Files.write(metaData, List.of("Label,Type,Color,self_consumption", "Swidden,AFT,#cc6300,0.5",
                "Agri,AFT,#cc6300,0", "Urban,Mask,#ff0000,"));
        Field path = ProjectLoader.class.getDeclaredField("aftMetaData");
        path.setAccessible(true);
        Object pathBackup = path.get(null);
        boolean switchBackup = ConfigLoader.config.use_self_consumption;
        try {
            path.set(null, metaData);

            ConfigLoader.config.use_self_consumption = true;
            initializeAftList();
            assertEquals(0.5, AFTsLoader.getAftHash().get("Swidden").getSelfConsumption(), 0.0);
            assertEquals(0.0, AFTsLoader.getAftHash().get("Agri").getSelfConsumption(), 0.0);
            assertEquals(0.0, AFTsLoader.getAftHash().get("Urban").getSelfConsumption(), 0.0, "blank is 0");

            // Off: the column is not read at all.
            ConfigLoader.config.use_self_consumption = false;
            initializeAftList();
            assertEquals(0.0, AFTsLoader.getAftHash().get("Swidden").getSelfConsumption(), 0.0);
        } finally {
            path.set(null, pathBackup);
            ConfigLoader.config.use_self_consumption = switchBackup;
        }
    }

    /**
     * Runs AFTsLoader's private initializeAftList() on its own, without the rest of the constructor,
     * which needs a whole project. It reads and writes only static state, so a mock instance will do.
     */
    private static void initializeAftList() throws Exception {
        Method m = AFTsLoader.class.getDeclaredMethod("initializeAftList");
        m.setAccessible(true);
        m.invoke(mock(AFTsLoader.class));
    }
}
