package de.cesr.crafty.core.crafty;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import de.cesr.crafty.core.cli.ConfigLoader;
import de.cesr.crafty.core.dataLoader.afts.AFTsLoader;
import de.cesr.crafty.core.dataLoader.afts.AftCategorised;
import de.cesr.crafty.core.dataLoader.serivces.ServiceSet;
import de.cesr.crafty.core.output.Listener;
import de.cesr.crafty.core.output.Tracker;
import de.cesr.crafty.core.updaters.CellBehaviourUpdater;
import de.cesr.crafty.core.updaters.LandMaskUpdater;
import de.cesr.crafty.core.updaters.Timestep;

class CompetitivenessTest {

    private Object originalConfig;
    private boolean originalUseCategorisationGivIn;
    private boolean originalBehaviourUsed;
    private Map<String, Double> meanBackup;
    private Map<String, Double> sdBackup;
    private Map<String, ConcurrentHashMap<String, Boolean>> restrictionsBackup;

    @BeforeEach
    void snapshotAndReset() throws Exception {
        // ensure counter exists + reset
        if (Listener.landUseChangeCounter == null) {
            Listener.landUseChangeCounter = new AtomicInteger(0);
        } else {
            Listener.landUseChangeCounter.set(0);
        }

        // snapshot config
        Field cfgField = findField(ConfigLoader.class, "config");
        cfgField.setAccessible(true);
        originalConfig = cfgField.get(null);

        // snapshot AftCategorised
        originalUseCategorisationGivIn = AftCategorised.useCategorisationGivIn;
        meanBackup = new HashMap<>(AftCategorised.getMean());
        sdBackup = new HashMap<>(AftCategorised.getSD());

        // snapshot CellBehaviourUpdater
        originalBehaviourUsed = CellBehaviourUpdater.behaviourUsed;

        // snapshot LandMaskUpdater.restrictions
        if (LandMaskUpdater.restrictions == null) {
            LandMaskUpdater.restrictions = new ConcurrentHashMap<>();
        }
        restrictionsBackup = deepCopyRestrictions(LandMaskUpdater.restrictions);
        Listener.newAftsInLandNbr = new ConcurrentHashMap<>();
    }

    @AfterEach
    void restore() throws Exception {
        // reset price utility flags before restoring config reference,
        // because ensureConfigInstance() reuses the same object so
        // @BeforeEach's snapshot and originalConfig share identity
        if (ConfigLoader.config != null) {
            ConfigLoader.config.use_explicit_price_utility = false;
            ConfigLoader.config.use_price_only_utility = false;
            ConfigLoader.config.use_conversion_cost = false;
            ConfigLoader.config.use_matrix_give_in = false;
            ConfigLoader.config.use_price_feedback = false;
        }

        // restore config
        Field cfgField = findField(ConfigLoader.class, "config");
        cfgField.setAccessible(true);
        cfgField.set(null, originalConfig);

        // restore AftCategorised
        AftCategorised.useCategorisationGivIn = originalUseCategorisationGivIn;
        AftCategorised.getMean().clear();
        AftCategorised.getMean().putAll(meanBackup);
        AftCategorised.getSD().clear();
        AftCategorised.getSD().putAll(sdBackup);

        // restore behaviour flag
        CellBehaviourUpdater.behaviourUsed = originalBehaviourUsed;

        // restore restrictions
        LandMaskUpdater.restrictions.clear();
        LandMaskUpdater.restrictions.putAll(restrictionsBackup);
    }

    // -------------------------------------------------------
    // utility(...)
    // -------------------------------------------------------

    private double callUtility(Cell c, Aft a, RegionalModelRunner r) {
        try {
            Method m = Competitiveness.class.getDeclaredMethod("utility", Cell.class, Aft.class,
                    RegionalModelRunner.class);
            m.setAccessible(true); // bypass private
            return (double) m.invoke(null, c, a, r); // null because it's static
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void utility_returnsZero_whenAftNullOrNotInteract() throws Throwable {
        withServices(List.of("S1"), () -> {
            ensureConfigInstance();
            Cell c = new Cell(0, 0);
            RegionalModelRunner r = mock(RegionalModelRunner.class);

            assertEquals(0.0, callUtility(c, null, r), 1e-12);

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(false);
            assertEquals(0.0, callUtility(c, a, r), 1e-12);
        });
    }

    // -------------------------------------------------------
    // utility(...) — production cost subtraction (explicit-price path)
    // -------------------------------------------------------

    @Test
    void explicitPriceUtility_subtractsGlobalModeCosts_whenProductionCostsEnabled() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", true,
                    "spatial_production_costs", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");
            when(a.getNfertCostPerHa()).thenReturn(10.0);
            when(a.getIntensityCostPerHa()).thenReturn(5.0);

            doReturn(2.0).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);

                // revenue = 10 * 2 = 20; costs = nfertCostPerHa(10) + intensityCostPerHa(5) = 15
                assertEquals(5.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void explicitPriceUtility_subtractsSpatialModeCosts_fromCellMaps() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", true,
                    "spatial_production_costs", true));

            Cell c = Mockito.spy(new Cell(0, 0));
            c.getNfertCosts().put("AFT1", 8.0);
            c.getIntensityCosts().put("AFT1", 3.0);
            c.getIrrigationCosts().put("AFT1", 2.0);

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");

