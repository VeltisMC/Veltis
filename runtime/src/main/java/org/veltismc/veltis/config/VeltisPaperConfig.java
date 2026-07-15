package org.veltismc.veltis.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VeltisPaperConfig {

    private static final Yaml YAML = new Yaml();

    private static GlobalConfiguration global = new GlobalConfiguration();
    private static WorldConfiguration worldDefaults = new WorldConfiguration();

    private VeltisPaperConfig() {}

    public static void load(Path homeDir) {
        var configDir = homeDir.resolve("config");
        try {
            Files.createDirectories(configDir);
        } catch (Exception ignored) {}

        var globalFile = configDir.resolve("paper-global.yml");
        if (!Files.exists(globalFile)) {
            writeDefaults(configDir, "paper-global.yml", new GlobalConfiguration());
        }
        if (Files.exists(globalFile)) {
            try (InputStream in = Files.newInputStream(globalFile)) {
                var raw = loadYaml(in);
                if (raw != null) {
                    global = new GlobalConfiguration();
                    applyMap(global, raw);
                }
            } catch (Exception ignored) {}
        }

        var worldDefaultsFile = configDir.resolve("paper-world-defaults.yml");
        if (!Files.exists(worldDefaultsFile)) {
            writeDefaults(configDir, "paper-world-defaults.yml", new WorldConfiguration());
        }
        if (Files.exists(worldDefaultsFile)) {
            try (InputStream in = Files.newInputStream(worldDefaultsFile)) {
                var raw = loadYaml(in);
                if (raw != null) {
                    worldDefaults = new WorldConfiguration();
                    applyMap(worldDefaults, raw);
                }
            } catch (Exception ignored) {}
        }
    }

    public static GlobalConfiguration global() { return global; }
    public static WorldConfiguration worldDefaults() { return worldDefaults; }

    public static WorldConfiguration worldConfig(Path worldDir) {
        var file = worldDir.resolve("paper-world.yml");
        if (!Files.exists(file)) return worldDefaults;
        try (InputStream in = Files.newInputStream(file)) {
            var raw = loadYaml(in);
            if (raw != null) {
                var wc = new WorldConfiguration();
                applyMap(wc, raw);
                return wc;
            }
        } catch (Exception ignored) {}
        return worldDefaults;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyMap(Object target, Map<String, Object> source) {
        for (var entry : source.entrySet()) {
            var key = toFieldName(entry.getKey());
            var value = entry.getValue();
            try {
                var field = findField(target.getClass(), key);
                if (field == null) continue;
                if (value instanceof Map submap && !isSimpleMap(submap)) {
                    var nested = field.get(target);
                    if (nested == null) {
                        nested = field.getType().getDeclaredConstructor().newInstance();
                        field.set(target, nested);
                    }
                    applyMap(nested, (Map) submap);
                } else {
                    setField(target, field, value);
                }
            } catch (Exception ignored) {}
        }
    }

    private static boolean isSimpleMap(Map<?, ?> map) {
        for (var key : map.keySet()) {
            if (key instanceof String s && !(map.get(s) instanceof Map)) continue;
            return false;
        }
        return true;
    }

    private static String toFieldName(String yamlKey) {
        var parts = yamlKey.split("-");
        if (parts.length <= 1) return yamlKey;
        var sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private static Field findField(Class<?> clazz, String name) {
        for (var f = clazz; f != null; f = f.getSuperclass()) {
            try {
                var field = f.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setField(Object target, Field field, Object value) throws Exception {
        var type = field.getType();
        if (value == null) return;
        if (type.isInstance(value)) {
            field.set(target, value);
        } else if (type == int.class || type == Integer.class) {
            if (value instanceof Number n) field.set(target, n.intValue());
            else if (value instanceof String s) field.set(target, "default".equals(s) || "disabled".equals(s) ? -1 : Integer.parseInt(s));
        } else if (type == long.class || type == Long.class) {
            if (value instanceof Number n) field.set(target, n.longValue());
            else if (value instanceof String s) field.set(target, "default".equals(s) || "disabled".equals(s) ? -1L : Long.parseLong(s));
        } else if (type == double.class || type == Double.class) {
            if (value instanceof Number n) field.set(target, n.doubleValue());
            else if (value instanceof String s) field.set(target, "default".equals(s) || "disabled".equals(s) ? -1.0 : Double.parseDouble(s));
        } else if (type == float.class || type == Float.class) {
            if (value instanceof Number n) field.set(target, n.floatValue());
            else field.set(target, Float.parseFloat(value.toString()));
        } else if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean b) field.set(target, b);
            else field.set(target, "true".equals(value.toString()));
        } else if (type == String.class) {
            field.set(target, value.toString());
        } else if (type == List.class) {
            if (value instanceof List l) field.set(target, new ArrayList(l));
        } else if (type == Map.class) {
            if (value instanceof Map m) field.set(target, new LinkedHashMap(m));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(InputStream in) {
        var loaded = YAML.load(in);
        if (loaded instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return null;
    }

    private static void writeDefaults(Path dir, String filename, Object defaults) {
        try {
            var data = objectToMap(defaults);
            if (data.containsKey("_version") && data.get("_version") instanceof Integer v) {
                data.put("_version", v);
            }
            var yamlStr = YAML.dump(data);
            Files.writeString(dir.resolve(filename), yamlStr);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> objectToMap(Object obj) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        for (var field : obj.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            var key = toYamlKey(field.getName());
            var val = field.get(obj);
            if (val == null) continue;
            if (isSimpleType(val)) {
                result.put(key, val);
            } else if (val instanceof List list) {
                result.put(key, list);
            } else if (val instanceof Map map) {
                result.put(key, new LinkedHashMap(map));
            } else {
                result.put(key, objectToMap(val));
            }
        }
        return result;
    }

    private static boolean isSimpleType(Object val) {
        return val instanceof Number || val instanceof Boolean || val instanceof String || val instanceof Character;
    }

    private static String toYamlKey(String fieldName) {
        var sb = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            var ch = fieldName.charAt(i);
            if (Character.isUpperCase(ch)) {
                sb.append('-');
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ==================== GLOBAL CONFIGURATION ====================

    public static final class GlobalConfiguration {
        public int _version = 31;

        public ChunkLoadingBasic chunkLoadingBasic = new ChunkLoadingBasic();
        public ChunkLoadingAdvanced chunkLoadingAdvanced = new ChunkLoadingAdvanced();
        public ChunkSystem chunkSystem = new ChunkSystem();
        public Collisions collisions = new Collisions();
        public Commands commands = new Commands();
        public Console console = new Console();
        public ItemValidation itemValidation = new ItemValidation();
        public Messages messages = new Messages();
        public Misc misc = new Misc();
        public PacketLimiter packetLimiter = new PacketLimiter();
        public PlayerAutoSave playerAutoSave = new PlayerAutoSave();
        public Proxies proxies = new Proxies();
        public Scoreboards scoreboards = new Scoreboards();
        public SpamLimiter spamLimiter = new SpamLimiter();
        public Spark spark = new Spark();
        public Time time = new Time();
        public UnsupportedSettings unsupportedSettings = new UnsupportedSettings();
        public UpdateChecker updateChecker = new UpdateChecker();
        public Watchdog watchdog = new Watchdog();
        public Anticheat anticheat = new Anticheat();
        public BlockUpdates blockUpdates = new BlockUpdates();

        public static final class ChunkLoadingBasic {
            public double playerMaxChunkSendRate = 75.0;
            public double playerMaxChunkLoadRate = 100.0;
            public double playerMaxChunkGenerateRate = -1.0;
        }

        public static final class ChunkLoadingAdvanced {
            public boolean autoConfigSendDistance = true;
            public int playerMaxConcurrentChunkLoads = 0;
            public int playerMaxConcurrentChunkGenerates = 0;
        }

        public static final class ChunkSystem {
            public int ioThreads = -1;
            public int workerThreads = -1;
        }

        public static final class Collisions {
            public boolean enablePlayerCollisions = true;
            public boolean sendFullPosForHardCollidingEntities = true;
        }

        public static final class Commands {
            public boolean suggestPlayerNamesWhenNullTabCompletions = true;
            public boolean rideCommandAllowPlayerAsVehicle = false;
        }

        public static final class Console {
            public boolean enableBrigadierHighlighting = true;
            public boolean enableBrigadierCompletions = true;
            public boolean hasAllPermissions = false;
        }

        public static final class ItemValidation {
            public int displayName = 8192;
            public int loreLine = 8192;
            public Book book = new Book();
            public BookSize bookSize = new BookSize();
            public boolean resolveSelectorsInBooks = false;

            public static final class Book {
                public int title = 8192;
                public int author = 8192;
                public int page = 16384;
            }

            public static final class BookSize {
                public int pageMax = 2560;
                public double totalMultiplier = 0.98;
            }
        }

        public static final class Messages {
            public Kick kick = new Kick();
            public String noPermission = "<red>I'm sorry, but you do not have permission to perform this command. Please contact the server administrators if you believe that this is in error.";
            public boolean useDisplayNameInQuitMessage = false;

            public static final class Kick {
                public String authenticationServersDown = "<lang:multiplayer.disconnect.authservers_down>";
                public String connectionThrottle = "Connection throttled! Please wait before reconnecting.";
                public String flyingPlayer = "<lang:multiplayer.disconnect.flying>";
                public String flyingVehicle = "<lang:multiplayer.disconnect.flying>";
            }
        }

        public static final class Misc {
            public ChatThreads chatThreads = new ChatThreads();
            public int maxJoinsPerTick = 5;
            public boolean sendFullPosForItemEntities = false;
            public boolean loadPermissionsYmlBeforePlugins = true;
            public int regionFileCacheSize = 256;
            public boolean useAlternativeLuckFormula = false;
            public boolean useDimensionTypeForCustomSpawners = false;
            public boolean strictAdvancementDimensionCheck = false;
            public String compressionLevel = "default";
            public String clientInteractionLeniencyDistance = "default";
            public String xpOrbGroupsPerArea = "default";
            public boolean preventNegativeVillagerDemand = false;
            public boolean enableNether = true;
            public boolean fixFarEndTerrainGeneration = true;
            public int maxTrackingCombatEntries = 10240;

            public static final class ChatThreads {
                public int chatExecutorCoreSize = -1;
                public int chatExecutorMaxSize = -1;
            }
        }

        public static final class PacketLimiter {
            public String kickMessage = "<red><lang:disconnect.exceeded_packet_rate>";
            public AllPackets allPackets = new AllPackets();
            public Map<String, Object> overrides = new LinkedHashMap<>();

            public static final class AllPackets {
                public double interval = 7.0;
                public double maxPacketRate = 500.0;
                public String action = "KICK";
            }
        }

        public static final class PlayerAutoSave {
            public int rate = -1;
            public int maxPerTick = -1;
        }

        public static final class Proxies {
            public BungeeCord bungeeCord = new BungeeCord();
            public Velocity velocity = new Velocity();
            public boolean proxyProtocol = false;

            public static final class BungeeCord {
                public boolean onlineMode = true;
            }

            public static final class Velocity {
                public boolean enabled = false;
                public boolean onlineMode = true;
                public String secret = "";
            }
        }

        public static final class Scoreboards {
            public boolean trackPluginScoreboards = false;
            public boolean saveEmptyScoreboardTeams = true;
        }

        public static final class SpamLimiter {
            public int tabSpamIncrement = 1;
            public int tabSpamLimit = 500;
            public int recipeSpamIncrement = 1;
            public int recipeSpamLimit = 20;
            public int incomingPacketThreshold = 300;
        }

        public static final class Spark {
            public boolean enabled = true;
            public boolean enableImmediately = false;
        }

        public static final class Time {
            public boolean affectsAllWorlds = false;
        }

        public static final class UnsupportedSettings {
            public boolean allowUnsafeEndPortalTeleportation = false;
            public boolean skipTripwireHookPlacementValidation = false;
            public boolean allowPermanentBlockBreakExploits = false;
            public boolean allowPistonDuplication = false;
            public boolean performUsernameValidation = true;
            public boolean allowHeadlessPistons = false;
            public boolean skipVanillaDamageTickWhenShieldBlocked = false;
            public boolean updateEquipmentOnPlayerActions = true;
            public Map<String, Object> oversizedItemComponentSanitizer = new LinkedHashMap<>();
        }

        public static final class UpdateChecker {
            public boolean enabled = true;
        }

        public static final class Watchdog {
            public int earlyWarningEvery = 5000;
            public int earlyWarningDelay = 10000;
        }

        public static final class Anticheat {
            public Obfuscation obfuscation = new Obfuscation();

            public static final class Obfuscation {
                public Items items = new Items();

                public static final class Items {
                    public boolean enableItemObfuscation = false;
                    public Map<String, Object> allModels = new LinkedHashMap<>();
                    public Map<String, Object> modelOverrides = new LinkedHashMap<>();
                }
            }
        }

        public static final class BlockUpdates {
            public boolean disableNoteblockUpdates = false;
            public boolean disableTripwireUpdates = false;
            public boolean disableChorusPlantUpdates = false;
            public boolean disableMushroomBlockUpdates = false;
        }
    }

    // ==================== WORLD CONFIGURATION ====================

    public static final class WorldConfiguration {
        public int _version = 31;

        public Anticheat anticheat = new Anticheat();
        public Chunks chunks = new Chunks();
        public Collisions collisions = new Collisions();
        public CommandBlocks commandBlocks = new CommandBlocks();
        public Entities entities = new Entities();
        public Environment environment = new Environment();
        public FeatureSeeds featureSeeds = new FeatureSeeds();
        public FishingTimeRange fishingTimeRange = new FishingTimeRange();
        public Fixes fixes = new Fixes();
        public Hopper hopper = new Hopper();
        public Lootables lootables = new Lootables();
        public Maps maps = new Maps();
        public MaxGrowthHeight maxGrowthHeight = new MaxGrowthHeight();
        public Misc misc = new Misc();
        public Scoreboards scoreboards = new Scoreboards();
        public Spawn spawn = new Spawn();
        public TickRates tickRates = new TickRates();
        public UnsupportedSettings unsupportedSettings = new UnsupportedSettings();

        public static final class Anticheat {
            public AntiXray antiXray = new AntiXray();

            public static final class AntiXray {
                public boolean enabled = false;
                public int engineMode = 1;
                public int maxBlockHeight = 64;
                public int updateRadius = 2;
                public boolean lavaObscures = false;
                public boolean usePermission = false;
                public List<String> hiddenBlocks = List.of(
                    "copper_ore", "deepslate_copper_ore", "raw_copper_block",
                    "gold_ore", "deepslate_gold_ore", "iron_ore", "deepslate_iron_ore",
                    "raw_iron_block", "coal_ore", "deepslate_coal_ore",
                    "lapis_ore", "deepslate_lapis_ore", "mossy_cobblestone",
                    "obsidian", "chest", "diamond_ore", "deepslate_diamond_ore",
                    "redstone_ore", "deepslate_redstone_ore", "clay",
                    "emerald_ore", "deepslate_emerald_ore", "ender_chest");
                public List<String> replacementBlocks = List.of("stone", "oak_planks", "deepslate");
            }
        }

        public static final class Chunks {
            public String autoSaveInterval = "default";
            public int maxAutoSaveChunksPerTick = 24;
            public int fixedChunkInhabitedTime = -1;
            public boolean preventMovingIntoUnloadedChunks = false;
            public String delayChunkUnloadsBy = "10s";
            public Map<String, Integer> entityPerChunkSaveLimit = new LinkedHashMap<>(Map.of(
                "arrow", -1, "ender_pearl", -1, "experience_orb", -1,
                "fireball", -1, "small_fireball", -1, "snowball", -1));
            public boolean flushRegionsOnSave = false;
        }

        public static final class Collisions {
            public boolean onlyPlayersCollide = false;
            public boolean allowVehicleCollisions = true;
            public boolean fixClimbingBypassingCrammingRule = false;
            public int maxEntityCollisions = 8;
            public boolean allowPlayerCrammingDamage = false;
        }

        public static final class CommandBlocks {
            public int permissionsLevel = 2;
            public boolean forceFollowPermLevel = true;
        }

        public static final class Entities {
            public ArmorStands armorStands = new ArmorStands();
            public Markers markers = new Markers();
            public MobEffects mobEffects = new MobEffects();
            public Sniffer sniffer = new Sniffer();
            public Spawning spawning = new Spawning();
            public Behavior behavior = new Behavior();
            public TrackingRangeY trackingRangeY = new TrackingRangeY();

            public static final class ArmorStands {
                public boolean doCollisionEntityLookups = true;
                public boolean tick = true;
            }

            public static final class Markers {
                public boolean tick = true;
            }

            public static final class MobEffects {
                public boolean spidersImmuneToPoisonEffect = true;
                public ImmuneToWitherEffect immuneToWitherEffect = new ImmuneToWitherEffect();

                public static final class ImmuneToWitherEffect {
                    public boolean wither = true;
                    public boolean witherSkeleton = true;
                }
            }

            public static final class Sniffer {
                public String hatchTime = "default";
                public String boostedHatchTime = "default";
            }

            public static final class Spawning {
                public String nonPlayerArrowDespawnRate = "default";
                public String creativeArrowDespawnRate = "default";
                public int maxArrowDespawnInvulnerability = 200;
                public boolean filterBadTileEntityNbtFromFallingBlocks = true;
                public List<String> filteredEntityTagNbtPaths = List.of("Pos", "Motion", "sleeping_pos");
                public boolean disableMobSpawnerSpawnEggTransformation = false;
                public boolean perPlayerMobSpawns = true;
                public boolean scanForLegacyEnderDragon = true;
                public Map<String, Integer> spawnLimits = new LinkedHashMap<>(Map.of(
                    "ambient", -1, "axolotls", -1, "creature", -1,
                    "monster", -1, "underground_water_creature", -1,
                    "water_ambient", -1, "water_creature", -1));
                public Map<String, DespawnRangePair> despawnRanges = new LinkedHashMap<>();
                public String despawnRangeShape = "ELLIPSOID";
                public Map<String, Integer> ticksPerSpawn = new LinkedHashMap<>(Map.of(
                    "ambient", -1, "axolotls", -1, "creature", -1,
                    "monster", -1, "underground_water_creature", -1,
                    "water_ambient", -1, "water_creature", -1));
                public Map<String, String> despawnTime = new LinkedHashMap<>(Map.of(
                    "snowball", "disabled", "llama_spit", "disabled"));
                public WaterAnimalSpawnHeight wateranimalSpawnHeight = new WaterAnimalSpawnHeight();
                public SlimeSpawnHeight slimeSpawnHeight = new SlimeSpawnHeight();
                public WanderingTrader wanderingTrader = new WanderingTrader();
                public boolean allChunksAreSlimeChunks = false;
                public String skeletonHorseThunderSpawnChance = "default";
                public boolean ironGolemsCanSpawnInAir = false;
                public boolean countAllMobsForSpawning = false;
                public String monsterSpawnMaxLightLevel = "default";
                public DuplicateUUID duplicateUuid = new DuplicateUUID();
                public AltItemDespawnRate altItemDespawnRate = new AltItemDespawnRate();

                public static final class DespawnRangePair {
                    public String hard = "default";
                    public String soft = "default";
                }

                public static final class WaterAnimalSpawnHeight {
                    public String maximum = "default";
                    public String minimum = "default";
                }

                public static final class SlimeSpawnHeight {
                    public SurfaceBiome surfaceBiome = new SurfaceBiome();
                    public SlimeChunk slimeChunk = new SlimeChunk();

                    public static final class SurfaceBiome {
                        public double maximum = 70.0;
                        public double minimum = 50.0;
                    }

                    public static final class SlimeChunk {
                        public double maximum = 40.0;
                    }
                }

                public static final class WanderingTrader {
                    public int spawnMinuteLength = 1200;
                    public int spawnDayLength = 24000;
                    public int spawnChanceFailureIncrement = 25;
                    public int spawnChanceMin = 25;
                    public int spawnChanceMax = 75;
                }

                public static final class DuplicateUUID {
                    public String mode = "SAFE_REGEN";
                    public int safeRegenDeleteRange = 32;
                }

                public static final class AltItemDespawnRate {
                    public boolean enabled = false;
                    public Map<String, Integer> items = new LinkedHashMap<>(Map.of("cobblestone", 300));
                }
            }

            public static final class Behavior {
                public boolean disableChestCatDetection = false;
                public boolean spawnerNerfedMobsShouldJump = false;
                public int experienceMergeMaxValue = -1;
                public boolean shouldRemoveDragon = false;
                public boolean zombiesTargetTurtleEggs = true;
                public boolean piglinsGuardChests = true;
                public double babyZombieMovementModifier = 0.5;
                public boolean allowSpiderWorldBorderClimbing = true;
                public Map<String, List<String>> doorBreakingDifficulty = new LinkedHashMap<>(Map.of(
                    "husk", List.of("HARD"),
                    "zombie", List.of("HARD"),
                    "zombie_villager", List.of("HARD"),
                    "zombified_piglin", List.of("HARD"),
                    "vindicator", List.of("NORMAL", "HARD")));
                public boolean disableCreeperLingeringEffect = false;
                public boolean enderDragonsDeathAlwaysPlacesDragonEgg = false;
                public boolean phantomsDoNotSpawnOnCreativePlayers = true;
                public boolean phantomsOnlyAttackInsomniacs = true;
                public int playerInsomniaStartTicks = 72000;
                public int phantomsSpawnAttemptMinSeconds = 60;
                public int phantomsSpawnAttemptMaxSeconds = 119;
                public boolean parrotsAreUnaffectedByPlayerMovement = false;
                public String zombieVillagerInfectionChance = "default";
                public MobsCanAlwaysPickUpLoot mobsCanAlwaysPickUpLoot = new MobsCanAlwaysPickUpLoot();
                public boolean disablePlayerCrits = false;
                public boolean nerfPigmenFromNetherPortals = false;
                public boolean onlyMergeItemsHorizontally = false;
                public PillagerPatrols pillagerPatrols = new PillagerPatrols();
                public boolean cooldownFailedBeehiveReleases = true;
                public int stuckEntityPoiRetryDelay = 200;

                public static final class MobsCanAlwaysPickUpLoot {
                    public boolean zombies = false;
                    public boolean skeletons = false;
                }

                public static final class PillagerPatrols {
                    public boolean disable = false;
                    public double spawnChance = 0.2;
                    public SpawnDelay spawnDelay = new SpawnDelay();
                    public Start start = new Start();

                    public static final class SpawnDelay {
                        public boolean perPlayer = false;
                        public int ticks = 12000;
                    }

                    public static final class Start {
                        public boolean perPlayer = false;
                        public int day = 5;
                    }
                }
            }

            public static final class TrackingRangeY {
                public boolean enabled = false;
                public String player = "default";
                public String animal = "default";
                public String monster = "default";
                public String misc = "default";
                public String display = "default";
                public String other = "default";
            }
        }

        public static final class Environment {
            public boolean disableThunder = false;
            public boolean disableIceAndSnow = false;
            public boolean optimizeExplosions = false;
            public boolean disableExplosionKnockback = false;
            public boolean generateFlatBedrock = false;
            public FrostedIce frostedIce = new FrostedIce();
            public double voidDamageAmount = 4.0;
            public double voidDamageMinBuildHeightOffset = -64.0;
            public TreasureMaps treasureMaps = new TreasureMaps();
            public int fireTickDelay = 30;
            public int waterOverLavaFlowSpeed = 5;
            public int portalSearchRadius = 128;
            public int portalCreateRadius = 16;
            public boolean portalSearchVanillaDimensionScaling = true;
            public String netherCeilingVoidDamageHeight = "disabled";
            public int maxFluidTicks = 65536;
            public int maxBlockTicks = 65536;
            public boolean locateStructuresOutsideWorldBorder = false;

            public static final class FrostedIce {
                public boolean enabled = true;
                public Delay delay = new Delay();

                public static final class Delay {
                    public int min = 20;
                    public int max = 40;
                }
            }

            public static final class TreasureMaps {
                public boolean enabled = true;
                public boolean findAlreadyDiscoveredVillager = false;
                public String findAlreadyDiscoveredLootTable = "default";
            }
        }

        public static final class FeatureSeeds {
            public boolean generateRandomSeedsForAll = false;
            public Map<String, Long> features = new LinkedHashMap<>();
        }

        public static final class FishingTimeRange {
            public int minimum = 100;
            public int maximum = 600;
        }

        public static final class Fixes {
            public boolean fixItemsMergingThroughWalls = false;
            public boolean disableUnloadedChunkEnderpearlExploit = false;
            public boolean preventTntFromMovingInWater = false;
            public boolean splitOverstackedLoot = true;
            public String fallingBlockHeightNerf = "disabled";
            public String tntEntityHeightNerf = "disabled";
        }

        public static final class Hopper {
            public boolean cooldownWhenFull = true;
            public boolean disableMoveEvent = false;
            public boolean ignoreOccludingBlocks = false;
        }

        public static final class Lootables {
            public boolean autoReplenish = false;
            public boolean restrictPlayerReloot = true;
            public String restrictPlayerRelootTime = "disabled";
            public boolean resetSeedOnFill = true;
            public int maxRefills = -1;
            public String refreshMin = "12h";
            public String refreshMax = "2d";
            public boolean retainUnlootedShulkerBoxLootTableOnNonPlayerBreak = true;
        }

        public static final class Maps {
            public int itemFrameCursorLimit = 128;
            public int itemFrameCursorUpdateInterval = 10;
        }

        public static final class MaxGrowthHeight {
            public int cactus = 3;
            public int reeds = 3;
            public Bamboo bamboo = new Bamboo();

            public static final class Bamboo {
                public int max = 16;
                public int min = 11;
            }
        }

        public static final class Misc {
            public boolean allowRemoteEnderDragonRespawning = false;
            public String alternateCurrentUpdateOrder = "HORIZONTAL_FIRST_OUTWARD";
            public boolean disableEndCredits = false;
            public boolean disableRelativeProjectileVelocity = false;
            public boolean disableSprintInterruptionOnAttack = false;
            public boolean legacyEnderPearlBehavior = false;
            public String maxLeashDistance = "default";
            public String redstoneImplementation = "VANILLA";
            public boolean showSignClickCommandFailureMsgsToPlayer = false;
            public boolean updatePathfindingOnBlockUpdate = true;
        }

        public static final class Scoreboards {
            public boolean allowNonPlayerEntitiesOnScoreboards = true;
            public boolean useVanillaWorldScoreboardNameColoring = false;
        }

        public static final class Spawn {
            public boolean allowUsingSignsInsideSpawnProtection = false;
        }

        public static final class TickRates {
            public int grassSpread = 1;
            public int containerUpdate = 1;
            public int mobSpawner = 1;
            public int wetFarmland = 1;
            public int dryFarmland = 1;
            public Map<String, Map<String, Integer>> sensor = new LinkedHashMap<>(Map.of(
                "villager", new LinkedHashMap<>(Map.of("secondarypoisensor", 40))));
            public Map<String, Map<String, Integer>> behavior = new LinkedHashMap<>(Map.of(
                "villager", new LinkedHashMap<>(Map.of("validatenearbypoi", -1))));
        }

        public static final class UnsupportedSettings {
            public boolean fixInvulnerableEndCrystalExploit = true;
            public boolean disableWorldTickingWhenEmpty = false;
            public Ticking ticking = new Ticking();

            public static final class Ticking {
                public boolean chunks = true;
                public boolean blockEntities = true;
            }
        }
    }
}