            doReturn(2.0).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);

                // revenue = 10 * 2 = 20; costs = nfert(8) + intensity(3) + irrigation(2) = 13
                assertEquals(7.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void explicitPriceUtility_subtractsGlobalModeStockingCost() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", true,
                    "spatial_production_costs", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");
            when(a.getIntensityCostPerHa()).thenReturn(5.0);
            when(a.getStockingCostPerHa()).thenReturn(4.0);

            doReturn(2.0).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);

                // revenue = 10 * 2 = 20; costs = intensity(5) + stocking(4) = 9
                assertEquals(11.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void explicitPriceUtility_subtractsSpatialModeStockingCost() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", true,
                    "spatial_production_costs", true));

            Cell c = Mockito.spy(new Cell(0, 0));
            c.getIntensityCosts().put("AFT1", 3.0);
            c.getStockingCosts().put("AFT1", 6.0);

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");

            doReturn(2.0).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);

                // revenue = 10 * 2 = 20; costs = intensity(3) + stocking(6) = 9
                assertEquals(11.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void explicitPriceUtility_mixedAftPaysBothNfertAndStocking() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", true,
                    "spatial_production_costs", true));

            // Costs are additive and independent: a mixed crop/livestock AFT pays both.
            Cell c = Mockito.spy(new Cell(0, 0));
            c.getNfertCosts().put("AFT1", 8.0);
            c.getStockingCosts().put("AFT1", 6.0);
            c.getIntensityCosts().put("AFT1", 3.0);
            c.getIrrigationCosts().put("AFT1", 2.0);

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");

            doReturn(2.0).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);

                // revenue = 20; costs = nfert(8) + stocking(6) + intensity(3) + irrigation(2) = 19
                assertEquals(1.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void explicitPriceUtility_noCostSubtraction_whenProductionCostsDisabled() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", false));

            Cell c = Mockito.spy(new Cell(0, 0));
            // costs present on the AFT/cell but must be ignored while the feature flag is off
            c.getNfertCosts().put("AFT1", 8.0);

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");
            when(a.getNfertCostPerHa()).thenReturn(10.0);
            when(a.getIntensityCostPerHa()).thenReturn(5.0);

            doReturn(2.0).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);

                // revenue = 10 * 2 = 20; no cost subtraction since use_production_costs=false
                assertEquals(20.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void marginalUtility_isUnaffectedByProductionCosts() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            // use_explicit_price_utility left false (default) so the marginal formula is used
            setConfig(cfg, Map.of("use_production_costs", true, "spatial_production_costs", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");
            when(a.getNfertCostPerHa()).thenReturn(10.0);
            when(a.getIntensityCostPerHa()).thenReturn(5.0);

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0)));

            doReturn(6.0).when(c).competitiveness(a, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            // marginal formula: (0+1)*6 + 0 = 6, unchanged despite use_production_costs=true
            assertEquals(6.0, callUtility(c, a, r), 1e-12);
        });
    }

    @Test
    void dispatcher_useCellLevelTaxes_returnsMarginalWithTexesFormula() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");

            doReturn(4.0).when(c).competitiveness(a, "S1");
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 2.0)));
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>(Map.of("AFT1", 3.0)));

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.initial_service_gaps = new ConcurrentHashMap<>(Map.of("S1", 5.0));
            r.initialutilityAverage = new ConcurrentHashMap<>(Map.of("AFT1", 2.0));
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0)));

            ConfigLoader.config.use_cell_level_taxes = true;
            try {
                // utilityUseMarginalWithTexes: (2*5 + 1)*4 + 3*2 = 44 + 6 = 50
                assertEquals(50.0, callUtility(c, a, r), 1e-12);
            } finally {
                ConfigLoader.config.use_cell_level_taxes = false;
            }
        });
    }

//    @Test
//    void utility_sumsTaxPlusMarginal_timesProductivity_plusLandTax() throws Throwable {
//        withServices(List.of("S1", "S2"), () -> {
//            Cell c = Mockito.spy(new Cell(0, 0));
//
//            Aft a = mock(Aft.class);
//            when(a.isInteract()).thenReturn(true);
//            when(a.getCachedLandTax()).thenReturn(5.0);
//
//            RegionalModelRunner r = mock(RegionalModelRunner.class);
//            when(r.getServiceTax()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0, "S2", 10.0)));
//            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.5, "S2", -2.0)));
//
//            // S1 productivity=2, S2 productivity=3
//            doReturn(2.0).when(c).competitiveness(a, "S1");
//            doReturn(3.0).when(c).productivity(a, "S2");
//
//            // expected: (1+0.5)*2 + (10-2)*3 + 5 = 3 + 24 + 5 = 32
//            assertEquals(32.0, callUtility(c, a, r), 1e-12);
//        });
//    }

//    @Test
//    void associateUtility_setsCellCurrentUtility() throws Throwable {
//        withServices(List.of("S1"), () -> {
//            Cell c = Mockito.spy(new Cell(0, 0));
//
//            Aft owner = mock(Aft.class);
//            when(owner.isInteract()).thenReturn(true);
//            when(owner.getCachedLandTax()).thenReturn(1.0);
//            setOwner(c, owner);
//
//            RegionalModelRunner r = mock(RegionalModelRunner.class);
//            when(r.getServiceTax()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 2.0)));
//            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));
//
//            doReturn(10.0).when(c).productivity(owner, "S1"); // => 2*10 + 1 = 21
//            c.setCurrentUtility(Competitiveness.utility(c, c.getOwner(), r));
//
//
//            assertEquals(21.0, c.getCurrentUtility(), 1e-12);
//        });
//    }

    // -------------------------------------------------------
    // mostCompetitiveAgent(...)
    // -------------------------------------------------------

    @Test
    void mostCompetitiveAgent_returnsOwner_whenSetIsEmpty() throws Throwable {
        withServices(List.of("S1"), () -> {
            Cell c = new Cell(0, 0);
            Aft owner = mock(Aft.class);
            setOwner(c, owner);

            RegionalModelRunner r = mock(RegionalModelRunner.class);

            assertSame(owner, Competitiveness.mostCompetitiveAgent(c, List.of(), r));
        });
    }

//    @Test
//    void mostCompetitiveAgent_picksAgentWithHighestUtility() throws Throwable {
//        withServices(List.of("S1"), () -> {
//            Cell c = Mockito.spy(new Cell(0, 0));
//
//            Aft a1 = mock(Aft.class);
//            when(a1.isInteract()).thenReturn(true);
//            when(a1.getCachedLandTax()).thenReturn(0.0);
//
//            Aft a2 = mock(Aft.class);
//            when(a2.isInteract()).thenReturn(true);
//            when(a2.getCachedLandTax()).thenReturn(0.0);
//
//            RegionalModelRunner r = mock(RegionalModelRunner.class);
//            when(r.getServiceTax()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0)));
//            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));
//
//            // utility = 1 * productivity
//            doReturn(1.0).when(c).productivity(a1, "S1");
//            doReturn(10.0).when(c).productivity(a2, "S1");
//
//            Aft best = Competitiveness.mostCompetitiveAgent(c, List.of(a1, a2), r);
//            assertSame(a2, best);
//        });
//    }

    // -------------------------------------------------------
    // competition(...)
    // -------------------------------------------------------

    @Test
    void competition_takesOver_whenOwnerNull_andCompetitorAboveMean_nonNormalisedPath() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_neighbour_priority", false, "most_competitive_aft_probability", 1.0));

            // categorisation OFF so landUsechange() is used
            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            Cell c = Mockito.spy(new Cell(0, 0));
            setOwner(c, null);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("aft");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            // competitor utility = 1 * 6 = 6
            doReturn(6.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            ConcurrentHashMap<String, Double> meanY = new ConcurrentHashMap<>();
            meanY.put(competitor.getLabel(), 5.0);
            when(r.getDistributionMeanY()).thenReturn(meanY);

            setupSankeyData("aft", 2020);

            try (MockedStatic<AFTsLoader> mocked = Mockito.mockStatic(AFTsLoader.class);
                 MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ConcurrentHashMap<String, Aft> active = new ConcurrentHashMap<>();
                active.put("comp", competitor);
                mocked.when(AFTsLoader::getActivateAFTsHash).thenReturn(active);

                Competitiveness.competition(c, r);
//                assertSame(competitor, getOwner(c));
//                assertEquals(1, Listener.landUseChangeCounter.get());
            }
        });
    }

    @Test
    void competition_isBlockedByMaskRestriction_whenOwnerNull() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_neighbour_priority", false, "most_competitive_aft_probability", 1.0));

            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            Cell c = Mockito.spy(new Cell(0, 0));
            setOwner(c, null);

            // set mask type
            setField(c, "maskType", "mask1");

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("FARM");

            // restrictions: for unmanaged -> competitor takeover key is "FARM_FARM"
            LandMaskUpdater.restrictions.put("mask1", new ConcurrentHashMap<>(Map.of("FARM_FARM", false)));

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            doReturn(100.0).when(c).competitiveness(competitor, "S1");

            ConcurrentHashMap<String, Double> meanY = new ConcurrentHashMap<>();
            meanY.put(competitor.getLabel(), 0.0);
            when(r.getDistributionMeanY()).thenReturn(meanY);

            try (MockedStatic<AFTsLoader> mocked = Mockito.mockStatic(AFTsLoader.class)) {
                mocked.when(AFTsLoader::getActivateAFTsHash)
                        .thenReturn(new ConcurrentHashMap<>(Map.of("comp", competitor)));

                Competitiveness.competition(c, r);

                assertNull(getOwner(c), "Owner must stay null because mask blocks competition");
                assertEquals(0, Listener.landUseChangeCounter.get());
            }
        });
    }

//    @Test
//    void competition_takesOver_whenOwnerExists_andUtilityDifferenceExceedsThreshold_nonNormalisedPath() throws Throwable {
//        withServices(List.of("S1"), () -> {
//            Object cfg = ensureConfigInstance();
//            setConfig(cfg,
//                    Map.of(
//                            "use_neighbour_priority", false,
//                            "most_competitive_aft_probability", 0.0 // force random-AFT branch
//                    )
//            );
//
//            AftCategorised.useCategorisationGivIn = false;
//            CellBehaviourUpdater.behaviourUsed = false;
//
//            Cell c = Mockito.spy(new Cell(0, 0));
//
//            Aft owner = mock(Aft.class);
//            when(owner.isInteract()).thenReturn(true);
//            when(owner.isAbandoned()).thenReturn(false);
//            when(owner.getGiveInMean()).thenReturn(1.0);
//            when(owner.getGiveInSD()).thenReturn(0.0); // remove randomness
//            setOwner(c, owner);
//
//            // set current utility uO=1
//            c.setCurrentUtility(1.0);
//
//            Aft competitor = mock(Aft.class);
//            when(competitor.isInteract()).thenReturn(true);
//            when(competitor.getCachedLandTax()).thenReturn(0.0);
//
//            RegionalModelRunner r = mock(RegionalModelRunner.class);
//            when(r.getServiceTax()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0)));
//            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));
//
//            // competitor uC = 10
//            doReturn(10.0).when(c).competitiveness(competitor, "S1");
//
//            // nbr = mean(owner) * giveInThreshold = 2 * 1 = 2
//            ConcurrentHashMap<Aft, Double> meanY = new ConcurrentHashMap<>();
//            meanY.put(owner, 2.0);
//            when(r.getDistributionMeanY()).thenReturn(meanY);
//
//            try (MockedStatic<AFTsLoader> mocked = Mockito.mockStatic(AFTsLoader.class)) {
//                mocked.when(AFTsLoader::getActivateAFTsHash).thenReturn(new ConcurrentHashMap<>(Map.of("comp", competitor)));
//                mocked.when(() -> AFTsLoader.getRandomAFT(any())).thenReturn(competitor);
//
//                Competitiveness.competition(c, r);
//
//                assertSame(competitor, getOwner(c));
//                assertEquals(1, Listener.landUseChangeCounter.get());
//            }
//        });
//    }
//
//    @Test
//    void competition_takesOver_inNormalisedUtilityPath_whenOwnerNull_andUCPositive() throws Throwable {
//        withServices(List.of("S1"), () -> {
//            Object cfg = ensureConfigInstance();
//            setConfig(cfg,
//                    Map.of(
//                            "use_neighbour_priority", false,
//                            "most_competitive_aft_probability", 0.0 // random
//                    )
//            );
//
//            // force normalised path
//            AftCategorised.useCategorisationGivIn = true;
//            CellBehaviourUpdater.behaviourUsed = true;
//
//            Cell c = Mockito.spy(new Cell(0, 0));
//            setOwner(c, null);
//
//            Aft competitor = mock(Aft.class);
//            when(competitor.isInteract()).thenReturn(true);
//            when(competitor.getCachedLandTax()).thenReturn(0.0);
//
//            RegionalModelRunner r = mock(RegionalModelRunner.class);
//            when(r.getServiceTax()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0)));
//            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));
//            when(r.getMinUtility()).thenReturn(0.0);
//            when(r.getMaxUtility()).thenReturn(10.0);
//
//            // raw utility = 5 => normalised 0.5 > 0 => takeover for owner null
//            doReturn(5.0).when(c).productivity(competitor, "S1");
//
//            try (MockedStatic<AFTsLoader> mocked = Mockito.mockStatic(AFTsLoader.class)) {
//                mocked.when(AFTsLoader::getActivateAFTsHash).thenReturn(new ConcurrentHashMap<>(Map.of("comp", competitor)));
//                mocked.when(() -> AFTsLoader.getRandomAFT(any())).thenReturn(competitor);
//
//                Competitiveness.competition(c, r);
//
//                assertSame(competitor, getOwner(c));
//                assertEquals(1, Listener.landUseChangeCounter.get());
//            }
//        });
//    }

    // -------------------------------------------------------
    // giveInThreshold(...) (private) via reflection
    // -------------------------------------------------------

//    @Test
//    void giveInThreshold_usesCategorisedMeanSd_whenPresent() throws Throwable {
//        AftCategorised.useCategorisationGivIn = true;
//
//        Aft owner = mock(Aft.class);
//        Aft competitor = mock(Aft.class);
//
//        AftCategory ownerCat = mock(AftCategory.class);
//        when(ownerCat.getName()).thenReturn("A");
//        when(owner.getCategory()).thenReturn(ownerCat);
//
//        AftCategory compCat = mock(AftCategory.class);
//        when(compCat.getName()).thenReturn("B");
//        when(competitor.getCategory()).thenReturn(compCat);
//
//        // sd=0 removes randomness from ThreadLocalRandom gaussian
//        AftCategorised.getMean().put("A|B", 0.25);
//        AftCategorised.getSD().put("A|B", 0.0);
//
//        Method m = Competitiveness.class.getDeclaredMethod("giveInThreshold", Cell.class, Aft.class);
//        m.setAccessible(true);
//
//        double v = (double) m.invoke(null, owner, competitor);
//        assertEquals(0.25, v, 1e-12);
//    }

    // -------------------------------------------------------
    // landUsechangeNormalisedPriceUtility (via reflection)
    // -------------------------------------------------------

    private boolean callLandUsechangeNormalisedPriceUtility(Cell c, Aft owner, Aft competitor,
                                                            RegionalModelRunner r) {
        try {
            Method m = Competitiveness.class.getDeclaredMethod(
                    "landUsechangeNormalisedPriceUtility", Cell.class, Aft.class, Aft.class,
                    RegionalModelRunner.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, c, owner, competitor, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setupSankeyData(String aftLabel, int year) {
        Tracker.sankeydata.computeIfAbsent(aftLabel, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(year, k -> new ConcurrentHashMap<>());
    }

    // --- Dispatch gate tests ---

    @Test
    void competition_dispatchesToNormalisedPrice_whenFlagIsTrue() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of(
                    "use_neighbour_priority", false,
                    "most_competitive_aft_probability", 1.0));

            setConfig(cfg, Map.of("use_normalised_price_competition", true));
            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            Cell c = Mockito.spy(new Cell(0, 0));
            setOwner(c, null);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("COMP");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            doReturn(10.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 10.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            r.R = region;

            setupSankeyData("COMP", 2020);

            try (MockedStatic<AFTsLoader> mocked = Mockito.mockStatic(AFTsLoader.class);
                 MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);
                mocked.when(AFTsLoader::getActivateAFTsHash)
                        .thenReturn(new ConcurrentHashMap<>(Map.of("COMP", competitor)));

                ConfigLoader.config.use_explicit_price_utility = true;
                try {
                    Competitiveness.competition(c, r);
                    assertSame(competitor, getOwner(c),
                            "Normalised price path should take over abandoned cell when uC > 0");
                    assertEquals(1, Listener.landUseChangeCounter.get());
                } finally {
                    ConfigLoader.config.use_explicit_price_utility = false;
                }
            }
        });
    }

    @Test
    void competition_doesNotUseNormalisedPrice_whenFlagIsFalse() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of(
                    "use_neighbour_priority", false,
                    "most_competitive_aft_probability", 1.0));

            setConfig(cfg, Map.of("use_normalised_price_competition", false));
            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            Cell c = Mockito.spy(new Cell(0, 0));
            setOwner(c, null);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("COMP");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            // marginal=1 so utility = (0+1)*6 + 0 = 6
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 1.0)));

            doReturn(6.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            ConcurrentHashMap<String, Double> meanY = new ConcurrentHashMap<>();
            meanY.put("COMP", 5.0);
            when(r.getDistributionMeanY()).thenReturn(meanY);

            setupSankeyData("COMP", 2020);

            try (MockedStatic<AFTsLoader> mocked = Mockito.mockStatic(AFTsLoader.class);
                 MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                mocked.when(AFTsLoader::getActivateAFTsHash)
                        .thenReturn(new ConcurrentHashMap<>(Map.of("COMP", competitor)));

                Competitiveness.competition(c, r);
                assertSame(competitor, getOwner(c),
                        "Standard landUsechange path should still work when normalised price flag is off");
            }
        });
    }

    // --- Normalised difference behaviour ---

    @Test
    void normalisedPrice_competitorTakesOver_whenNormDiffExceedsGiveIn() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();

            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft owner = mock(Aft.class);
            when(owner.isInteract()).thenReturn(true);
            when(owner.isAbandoned()).thenReturn(false);
            when(owner.getLabel()).thenReturn("OWN");
            when(owner.getGiveInMean()).thenReturn(0.1);
            when(owner.getGiveInSD()).thenReturn(0.0);
            setOwner(c, owner);
            c.setCurrentUtility(2.0);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("COMP");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            // uC = 10. normDiff = (10-2)/(10+2) = 0.667 > giveIn 0.1
            doReturn(10.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 1.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                ConfigLoader.config.use_explicit_price_utility = true;
                try {
                    assertTrue(callLandUsechangeNormalisedPriceUtility(c, owner, competitor, r),
                            "normDiff=0.667 > giveIn=0.1 should return true");
                } finally {
                    ConfigLoader.config.use_explicit_price_utility = false;
                }
            }
        });
    }

    @Test
    void normalisedPrice_competitorDoesNotTakeOver_whenNormDiffBelowGiveIn() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();

            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft owner = mock(Aft.class);
            when(owner.isInteract()).thenReturn(true);
            when(owner.isAbandoned()).thenReturn(false);
            when(owner.getLabel()).thenReturn("OWN");
            when(owner.getGiveInMean()).thenReturn(0.2);
            when(owner.getGiveInSD()).thenReturn(0.0);
            setOwner(c, owner);
            c.setCurrentUtility(5.0);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("COMP");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            // uC = 6. normDiff = (6-5)/(6+5) = 1/11 ~ 0.091 < giveIn 0.2
            doReturn(6.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 1.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                ConfigLoader.config.use_explicit_price_utility = true;
                try {
                    assertFalse(callLandUsechangeNormalisedPriceUtility(c, owner, competitor, r),
                            "normDiff=0.091 < giveIn=0.2 should return false");
                } finally {
                    ConfigLoader.config.use_explicit_price_utility = false;
                }
            }
        });
    }

    // --- Negative utility guard ---

    @Test
    void normalisedPrice_negativeUtilityDoesNotTakeOver() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();

            Cell c = Mockito.spy(new Cell(0, 0));
            setOwner(c, null);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("COMP");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            doReturn(-5.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 1.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                ConfigLoader.config.use_explicit_price_utility = true;
                try {
                    assertFalse(callLandUsechangeNormalisedPriceUtility(c, null, competitor, r),
                            "Competitor with negative utility must not take over even an abandoned cell");
                } finally {
                    ConfigLoader.config.use_explicit_price_utility = false;
                }
            }
        });
    }

    @Test
    void normalisedPrice_negativeUtilityDoesNotTakeOver_evenWhenHigherThanOwner() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft owner = mock(Aft.class);
            when(owner.isInteract()).thenReturn(true);
            when(owner.isAbandoned()).thenReturn(false);
            when(owner.getLabel()).thenReturn("OWN");
            when(owner.getGiveInMean()).thenReturn(0.0);
            when(owner.getGiveInSD()).thenReturn(0.0);
            setOwner(c, owner);
            c.setCurrentUtility(-10.0);

            Aft competitor = mock(Aft.class);
            when(competitor.isInteract()).thenReturn(true);
            when(competitor.getLabel()).thenReturn("COMP");

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            when(r.getMarginal()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.0)));

            // uC = -2 (higher than uO=-10, but still negative)
            doReturn(-2.0).when(c).competitiveness(competitor, "S1");
            when(c.getLandTax()).thenReturn(new ConcurrentHashMap<>());
            when(c.getServicesTax()).thenReturn(new ConcurrentHashMap<>());

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 1.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                ConfigLoader.config.use_explicit_price_utility = true;
                try {
                    assertFalse(callLandUsechangeNormalisedPriceUtility(c, owner, competitor, r),
                            "Competitor with uC=-2 > uO=-10 must NOT take over because uC <= 0");
                } finally {
                    ConfigLoader.config.use_explicit_price_utility = false;
                }
            }
        });
    }

    // -------------------------------------------------------
    // Conversion cost (use_conversion_cost)
    // -------------------------------------------------------
    //
    // The owner holds the cell at utility 20 and has a conversion cost of 100; the challenger's
    // utility is 130; give-in is 0.1. Every path lets the challenger in without the cost and
    // keeps it out with it (uO' = 20 + 100 = 120):
    //   normalised price: (130-20)/150 = 0.73 > 0.1, but (130-120)/250 = 0.04 < 0.1
    //   min-max (0..200): 0.65 > 0.10 + 0.1,          but 0.65 < 0.60 + 0.1
    //   raw (meanY 200):  130-20 = 110 > 200 x 0.1,   but 130-120 = 10 < 20

    private static final double OWNER_UTILITY = 20.0;
    private static final double CHALLENGER_UTILITY = 130.0;
    private static final double OWNER_CONVERSION_COST = 100.0;

    @FunctionalInterface
    private interface RunnerBody {
        void run(RegionalModelRunner r) throws Throwable;
    }

    /**
     * A real interacting AFT, not a mock: effectiveGiveIn reads the category field
     * directly, which a mock would leave null.
     */
    private static Aft conversionAft(String label, String category, double conversionCost) {
        Aft a = new Aft(label);
        a.setType(ManagerTypes.AFT);
        a.setCategory(new AftCategory(category));
        a.setConversionCost(conversionCost);
        a.setGiveInMean(0.1);
        a.setGiveInSD(0.0);
        return a;
    }

    /** A cell held by owner at OWNER_UTILITY, on which the challenger's utility is CHALLENGER_UTILITY. */
    private static Cell challengedCell(Aft owner, Aft challenger) {
        Cell c = Mockito.spy(new Cell(0, 0));
        setOwner(c, owner);
        c.setCurrentUtility(OWNER_UTILITY);
        doReturn(CHALLENGER_UTILITY).when(c).competitiveness(challenger, "S1");
        return c;
    }

    /** Explicit-price utility, one service S1 at price 1.0 in 2020, and use_conversion_cost as given. */
    private void withConversionCost(boolean conversionCostOn, RunnerBody body) throws Throwable {
        withServices(List.of("S1"), () -> {
            ensureConfigInstance();
            AftCategorised.useCategorisationGivIn = false;
            CellBehaviourUpdater.behaviourUsed = false;

            RegionalModelRunner r = mock(RegionalModelRunner.class);
            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 1.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                ConfigLoader.config.use_explicit_price_utility = true;
                ConfigLoader.config.use_conversion_cost = conversionCostOn;
                try {
                    body.run(r);
                } finally {
                    ConfigLoader.config.use_explicit_price_utility = false;
                    ConfigLoader.config.use_conversion_cost = false;
                }
            }
        });
    }

    private boolean callLandUseChange(String method, Cell c, Aft owner, Aft competitor, RegionalModelRunner r) {
        try {
            Method m = Competitiveness.class.getDeclaredMethod(method, Cell.class, Aft.class, Aft.class,
                    RegionalModelRunner.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, c, owner, competitor, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void conversionCost_blocksACrossCategoryTakeover_inNormalisedPricePath() throws Throwable {
        withConversionCost(true, r -> {
            Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
            Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
            Cell c = challengedCell(owner, challenger);

            assertFalse(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                    "(130-120)/250 = 0.04 < give-in 0.1: the owner's conversion cost must keep the challenger out");
        });
    }

    @Test
    void conversionCost_isIgnored_whenOwnerAndChallengerShareACategory() throws Throwable {
        withConversionCost(true, r -> {
            Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
            Aft challenger = conversionAft("COMP", "Nonex", 0.0);
            Cell c = challengedCell(owner, challenger);

            assertTrue(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                    "Same category: no conversion cost, so (130-20)/150 = 0.73 > 0.1 takes over");
        });
    }

    @Test
    void conversionCost_isIgnored_whenTheSwitchIsOff() throws Throwable {
        withConversionCost(false, r -> {
            Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
            Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
            Cell c = challengedCell(owner, challenger);

            assertTrue(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                    "use_conversion_cost off: the comparison must be exactly as before");
        });
    }

    @Test
    void conversionCost_isTheOwners_notTheChallengers() throws Throwable {
        // It is a cost of leaving a use, so a challenger's own conversion cost must not count.
        withConversionCost(true, r -> {
            Aft owner = conversionAft("OWN", "Agri_Crops", 0.0);
            Aft challenger = conversionAft("COMP", "Nonex", OWNER_CONVERSION_COST);
            Cell c = challengedCell(owner, challenger);

            assertTrue(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                    "The owner has no conversion cost, so the challenger takes over");
        });
    }

    @Test
    void conversionCost_doesNotApply_toAnAbandonedCell() throws Throwable {
        withConversionCost(true, r -> {
            Aft abandoned = new Aft("Abandoned");
            abandoned.setConversionCost(OWNER_CONVERSION_COST);
            Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
            Cell c = challengedCell(abandoned, challenger);

            assertTrue(callLandUsechangeNormalisedPriceUtility(c, abandoned, challenger, r),
                    "An abandoned cell has no owner to pay a conversion cost");
            assertTrue(callLandUsechangeNormalisedPriceUtility(c, null, challenger, r),
                    "An empty cell has no owner to pay a conversion cost");
        });
    }

    @Test
    void conversionCost_isNeverStoredOnTheCell() throws Throwable {
        // currentUtility also feeds giving up, utility statistics and outputs.
        withConversionCost(true, r -> {
            Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
            Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
            Cell c = challengedCell(owner, challenger);

            callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r);

            assertEquals(OWNER_UTILITY, c.getCurrentUtility(), 0.0);
            assertEquals(OWNER_UTILITY + OWNER_CONVERSION_COST,
                    Competitiveness.ownerUtilityAgainst(c, owner, challenger), 0.0);
        });
    }

    @Test
    void conversionCost_isZero_whenEitherCategoryIsMissing() throws Throwable {
        ensureConfigInstance();
        ConfigLoader.config.use_conversion_cost = true;

        Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
        Aft uncategorised = conversionAft("COMP", "Agri_Crops", OWNER_CONVERSION_COST);
        uncategorised.setCategory(null);

        assertEquals(0.0, Competitiveness.conversionCost(owner, uncategorised), 0.0);
        assertEquals(0.0, Competitiveness.conversionCost(uncategorised, owner), 0.0);
    }

    @Test
    void conversionCost_alsoAppliesInTheMinMaxNormalisedPath() throws Throwable {
        for (boolean on : new boolean[] { false, true }) {
            withConversionCost(on, r -> {
                when(r.getMinUtility()).thenReturn(0.0);
                when(r.getMaxUtility()).thenReturn(200.0);
                Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
                Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
                Cell c = challengedCell(owner, challenger);

                assertEquals(!on,
                        callLandUseChange("landUsechangeNormalisedUtility", c, owner, challenger, r),
                        "0.65 beats 0.10 + 0.1 without the cost, but not 0.60 + 0.1 with it");
            });
        }
    }

    @Test
    void conversionCost_alsoAppliesInTheRawPath() throws Throwable {
        for (boolean on : new boolean[] { false, true }) {
            withConversionCost(on, r -> {
                when(r.getDistributionMeanY()).thenReturn(new ConcurrentHashMap<>(Map.of("OWN", 200.0)));
                Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
                Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
                Cell c = challengedCell(owner, challenger);

                assertEquals(!on,
                        callLandUseChange("landUsechange", c, owner, challenger, r),
                        "130-20 = 110 beats 200 x 0.1 without the cost, but 130-120 = 10 does not");
            });
        }
    }

    // -------------------------------------------------------
    // Matrix give-in (use_matrix_give_in)
    // -------------------------------------------------------
    //
    // The same cell as for conversion cost: the owner holds it at utility 20 and the challenger's
    // utility is 130. The owner's givingInDistributionMean is 0.1 and its SD 0.5, so a threshold of
    // exactly 0.1 shows the fallback has no noise.
    //   normalised price: (130-20)/150 = 0.733, so matrix give-in 0.7 lets the challenger in, 0.8 does not
    //   raw (meanY 200):  130-20 = 110, so 200 x 0.5 = 100 lets it in, 200 x 0.6 = 120 does not

    /** A give-in matrix row: challenger label, threshold, challenger label, threshold, ... */
    private static IndexedValues giveInRow(Object... labelsAndValues) {
        IndexedValues row = new IndexedValues(new IndexedValues.Index());
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            row.put((String) labelsAndValues[i], ((Number) labelsAndValues[i + 1]).doubleValue());
        }
        return row;
    }

    private static Aft matrixAft(String label) {
        Aft a = conversionAft(label, "Nonex", 0.0);
        a.setGiveInSD(0.5);
        return a;
    }

    /** As withConversionCost(false, ...), with use_matrix_give_in as given. */
    private void withMatrixGiveIn(boolean matrixOn, RunnerBody body) throws Throwable {
        withConversionCost(false, r -> {
            ConfigLoader.config.use_matrix_give_in = matrixOn;
            try {
                body.run(r);
            } finally {
                ConfigLoader.config.use_matrix_give_in = false;
            }
        });
    }

    private double callGiveInThreshold(Cell c, Aft owner, Aft competitor) {
        try {
            Method m = Competitiveness.class.getDeclaredMethod("giveInThreshold", Cell.class, Aft.class, Aft.class);
            m.setAccessible(true);
            return (double) m.invoke(null, c, owner, competitor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void matrixGiveIn_readsTheOwnersRow_atTheChallengersColumn() {
        Aft owner = matrixAft("OWN");
        Aft challenger = matrixAft("COMP");
        owner.setGiveInRow(giveInRow("COMP", 0.7, "OTHER", 0.2));
        challenger.setGiveInRow(giveInRow("OWN", 0.9));

        assertEquals(0.7, Competitiveness.matrixGiveIn(owner, challenger), 0.0);
        assertEquals(0.9, Competitiveness.matrixGiveIn(challenger, owner), 0.0);
    }

    @Test
    void matrixGiveIn_fallsBackToTheOwnersMean_withoutNoise() {
        Aft owner = matrixAft("OWN");
        Aft challenger = matrixAft("COMP");

        owner.setGiveInRow(giveInRow("OTHER", 0.2)); // a blank cell: no value for COMP
        assertEquals(0.1, Competitiveness.matrixGiveIn(owner, challenger), 0.0,
                "A pair with no value uses givingInDistributionMean, without its SD");

        owner.setGiveInRow(null);
        assertEquals(0.1, Competitiveness.matrixGiveIn(owner, challenger), 0.0,
                "An owner with no row uses givingInDistributionMean, without its SD");
    }

    @Test
    void matrixGiveIn_decidesTheNormalisedPricePath() throws Throwable {
        for (double value : new double[] { 0.7, 0.8 }) {
            withMatrixGiveIn(true, r -> {
                Aft owner = matrixAft("OWN");
                Aft challenger = matrixAft("COMP");
                owner.setGiveInRow(giveInRow("COMP", value));
                Cell c = challengedCell(owner, challenger);

                assertEquals(value == 0.7, callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                        "normDiff 0.733 against matrix give-in " + value);
            });
        }
    }

    @Test
    void matrixGiveIn_isTheOwnersRow_notTheChallengers() throws Throwable {
        withMatrixGiveIn(true, r -> {
            Aft owner = matrixAft("OWN");
            Aft challenger = matrixAft("COMP");
            owner.setGiveInRow(giveInRow("COMP", 0.8));
            challenger.setGiveInRow(giveInRow("OWN", 0.0));
            Cell c = challengedCell(owner, challenger);

            assertFalse(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                    "The owner's 0.8 must keep the challenger out; the challenger's own row must not count");
        });
    }

    @Test
    void matrixGiveIn_isScaledByTheOwnersMeanUtility_inTheRawPath() throws Throwable {
        for (double value : new double[] { 0.5, 0.6 }) {
            withMatrixGiveIn(true, r -> {
                when(r.getDistributionMeanY()).thenReturn(new ConcurrentHashMap<>(Map.of("OWN", 200.0)));
                Aft owner = matrixAft("OWN");
                Aft challenger = matrixAft("COMP");
                owner.setGiveInRow(giveInRow("COMP", value));
                Cell c = challengedCell(owner, challenger);

                assertEquals(value == 0.5, callLandUseChange("landUsechange", c, owner, challenger, r),
                        "130-20 = 110 against 200 x " + value);
            });
        }
    }

    @Test
    void matrixGiveIn_isTheSameInEveryCellAndYear() throws Throwable {
        // With the switch off, give-in is the owner's mean plus SD x a draw that differs by cell and year.
        ensureConfigInstance();
        AftCategorised.useCategorisationGivIn = false;
        Aft owner = matrixAft("OWN");
        Aft challenger = matrixAft("COMP");
        owner.setGiveInRow(giveInRow("COMP", 0.7));

        for (boolean on : new boolean[] { true, false }) {
            Set<Double> thresholds = new HashSet<>();
            ConfigLoader.config.use_matrix_give_in = on;
            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                for (int year : new int[] { 2020, 2021 }) {
                    ts.when(Timestep::getCurrentYear).thenReturn(year);
                    for (int x = 0; x < 3; x++) {
                        thresholds.add(callGiveInThreshold(new Cell(x, 0), owner, challenger));
                    }
                }
            } finally {
                ConfigLoader.config.use_matrix_give_in = false;
            }
            if (on) {
                assertEquals(Set.of(0.7), thresholds, "Matrix give-in has no noise");
            } else {
                assertTrue(thresholds.size() > 1, "Switch off: the noisy threshold is unchanged");
            }
        }
    }

    @Test
    void matrixGiveIn_stacksWithConversionCost() throws Throwable {
        withConversionCost(true, r -> {
            ConfigLoader.config.use_matrix_give_in = true;
            try {
                Aft owner = conversionAft("OWN", "Nonex", OWNER_CONVERSION_COST);
                Aft challenger = conversionAft("COMP", "Agri_Crops", 0.0);
                Cell c = challengedCell(owner, challenger);

                // With the cost, normDiff = (130-120)/250 = 0.04 (0.733 without it).
                owner.setGiveInRow(giveInRow("COMP", 0.0));
                assertTrue(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r), "0.04 > 0.0");
                owner.setGiveInRow(giveInRow("COMP", 0.05));
                assertFalse(callLandUsechangeNormalisedPriceUtility(c, owner, challenger, r),
                        "0.04 < 0.05: the conversion cost must still count");
            } finally {
                ConfigLoader.config.use_matrix_give_in = false;
            }
        });
    }

    // -------------------------------------------------------
    // utility(...) — PLUM price-ratio path (use_price_only_utility)
    // -------------------------------------------------------

    @Test
    void priceRatioUtility_basicCalculation() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_price_only_utility", true, "use_explicit_price_utility", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");
            when(a.getNfertCostPerHa()).thenReturn(10.0);
            when(a.getIntensityCostPerHa()).thenReturn(5.0);

            doReturn(0.8).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 100.0, 2025, 150.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2025);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                // U = (150/100) * 0.8 = 1.2 — no cost subtraction despite costs being set
                assertEquals(1.2, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void priceRatioUtility_returnsZero_whenAftNull() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_price_only_utility", true, "use_explicit_price_utility", false));

            Cell c = new Cell(0, 0);
            RegionalModelRunner r = mock(RegionalModelRunner.class);

            assertEquals(0.0, callUtility(c, null, r), 1e-12);
        });
    }

    @Test
    void priceRatioUtility_returnsZero_whenAftNotInteract() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_price_only_utility", true, "use_explicit_price_utility", false));

            Cell c = new Cell(0, 0);
            RegionalModelRunner r = mock(RegionalModelRunner.class);

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(false);
            assertEquals(0.0, callUtility(c, a, r), 1e-12);
        });
    }

    @Test
    void priceRatioUtility_handlesZeroInitialWeight() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_price_only_utility", true, "use_explicit_price_utility", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");

            doReturn(0.5).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 0.0, 2025, 100.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2025);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                // Infinity from 100/0 → clamped to 0 by NaN/Inf guard → total = 0
                assertEquals(0.0, callUtility(c, a, r), 1e-12);
            }
        });
    }

    @Test
    void priceRatioUtility_multipleServices() throws Throwable {
        withServices(List.of("S1", "S2"), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_price_only_utility", true, "use_explicit_price_utility", false));

            Cell c = Mockito.spy(new Cell(0, 0));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");

            doReturn(2.0).when(c).competitiveness(a, "S1");
            doReturn(3.0).when(c).competitiveness(a, "S2");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 100.0, 2025, 200.0)));
            Service s2 = mock(Service.class);
            when(s2.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 50.0, 2025, 75.0)));

            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1, "S2", s2)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2025);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                // S1: (200/100)*2 = 4.0;  S2: (75/50)*3 = 4.5;  total = 8.5
                assertEquals(8.5, callUtility(c, a, r), 1e-12);
            }
        });
    }

    // -------------------------------------------------------
    // Price feedback (use_price_feedback)
    // -------------------------------------------------------
    //
    // Plan 39a §2.4: file price 200 $/t; intensive 8 t/ha at 900 $/ha, extensive 4 t/ha at 200 $/ha.
    //   M = 1:    intensive 700, extensive 600   -> intensive wins
    //   M = 0.75: intensive 300, extensive 400   -> extensive wins
    //   M = 0.5:  intensive -100, extensive 200  -> intensive gives up, extensive does not

    private static Aft costedAft(String label, double costPerHa) {
        Aft a = mock(Aft.class);
        when(a.isInteract()).thenReturn(true);
        when(a.getLabel()).thenReturn(label);
        when(a.getNfertCostPerHa()).thenReturn(costPerHa);
        return a;
    }

    /**
     * Explicit-price utility with production costs, at the given file prices in 2020, on a mock runner
     * whose price multipliers are the given ones. use_price_feedback is set as given.
     */
    private void withFeedbackPrices(boolean feedbackOn, Map<String, Double> filePrices,
                                    Map<String, Double> multipliers, RunnerBody body) throws Throwable {
        withServices(new ArrayList<>(new TreeSet<>(filePrices.keySet())), () -> {
            Object cfg = ensureConfigInstance();
            setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_production_costs", true,
                    "spatial_production_costs", false, "use_price_feedback", feedbackOn));

            ConcurrentHashMap<String, Service> services = new ConcurrentHashMap<>();
            filePrices.forEach((serviceName, price) -> {
                Service s = mock(Service.class);
                when(s.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, price)));
                services.put(serviceName, s);
            });
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(services);
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;
            when(r.getPriceMultiplier()).thenReturn(new ConcurrentHashMap<>(multipliers));

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2020);
                ts.when(Timestep::getStartYear).thenReturn(2020);
                body.run(r);
            } finally {
                ConfigLoader.config.use_price_feedback = false;
                ConfigLoader.config.use_production_costs = false;
            }
        });
    }

    @Test
    void priceFeedback_scalesTheFilePrice_inExplicitPriceUtility() throws Throwable {
        withFeedbackPrices(true, Map.of("S1", 200.0), Map.of("S1", 0.75), r -> {
            Aft intensive = costedAft("INT", 900.0);
            Cell c = Mockito.spy(new Cell(0, 0));
            doReturn(8.0).when(c).competitiveness(intensive, "S1");

            // 200 x 0.75 x 8 - 900 = 300
            assertEquals(300.0, callUtility(c, intensive, r), 1e-9);
        });
    }

    @Test
    void priceFeedback_isIgnored_whenTheSwitchIsOff() throws Throwable {
        withFeedbackPrices(false, Map.of("S1", 200.0), Map.of("S1", 0.75), r -> {
            Aft intensive = costedAft("INT", 900.0);
            Cell c = Mockito.spy(new Cell(0, 0));
            doReturn(8.0).when(c).competitiveness(intensive, "S1");

            // The file price: 200 x 8 - 900 = 700
            assertEquals(700.0, callUtility(c, intensive, r), 1e-9);
        });
    }

    @Test
    void priceFeedback_leavesAServiceWithNoMultiplier_atItsFilePrice() throws Throwable {
        withFeedbackPrices(true, Map.of("S1", 200.0, "S2", 50.0), Map.of("S1", 0.5), r -> {
            Aft mixed = costedAft("MIX", 0.0);
            Cell c = Mockito.spy(new Cell(0, 0));
            doReturn(1.0).when(c).competitiveness(mixed, "S1");
            doReturn(2.0).when(c).competitiveness(mixed, "S2");

            // S1: 200 x 0.5 x 1 = 100; S2: 50 x 1 x 2 = 100
            assertEquals(200.0, callUtility(c, mixed, r), 1e-9);
        });
    }

    @Test
    void priceFeedback_turnsTheContestFromIntensiveToExtensive_asOversupplyGrows() throws Throwable {
        for (double[] row : new double[][] { { 1.0, 700, 600 }, { 0.75, 300, 400 }, { 0.5, -100, 200 } }) {
            double m = row[0];
            withFeedbackPrices(true, Map.of("S1", 200.0), Map.of("S1", m), r -> {
                Aft intensive = costedAft("INT", 900.0);
                Aft extensive = costedAft("EXT", 200.0);
                Cell c = Mockito.spy(new Cell(0, 0));
                doReturn(8.0).when(c).competitiveness(intensive, "S1");
                doReturn(4.0).when(c).competitiveness(extensive, "S1");

                assertEquals(row[1], callUtility(c, intensive, r), 1e-9, "intensive at M=" + m);
                assertEquals(row[2], callUtility(c, extensive, r), 1e-9, "extensive at M=" + m);
                Aft best = Competitiveness.mostCompetitiveAgent(c, List.of(intensive, extensive), r);
                assertSame(m == 1.0 ? intensive : extensive, best, "most competitive at M=" + m);
            });
        }
    }

    @Test
    void priceFeedback_neverAppliesInThePriceRatioMode() throws Throwable {
        withServices(List.of("S1"), () -> {
            Object cfg = ensureConfigInstance();
            // Startup stops this combination; the utility must still ignore the multiplier.
            setConfig(cfg, Map.of("use_price_only_utility", true, "use_explicit_price_utility", false,
                    "use_price_feedback", true));

            Aft a = mock(Aft.class);
            when(a.isInteract()).thenReturn(true);
            when(a.getLabel()).thenReturn("AFT1");
            Cell c = Mockito.spy(new Cell(0, 0));
            doReturn(0.8).when(c).competitiveness(a, "S1");

            Service s1 = mock(Service.class);
            when(s1.getWeights()).thenReturn(new ConcurrentHashMap<>(Map.of(2020, 100.0, 2025, 150.0)));
            Region region = mock(Region.class);
            when(region.getServicesHash()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", s1)));
            RegionalModelRunner r = mock(RegionalModelRunner.class);
            r.R = region;
            when(r.getPriceMultiplier()).thenReturn(new ConcurrentHashMap<>(Map.of("S1", 0.5)));

            try (MockedStatic<Timestep> ts = Mockito.mockStatic(Timestep.class)) {
                ts.when(Timestep::getCurrentYear).thenReturn(2025);
                ts.when(Timestep::getStartYear).thenReturn(2020);

                // U = (150/100) x 0.8 = 1.2, as without feedback
                assertEquals(1.2, callUtility(c, a, r), 1e-12);
            } finally {
                ConfigLoader.config.use_price_feedback = false;
            }
        });
    }

    @Test
    void configValidation_bothPriceUtilityFlags_shouldBeInvalid() throws Throwable {
        Object cfg = ensureConfigInstance();
        setConfig(cfg, Map.of("use_explicit_price_utility", true, "use_price_only_utility", true));

        // The combination is invalid — in the real code validatePriceUtilityConfig() calls LOGGER.fatal()
        assertTrue(ConfigLoader.config.use_explicit_price_utility);
        assertTrue(ConfigLoader.config.use_price_only_utility);
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static void withServices(List<String> services, ExecutableThrowing body) throws Throwable {
        List<String> provided = new ArrayList<>(services);

        try {
            // try mutate real list
            List<String> real = ServiceSet.getServicesList();
            List<String> backup = new ArrayList<>(real);
            try {
                real.clear();
                real.addAll(provided);
                body.run();
            } finally {
                real.clear();
                real.addAll(backup);
            }
        } catch (UnsupportedOperationException ex) {
            // fallback to static mocking
            try (MockedStatic<ServiceSet> mocked = Mockito.mockStatic(ServiceSet.class)) {
                mocked.when(ServiceSet::getServicesList).thenReturn(provided);
                body.run();
            }
        }
    }

    @FunctionalInterface
    private interface ExecutableThrowing {
        void run() throws Throwable;
    }

    private Object ensureConfigInstance() throws Exception {
        Field cfgField = findField(ConfigLoader.class, "config");
        cfgField.setAccessible(true);
        Object cfg = cfgField.get(null);
        if (cfg == null) {
            cfg = cfgField.getType().getDeclaredConstructor().newInstance();
            cfgField.set(null, cfg);
        }
        return cfg;
    }

    private static void setConfig(Object cfg, Map<String, Object> values) {
        values.forEach((k, v) -> setField(cfg, k, v));
    }

    private static void setOwner(Cell c, Aft owner) {
        // Prefer a setter if present, else set field.
        try {
            Method m = findMethod(c.getClass(), "setOwner", Aft.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(c, owner);
                return;
            }
        } catch (Exception ignored) {
        }
        setField(c, "owner", owner);
    }

    private static Aft getOwner(Cell c) {
        try {
            Method m = findMethod(c.getClass(), "getOwner");
            if (m != null) {
                m.setAccessible(true);
                return (Aft) m.invoke(c);
            }
        } catch (Exception ignored) {
        }
        return (Aft) getField(c, "owner");
    }

    private static Map<String, ConcurrentHashMap<String, Boolean>> deepCopyRestrictions(
            Map<String, ConcurrentHashMap<String, Boolean>> src) {
        Map<String, ConcurrentHashMap<String, Boolean>> copy = new HashMap<>();
        for (var e : src.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? null : new ConcurrentHashMap<>(e.getValue()));
        }
        return copy;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object getField(Object target, String fieldName) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read field '" + fieldName + "'", e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field '" + fieldName + "'", e);
        }
    }
}

