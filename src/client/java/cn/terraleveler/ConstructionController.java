package cn.terraleveler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.Direction.Plane;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;

public final class ConstructionController {
   /** How far to look for a bank when swimming out of water. */
   private static final int WATER_ESCAPE_RADIUS = 32;
   private static final FlattenFluidDrainPlanner.Limits FLATTEN_FLUID_LIMITS = new FlattenFluidDrainPlanner.Limits(10, 40, 2);
   private static final int MAX_ROUTE_FAILURES = 3;
   // Route searches run on the client thread; spread them over ticks.
   private static final int MAX_BUDGET_RELIEF_ROUTE_SEARCHES = 4;
   private static final int BUDGET_RELIEF_ROUTE_VISITS = 8000;
   private static final int MAX_FINAL_CLEANUP_TOPOLOGY_CONFLICTS = 24;
   private static final int MAX_TEMPORARY_EXCAVATION_ROUTE_SCAFFOLD_DEBT = 16;
   private static final int MAX_DETACHED_ROLLING_CONFIRMATION_TICKS = 80;
   // A placement the client already shows waits this long for the server to
   // acknowledge the prediction before it is accepted conservatively.
   private static final int MAX_DETACHED_ROLLING_PREDICTION_TICKS = 200;
   private static final int MAX_FLATTEN_PILLAR_STEP_TICKS = 160;
   /** 0.8.86: 48 could not reach an old scaffold bridge 51 blocks above the finished ground. */
   private static final int MAX_FINAL_SCAFFOLD_PILLAR_HEIGHT = 64;
   /** Time one excavation target may stay current without being dug: four minutes. */
   static final int STUCK_EXCAVATION_TARGET_TICKS = 20 * 60 * 4;
   private static final int AUTO_RETRY_PAUSE_TICKS = 600;
   private static final int MAX_CONSECUTIVE_AUTO_RETRIES = 10;
   private static final int INVENTORY_DISCARD_SETTLE_TICKS = 2;
   private static final int INVENTORY_DISCARD_STABILITY_TICKS = 12;
   private static final int UNEXPECTED_PICKUP_VISIBILITY_GRACE_TICKS = 40;
   private static final float DIRECTED_DISCARD_PITCH = -30.0F;
   private static final int MAX_NEARBY_DEFENSE_EPISODE_TICKS = 600;
   private static final int NEARBY_DEFENSE_COOLDOWN_TICKS = 200;
   private static final int MAX_UNCOMMITTED_WORK_ROUTE_ARRIVALS = 3;
   private static final int MAX_NEARBY_PICKUP_ROUTE_SEARCHES = 6;
   private static final int NEARBY_PICKUP_ROUTE_VISITS = 400;
   /** Sprinting stops at this food level; only then may eating interrupt a committed route. */
   static final int STARVING_FOOD_LEVEL = 6;
   /** Ordinary eating threshold: a 6-nutrition item is not wasted. */
   static final int HUNGRY_FOOD_LEVEL = 14;
   /** Natural regeneration needs at least this food level. */
   static final int NATURAL_REGENERATION_FOOD_LEVEL = 18;
   static final int LOW_HEALTH_RESUME_MARGIN = 4;
   /**
    * Empty main-inventory slots kept free while working. quickShulker returns the box
    * through an empty slot, and pickups during the transaction can take the slots it
    * frees; below this reserve the inventory is cleared before anything else.
    */
   static final int INVENTORY_FREE_SLOT_RESERVE = 3;
   /** Thrown items must land this many columns clear of the work area, beyond pickup reach. */
   static final int DISCARD_OUTSIDE_MARGIN = 2;
   /** Estimated horizontal flight of a thrown stack at DIRECTED_DISCARD_PITCH. */
   private static final double DISCARD_THROW_DISTANCE = 4.0;
   private static final int MAX_COMPLETION_JUNK_DISCARD_TICKS = 200;
   /** Overflow storage waits this long after an extraction (0.8.71 stored the fresh stack again at once). */
   static final int EXTRACTION_STORE_COOLDOWN_TICKS = 200;
   /** Stacks of surface material one box opening takes while filling (0.9.09: one stack per opening before). */
   static final int FILL_EXTRACTION_STACKS = 4;
   /** How long a living thing that is not killed may stand in a surface cell before the cell is placed later. */
   static final int PLACEMENT_BLOCKER_WAIT_TICKS = 100;
   /** Consecutive scaffold placements the client refused before the job pauses (each one replans first). */
   static final int MAX_REJECTED_SCAFFOLD_PLACEMENTS = 3;
   private final Set<BlockPos> rejectedScaffoldPlacements = new HashSet<>();
   private int consecutiveScaffoldPlacementFailures;
   private BlockPos obsoleteScaffoldTarget;
   /**
    * Cells of enclosed fluid pockets (and a 2-block shell) put off until the rest of the
    * job is done, because the pocket could not be reached yet (0.8.74).
    */
   private final Set<BlockPos> deferredFluidZone = new HashSet<>();
   private int defenseApproachStalls;
   /** Approach plans from the approach pose itself before an unhittable target is given up. */
   static final int MAX_DEFENSE_APPROACH_STALLS = 20;
   /** How long a hostile that cannot be seen or reached is ignored (was 100 ticks). */
   static final long UNSEEN_THREAT_IGNORE_TICKS = 600L;
   private int fluidPlacementRotations;
   /** Drain placements found unreachable since the last successful placement. */
   private final Set<BlockPos> unreachableFluidPlacements = new HashSet<>();
   private int fluidRecedeWaitTicks;
   static final int FLUID_RECEDE_WAIT_TICKS = 60;
   private boolean fluidRecedeDone;
   private int fluidRecedeLastWet = Integer.MAX_VALUE;
   private int fluidRecedeStallTicks;
   static final int FLUID_RECEDE_MAX_TICKS = 300;
   static final int FLUID_RECEDE_STALL_TICKS = 40;
   private int fluidObstructedTicks;
   static final int FLUID_OBSTRUCTION_WAIT_TICKS = 200;
   private int fluidInletSettleTicks;
   private int fluidSealExtensions;
   /** Time flowing water left in the seal ring gets to drain once its sources are plugged. */
   static final int FLUID_INLET_SETTLE_TICKS = 200;
   static final int MAX_FLUID_SEAL_EXTENSIONS = 6;
   private boolean deferredFluidRevisit;
   static final int FLUID_ZONE_SHELL = 2;
   private final Set<BlockPos> obsoleteScaffoldRejected = new HashSet<>();
   private int obsoleteScaffoldScanCooldown;
   /** Where the last departure sweep found nothing more to take down; not repeated from the same spot. */
   private BlockPos obsoleteSweepExhaustedFeet;
   /** Fluid left in place at the end of the job (unreachable twice) and the cells around it. */
   private final Set<BlockPos> abandonedFluidZone = new HashSet<>();
   /** Targets the last pre-fill rescan sent back to excavation; the same set twice means no progress. */
   private Set<BlockPos> lastPrefillReplanTargets = Set.of();
   /** Excavation and fill targets the last final rescan sent back; the same set twice means no progress. */
   private Set<BlockPos> lastFinalReplanTargets = Set.of();
   /** Excavation target watched for lack of progress, and the ticks it has been current (0.8.98). */
   private BlockPos stuckWatchTarget;
   private int stuckWatchTicks;
   /** Targets already moved to the end of the queue once because they made no progress. */
   private final Set<BlockPos> stuckDeferredTargets = new HashSet<>();
   private int obsoleteScaffoldsRetired;
   private long lastExpandedRouteSearchTick = Long.MIN_VALUE / 2;
   /** At most one expanded route search per this many ticks (each can visit 160k cells). */
   static final int EXPANDED_ROUTE_SEARCH_COOLDOWN_TICKS = 40;
   private final ServerActionExecutor actions = new ServerActionExecutor();
   private final ToolBoxTransaction toolBoxTransaction = new ToolBoxTransaction();
   private final BatchBreakExecutor batchBreaks = new BatchBreakExecutor();
   private final ExcavationSpatialIndex excavationSpatialIndex = new ExcavationSpatialIndex();
   private ConstructionController.State state = ConstructionController.State.IDLE;
   private ConstructionController.State resumeState = ConstructionController.State.EXCAVATING;
   private FlattenPlan flattenPlan;
   private List<BlockPos> excavationTargets = List.of();
   private int excavationIndex;
   private boolean flattenDirtHarvesting;
   private int flattenDirtHarvestGoal;
   private int flattenDirtHarvestExpected;
   private int flattenDirtPickupSettleTicks;
   private final Set<BlockPos> deferredUnsupportedFluidTargets = new LinkedHashSet<>();
   private final Set<BlockPos> deferredRouteTargets = new LinkedHashSet<>();
   private final Set<BlockPos> deferredFoliageTargets = new LinkedHashSet<>();
   /** Leaf promoted to clear access, mapped to the excavation target it was promoted for. */
   private final Map<BlockPos, BlockPos> promotedFoliageRoots = new HashMap<>();
   /** Excavation targets whose promoted access leaf turned out unreachable: no more leaf promotions. */
   private final Set<BlockPos> foliageAccessExhausted = new HashSet<>();
   private final Set<BlockPos> flattenInteriorFoliageTargets = new LinkedHashSet<>();
   private boolean flattenFoliageCleanup;
   private List<BlockPos> wallTargets = List.of();
   private int wallIndex;
   private Item flattenPlacementMaterial;
   private Item hotbarMaterialRetryItem;
   private int hotbarMaterialRetryTicks;
   private long completed;
   private String detail = "尚未开始";
   private ConstructionController.BreakPurpose breakPurpose = ConstructionController.BreakPurpose.NONE;
   private BlockPos breakTarget;
   private DestructiveSupportDropPlanner.Result scaffoldCleanupDrop;
   private BlockPos fallRecoveryScaffoldCleanupTarget;
   private DestructiveSupportDropPlanner.Result fallRecoveryScaffoldCleanupDrop;
   private DestructiveSupportDropPlanner.Result verticalRecoverySupportDrop;
   private BlockPos flattenCleanupFallStart;
   private boolean flattenCleanupFallWasRouteReturn;
   private boolean flattenCleanupRebaseAfterFall;
   private int actionCooldownTicks;
   private int fluidSnapshotSettleTicks;
   private List<SafeRoutePlanner.RouteStep> route = List.of();
   private int routeIndex;
   private BlockPos routeTaskKey;
   private ConstructionController.State routeReturnState = ConstructionController.State.EXCAVATING;
   private SafeRoutePlanner.RoutePolicy routePolicy = SafeRoutePlanner.RoutePolicy.natural();
   private BlockPos bridgePlacementTarget;
   private Item bridgePlacementItem;
   private int bridgeRecenterTicks;
   private double lastRouteDistance = Double.POSITIVE_INFINITY;
   private int routeNoProgressTicks;
   private int routeBlockerCandidateId = -1;
   private int routeBlockerTicks;
   private int routeCompletionDeferredTicks;
   private int routeRetryCooldownTicks;
   private boolean routeRequiresTaskValidation;
   private boolean routeNeedsContinuation;
   private int routeFailures;
   private BlockPos routeFailureKey;
   private ConstructionController.State routeFailureState;
   private String jobWallBlock = "minecraft:dirt";
   private String jobScaffoldBlock = "minecraft:dirt";
   private int jobScaffoldBudget = 128;
   private int scaffoldsPlaced;
   private final Set<BlockPos> scaffoldPositions = new LinkedHashSet<>();
   private final Map<BlockPos, Block> scaffoldBlocks = new HashMap<>();
   private final Map<BlockPos, Block> submittedScaffoldBlocks = new HashMap<>();
   private String scaffoldJobKey;
   private boolean scaffoldLedgerDirty;
   private int scaffoldLedgerSaveTicks;
   private List<BlockPos> scaffoldCleanupTargets = List.of();
   private Map<BlockPos, Integer> scaffoldCleanupClusters = Map.of();
   private int scaffoldCleanupIndex;
   private int flattenFinalCleanupPass;
   private final Set<BlockPos> flattenCleanupRouteScaffolds = new LinkedHashSet<>();
   private BlockPos flattenCleanupRouteRetireTarget;
   private BlockPos flattenCleanupRouteReturnAnchor;
   private BlockPos flattenCleanupRouteTask;
   private boolean flattenCleanupRouteReturnPending;
   private boolean routeIsFlattenCleanupReturn;
   private final List<SafeRoutePlanner.RouteStep> flattenCleanupOutboundRoute = new ArrayList<>();
   private final Set<BlockPos> flattenCleanupExcursionDeferredTargets = new LinkedHashSet<>();
   private PendingFlattenCleanupReplan pendingFlattenCleanupReplan;
   private final Map<BlockPos, FlattenCleanupTopologyBlock> flattenCleanupTopologyBlocks = new LinkedHashMap<>();
   private int flattenCleanupReservedScaffoldPeak;
   private int routeReservedScaffoldBudget;
   /** One-time extra scaffold allowance while the player is stranded high on own scaffolds. */
   private int strandedEmergencyScaffoldDebt;
   /** Where the emergency allowance was last granted; the same spot does not get it again. */
   private BlockPos strandedEmergencyStart;
   /** The target a water-bucket descent from the sky was last used for; once per target. */
   private BlockPos skyBucketTask;
   private BlockPos climbReliefTask;
   private int climbReliefActive = -1;
   /** The high excavation target a pillar was last tried for in place of a scaffold stair. */
   private BlockPos pillarFirstTask;
   /** Pillar attempts for {@link #pillarFirstTask}; a material prefetch before the pillar counts as one. */
   private int pillarFirstAttempts;
   /** The target the player last walked back down from after getting stuck on a scaffold stair. */
   private BlockPos strandedDescentTask;
   /** Where a walk-down search last failed and at which scaffold count; it is not repeated unchanged. */
   private BlockPos strandedDescentFailedStart;
   private int strandedDescentFailedCount = -1;
   private BlockPos budgetReliefScaffoldTarget;
   private int budgetReliefSearchCursor;
   /** Owned scaffolds taken over from an earlier run when this job started; they carry their own allowance. */
   private final Set<BlockPos> inheritedScaffolds = new HashSet<>();
   private int configuredScaffoldBudget;
   /** Budget after the inherited allowance, before the parked allowance; -1 when no job set it. */
   private int baseScaffoldBudget = -1;
   /** Own scaffolds left well above the lowest flatten work level; they do not use up the budget. */
   private int parkedScaffoldAllowance;
   private int lowestFlattenWorkY = Integer.MAX_VALUE;
   /** A full budget-relief scan found nothing: when, at which scaffold count and from where. */
   private long budgetReliefExhaustedTick = Long.MIN_VALUE;
   private int budgetReliefExhaustedCount = -1;
   private BlockPos budgetReliefExhaustedStart;
   private BlockPos budgetReliefSearchTaskKey;
   private BlockPos budgetReliefSearchStart;
   private int budgetReliefSearchCandidateSignature;
   private final Set<BlockPos> budgetReliefRejectedTargets = new LinkedHashSet<>();
   private int budgetReliefRejectedAtScaffoldCount = -1;
   private BlockPos rollingScaffoldRetireTarget;
   private BlockPos rollingScaffoldReplacementTarget;
   private boolean rollingScaffoldReplacementConfirmed;
   private int rollingScaffoldDetachedConfirmationTicks;
   private BlockPos rollingScaffoldOverflowTarget;
   private boolean rollingScaffoldOverflowWasPlayerSupport;
   private BlockPos pendingExcavationRouteCommit;
   private BlockPos uncommittedWorkRouteTarget;
   private int uncommittedWorkRouteArrivals;
   private List<BlockPos> flattenPillarTargets = List.of();
   private int flattenPillarIndex;
   private int flattenPillarStepTicks;
   private int flattenPillarLoggedJumpY = Integer.MIN_VALUE;
   private ConstructionController.State flattenPillarReturnState = ConstructionController.State.BUILDING_WALL;
   private BlockPos finalScaffoldPillarTarget;
   private FinalScaffoldPillarPlanner.Candidate finalScaffoldPillarPlan;
   private Item finalScaffoldPillarMaterial;
   private int finalScaffoldPillarDescentIndex = -1;
   private ConstructionController.FinalScaffoldPillarPhase finalScaffoldPillarPhase = ConstructionController.FinalScaffoldPillarPhase.NONE;
   private boolean finalScaffoldPillarExcavation;
   /** Final-cleanup pillar that removes every owned scaffold reachable from its top (0.8.73). */
   private boolean finalScaffoldPillarCluster;
   private int finalScaffoldPillarClusterRemoved;
   private final Set<BlockPos> clusterPillarRejected = new HashSet<>();
   /** Cleanup targets put behind the rest of the pass because they cannot be reached now (0.8.76). */
   private final Map<BlockPos, Long> parkedCleanupTargets = new LinkedHashMap<>();
   /** Owned scaffolds this job gave up on; they stay in the ledger for a later job. */
   private final Set<BlockPos> leftBehindScaffolds = new LinkedHashSet<>();
   private final Set<BlockPos> pillarBlockersPrioritized = new HashSet<>();
   private long scaffoldRemovalSerial;
   private int parkedCleanupRetries;
   /** Owned scaffold beside a cleanup pillar being taken down on the way up or down. */
   private BlockPos pillarSideTarget;
   private final Set<BlockPos> pillarSideSkipped = new HashSet<>();
   /** Final cleanup targets at least this far above the player's feet are reached by a pillar first. */
   static final int CLUSTER_PILLAR_MIN_RISE = 5;
   static final int BUDGET_RELIEF_PILLAR_CANDIDATES = 3;
   static final int CLUSTER_PILLAR_NEAREST_CANDIDATES = 3;
   static final long PROACTIVE_BUDGET_RELIEF_COOLDOWN_TICKS = 600L;
   static final int PROACTIVE_BUDGET_RELIEF_RADIUS = 24;
   private long lastProactiveBudgetReliefTick = Long.MIN_VALUE / 2;
   private BlockPos flattenEscapeTaskKey;
   private BlockPos flattenEscapeDestination;
   private List<BlockPos> flattenEscapeBreakTargets = List.of();
   private int flattenEscapeBreakIndex;
   private int flattenEscapeStepsUsed;
   private BlockPos unloadedTarget;
   private ConstructionController.State loadingReturnState = ConstructionController.State.EXCAVATING;
   private int loadingRelayAttempts;
   private final Set<BlockPos> failedLoadingRelays = new LinkedHashSet<>();
   private FlattenFluidDrainPlanner.DrainPlan flattenFluidTransaction;
   private int fluidActionIndex;
   private int fluidVerifiedTicks;
   private int fluidReflowAttempts;
   private ConstructionController.State fluidReturnState = ConstructionController.State.EXCAVATING;
   private boolean fluidAdvancesExcavation;
   private ConstructionController.State recoveryReturnState = ConstructionController.State.EXCAVATING;
   private ConstructionController.WaterPhase waterPhase = ConstructionController.WaterPhase.ASCEND;
   private BlockPos waterExit;
   private List<BlockPos> waterWaypoints = List.of();
   private int waterWaypointIndex;
   private int waterRecoveryTicks;
   private int waterNoProgressTicks;
   private int waterRouteNoProgressTicks;
   private int waterReplanCooldown;
   private double waterBestMetric = Double.POSITIVE_INFINITY;
   private double waterHighestY = Double.NEGATIVE_INFINITY;
   private int waterDryTicks;
   private int waterDryUnstableTicks;
   private int fallRecoveryTicks;
   private BlockPos waterBucketFallLanding;
   private int waterBucketFallTicks;
   private int waterBucketFallAimTicks;
   private int waterBucketFallPickupAttempts;
   private boolean waterBucketFallPlacementRequested;
   private boolean waterBucketFallWaterConfirmed;
   private int defenseTargetId = -1;
   private DefensiveCombat.Reason defenseReason;
   private ConstructionController.State defenseReturnState = ConstructionController.State.EXCAVATING;
   private int defenseNoThreatTicks;
   private int defenseApproachCooldown;
   private BlockPos defenseRouteTarget;
   private final TimedEntityIgnoreSet ignoredDefenseEntities = new TimedEntityIgnoreSet();
   private int defenseTicks;
   private long nearbyDefenseCooldownUntilTick;
   private BlockPos combatReturnAnchor;
   private boolean returningFromCombat;
   private int combatOriginalSelectedSlot = -1;
   private int combatWeaponSourceInventory = -1;
   private int combatWeaponHotbar = -1;
   private ItemStack combatLeasedWeapon = ItemStack.EMPTY;
   private ItemStack combatDisplacedStack = ItemStack.EMPTY;
   private int combatEquipSettleTicks;
   private ConstructionController.State eatingReturnState = ConstructionController.State.EXCAVATING;
   private int eatingStartFoodLevel;
   private int eatingLastFoodLevel;
   private int eatingTicks;
   private int eatingUseRetryTicks;
   private int eatingSlot = -1;
   private String eatingItem = "none";
   private int eatingPreviousSelectedSlot = -1;
   private int eatingFoodSourceInventory = -1;
   private int eatingFoodHotbar = -1;
   private ItemStack eatingLeasedFood = ItemStack.EMPTY;
   private ItemStack eatingDisplacedStack = ItemStack.EMPTY;
   private int eatingEquipSettleTicks;
   private boolean ownsUseKey;
   private ConstructionController.State materialReturnState = ConstructionController.State.EXCAVATING;
   private ConstructionController.MaterialOperation materialOperation = ConstructionController.MaterialOperation.EXTRACT;
   /** Opens a material box with QuickShulker and shift-clicks stacks (0.9.08, replaces the Carpet cursor clicks). */
   private final ShulkerBoxMenuTransaction boxTransaction = new ShulkerBoxMenuTransaction();
   /** Surface cell currently occupied by a living thing, and for how many ticks (0.9.09). */
   private BlockPos placementBlockerTarget;
   private int placementBlockerTicks;
   /** Surface cells already moved to the end of the fill queue because something stood in them. */
   private final Set<BlockPos> blockedPlacementsDeferred = new HashSet<>();
   /** Surface cells already routed to with the lookahead stand choice; a second try uses every stand (0.9.10). */
   private final Set<BlockPos> fillLookaheadTried = new HashSet<>();
   /** Cells placed from the current stand instead of walking to them first (0.9.11). */
   private int fillPlacedHere;
   /** Blocks dug from the current stand instead of walking to the queue head first (0.9.13). */
   private int excavationReachPromotions;
   private Item materialRequestedItem;
   private int materialTicks;
   private String materialFailureReason;
   private boolean materialStopRequested;
   private boolean materialResumeAfterRollback;
   private boolean materialStoreRequired;
   /** Surplus stacks stored per box round trip (overflow stores only). */
   private ConstructionController.State toolBoxReturnState = ConstructionController.State.EXCAVATING;
   private ConstructionController.BreakPurpose toolBoxReturnPurpose = ConstructionController.BreakPurpose.NONE;
   private BlockPos toolBoxTarget;
   private int dirtPickupCooldownTicks;
   private int materialStorageCooldownTicks;
   private List<Item> jobPickupItems = List.of(Blocks.DIRT.asItem());
   private String jobPickupMaterialFingerprint = "";
   private int inventoryDiscardSettleTicks;
   private long unexpectedPickupRevision;
   private long inventoryDiscardObservedPickupRevision = -1L;
   private int inventoryDiscardStableTicks;
   private InventoryDiscardTransaction inventoryDiscardTransaction;
   private DirectedItemDiscardTransaction directedItemDiscardTransaction;
   private final Map<Item, Integer> retainedInventoryBaseline = new HashMap<>();
   private final Map<Item, Integer> pendingUnexpectedPickupDiscard = new LinkedHashMap<>();
   private final Map<Item, Long> pendingUnexpectedPickupGraceUntil = new HashMap<>();
   private final Set<Item> pendingUnexpectedPickupObserved = new HashSet<>();
   private final Set<Item> inventoryDiscardSplitBlockedItems = new HashSet<>();
   private final Set<Integer> confirmedPickupEntityIds = new HashSet<>();
   private boolean routeIsMaterialPickupDetour;
   private int pausedTicks;
   private boolean pauseAutoRetryAllowed;
   /** The current pause resumes by itself once health has recovered. */
   private boolean pauseWaitsForHealth;
   private String pauseReason;
   private int consecutiveAutoRetryAttempts;
   private long autoRetryCompletedSnapshot = -1L;
   private int autoRetryCleanupIndexSnapshot = -1;
   private int autoRetryScaffoldCountSnapshot = -1;
   private int diagnosticTicks;
   private double lastDiagnosticX;
   private double lastDiagnosticZ;
   private int horizontalStationarySnapshots;
   /** The current flatten plan is one region of a regional island job. */
   private boolean islandRegionRun;
   /** Pause/resume or stop requested while an inventory transaction could not be interrupted. */
   private ConstructionController.PendingUserRequest pendingUserRequest = ConstructionController.PendingUserRequest.NONE;
   /** The flatten "excavation done, start filling" message was already sent for this job. */
   private boolean fillPhaseAnnounced;
   private int completionJunkDiscardTicks;
   private Item deferredJunkDiscardItem;

   public void start(FlattenPlan newPlan) {
      this.islandRegionRun = false;
      this.startFlatten(newPlan, null);
   }

   /**
    * Starts the next region of a regional island job. The inventory baseline
    * captured when the island job began is kept, so items picked up in earlier
    * regions are still treated as construction pickups rather than "owned".
    */
   void startIslandRegion(FlattenPlan regionPlan) {
      Map<Item, Integer> baseline = new HashMap<>(this.retainedInventoryBaseline);
      this.islandRegionRun = true;
      this.startFlatten(regionPlan, baseline);
   }

   boolean islandRegionRun() {
      return this.islandRegionRun;
   }

   private void startFlatten(FlattenPlan newPlan, Map<Item, Integer> preservedBaseline) {
      Minecraft mc = Minecraft.getInstance();
      this.resetRuntime(mc, "new-flatten-plan");
      this.fillPhaseAnnounced = false;
      BlockPos origin = mc.player == null ? null : mc.player.blockPosition();
      this.flattenPlan = newPlan;
      this.deferredUnsupportedFluidTargets.clear();
      this.deferredRouteTargets.clear();
      this.deferredFoliageTargets.clear();
      this.promotedFoliageRoots.clear();
      this.foliageAccessExhausted.clear();
      this.flattenInteriorFoliageTargets.clear();
      this.flattenInteriorFoliageTargets.addAll(newPlan.interiorFoliageTargets());
      this.flattenFoliageCleanup = false;
      this.wallTargets = new ArrayList<>(newPlan.fillTargetsFrom(origin));
      this.excavationIndex = 0;
      this.wallIndex = 0;
      this.completed = 0L;
      TerraLevelerConfig jobConfig = TerraLeveler.CONFIG;
      this.jobWallBlock = "minecraft:dirt";
      if (jobConfig != null) {
         this.jobScaffoldBlock = jobConfig.scaffoldBlock;
         this.jobScaffoldBudget = jobConfig.scaffoldBudget;
         this.jobPickupItems = configuredPickupItems(jobConfig);
         this.jobPickupMaterialFingerprint = pickupMaterialFingerprint(jobConfig);
         this.restoreOwnedScaffolds(jobConfig, flattenScaffoldJobKey(jobConfig, newPlan));
      }

      FlattenExcavationScheduler.Result scheduled = this.scheduleFlattenExcavation(mc, newPlan, origin, "start");
      FlattenExcavationScheduler.Result foliage = this.scheduleFlattenFoliage(mc, newPlan, origin, "start");
      this.deferredFoliageTargets.addAll(foliage.targets());
      this.cleanupFlattenFoliageTargets(foliage.targets(), jobConfig, "start");
      this.setExcavationTargets(new ArrayList<>(scheduled.targets()));
      if (preservedBaseline == null) {
         this.captureRetainedInventoryBaseline(mc);
      } else {
         this.retainedInventoryBaseline.clear();
         this.retainedInventoryBaseline.putAll(preservedBaseline);
         TerraLevelerDiagnostics.log("MATERIAL", "inventory-baseline-preserved reason=island-region retainedItemTypes=" + this.retainedInventoryBaseline.size());
      }
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = this.state;
      this.detail = "平地机正在从最高层向目标地面逐层削平";
      TerraLevelerDiagnostics.log(
         "STATE",
         "start mode=flatten bounds="
            + newPlan.minX()
            + ","
            + newPlan.minZ()
            + ".."
            + newPlan.maxX()
            + ","
            + newPlan.maxZ()
            + " targetGroundY="
            + newPlan.targetGroundY()
            + " topY="
            + newPlan.scannedTopY()
            + " excavation="
            + newPlan.estimatedBlocks()
            + " deferredFoliage="
            + foliage.targets().size()
            + " fill="
            + newPlan.estimatedFillBlocks()
      );
   }

   public void toggle(FlattenPlan newPlan) {
      this.toggleInternal(newPlan);
   }

   private void toggleInternal(FlattenPlan newFlattenPlan) {
      Minecraft mc = Minecraft.getInstance();
      if (this.inventoryDiscardTransaction != null) {
         // The split transaction may hold items on the cursor; remember the
         // request and apply it as soon as the (bounded) transaction settles.
         if (this.pendingUserRequest != ConstructionController.PendingUserRequest.STOP) {
            this.pendingUserRequest = ConstructionController.PendingUserRequest.TOGGLE;
         }
         this.detail = "正在完成库存整组清理，完成后立即执行暂停/恢复";
         return;
      }
      if (this.state == ConstructionController.State.UNPACKING_MATERIAL) {
         this.beginMaterialRollback(mc, "用户请求暂停，潜影盒补货事务已回滚");
      } else {
         if (this.state == ConstructionController.State.SWAPPING_TOOL) {
            this.toolBoxTransaction.cancel(mc, "user-pause");
            this.toolBoxTransaction.reset();
            this.state = this.toolBoxReturnState;
            this.breakPurpose = this.toolBoxReturnPurpose;
            this.breakTarget = this.toolBoxTarget;
            this.toolBoxReturnState = ConstructionController.State.EXCAVATING;
            this.toolBoxReturnPurpose = ConstructionController.BreakPurpose.NONE;
            this.toolBoxTarget = null;
         }
         if (isRunningState(this.state)) {
            this.resumeState = this.baseTaskState();
            if (this.state == ConstructionController.State.EATING) {
               this.cancelEating(mc, "user-pause");
            }

            if (this.defenseTargetId >= 0 || this.combatWeaponHotbar >= 0) {
               this.releaseCombatWeapon(mc, "user-pause");
               this.clearDefenseForPause();
            }

            this.state = ConstructionController.State.PAUSED;
            this.cancelOwnedWork(mc, "user-pause");
            this.flushOwnedScaffoldLedger();
            this.pausedTicks = 0;
            this.pauseAutoRetryAllowed = false;
            this.pauseWaitsForHealth = false;
            this.pauseReason = "用户主动暂停";
            this.detail = "已暂停；恢复时会重新读取服务器世界状态";
         } else if (this.state == ConstructionController.State.PAUSED && this.workArea() != null) {
            this.cancelOwnedWork(mc, "user-resume-revalidate");
            this.clearBudgetReliefSearch();
            this.clearBudgetReliefRejections();
            this.state = normalizeTaskState(this.resumeState);
            this.pausedTicks = 0;
            this.pauseAutoRetryAllowed = false;
            this.pauseWaitsForHealth = false;
            this.resetAutoRetryStreak();
            this.pauseReason = null;
            this.detail = "正在重新验证当前任务";
            TerraLevelerDiagnostics.log("MATERIAL", "inventory-baseline-preserved reason=user-resume retainedItemTypes=" + this.retainedInventoryBaseline.size());
         } else if (newFlattenPlan != null) {
            this.start(newFlattenPlan);
         }
      }
   }

   public void stop() {
      Minecraft mc = Minecraft.getInstance();
      if (this.inventoryDiscardTransaction != null) {
         this.pendingUserRequest = ConstructionController.PendingUserRequest.STOP;
         this.detail = "正在完成库存整组清理（最多约 2 秒），完成后立即停止";
         TerraLevelerDiagnostics.log("STATE", "stop-deferred reason=inventory-transaction-settling");
         return;
      }
      if (this.state == ConstructionController.State.UNPACKING_MATERIAL && mc.player != null && mc.gameMode != null) {
         this.materialStopRequested = true;
         this.beginMaterialRollback(mc, "用户请求停止，潜影盒补货事务已回滚");
      } else {
         this.resetRuntime(mc, "emergency-stop");
         this.state = ConstructionController.State.IDLE;
         this.detail = "已紧急停止";
         TerraLevelerDiagnostics.log("STATE", "stop");
      }
   }

   public void resetForNewSelection() {
      this.stop();
      this.flattenPlan = null;
      this.setExcavationTargets(List.of());
      this.wallTargets = List.of();
      this.detail = "角点已更新，旧施工计划已清除";
   }

   public void onWorldChanged(Minecraft mc) {
      quickShulkerUnsupported = false;
      this.resetRuntime(mc, "world-changed");
      this.flattenPlan = null;
      this.setExcavationTargets(List.of());
      this.wallTargets = List.of();
      this.state = ConstructionController.State.IDLE;
      this.detail = "世界或维度已切换；旧选区和施工计划已清除";
      TerraLevelerDiagnostics.log("LIFECYCLE", "world-changed old-plan-cleared");
   }

   public void onServerConfirmedPickup(Item item, int amount, int entityId) {
      if (item == null || amount <= 0 || !isRunningState(this.state) || this.workArea() == null
         || isConfiguredConstructionMaterial(item) || isLooseDiscardProtected(item, TerraLeveler.CONFIG)) return;
      if (!this.confirmedPickupEntityIds.add(entityId)) return;
      this.pendingUnexpectedPickupDiscard.merge(item, amount, Integer::sum);
      this.pendingUnexpectedPickupGraceUntil.put(item, currentClientGameTime() + UNEXPECTED_PICKUP_VISIBILITY_GRACE_TICKS);
      this.unexpectedPickupRevision++;
      this.inventoryDiscardStableTicks = 0;
      TerraLevelerDiagnostics.log("MATERIAL", "unexpected-pickup-queued item=" + materialName(item)
         + " amount=" + amount + " entity=" + entityId);
   }

   public void onRetentionConfigChanged() {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      this.pendingUnexpectedPickupDiscard.keySet().removeIf(item -> isLooseDiscardProtected(item, config));
      this.pendingUnexpectedPickupGraceUntil.keySet().removeIf(item -> isLooseDiscardProtected(item, config));
      this.pendingUnexpectedPickupObserved.removeIf(item -> isLooseDiscardProtected(item, config));
      if (this.inventoryDiscardTransaction != null
         && isLooseDiscardProtected(this.inventoryDiscardTransaction.item(), config)
         && this.inventoryDiscardTransaction.canCancelBeforeSubmission()) {
         this.inventoryDiscardTransaction = null;
      }
      this.unexpectedPickupRevision++;
      this.inventoryDiscardStableTicks = 0;
   }

   public void tick(Minecraft mc, TerraLevelerConfig config) {
      this.tickOwnedScaffoldLedgerSave();
      // A started split transaction may hold items on the cursor. It always runs
      // to its bounded end, whatever the controller state, before anything else.
      if (this.inventoryDiscardTransaction != null && mc.player != null && mc.gameMode != null) {
         this.tickInventoryDiscardTransaction(mc);
         return;
      }
      if (this.state == ConstructionController.State.PAUSED) {
         if (this.workArea() != null && mc.player != null && mc.level != null && mc.gameMode != null) {
            this.tickPausedAutoRetry(mc);
         }
      } else if (isRunningState(this.state) && this.workArea() != null) {
         if (mc.player != null && mc.level != null && mc.gameMode != null) {
            this.diagnosticSnapshot(mc);
            if (this.manualMovementOverride(mc)) {
               this.pause(mc, "检测到玩家手动移动（人工接管）", ConstructionController.PauseKind.MANUAL_RESUME);
               return;
            }
            this.batchBreaks.reconcile(mc);
            this.trackStuckExcavationTarget();
            if (this.refreshPickupMaterialConfiguration(mc, config)) {
               return;
            }
            if (this.dirtPickupCooldownTicks > 0) {
               this.dirtPickupCooldownTicks--;
            }

            if (this.materialStorageCooldownTicks > 0) {
               this.materialStorageCooldownTicks--;
            }

            if (this.inventoryDiscardSettleTicks > 0) {
               this.inventoryDiscardSettleTicks--;
            }

            
            if (this.directedItemDiscardTransaction != null && this.tickDirectedItemDiscardTransaction(mc)) {
               return;
            }

            if (this.state != ConstructionController.State.UNPACKING_MATERIAL
               && this.tickDetachedRollingScaffoldPlacement(mc)) {
               return;
            }

            if (this.state != ConstructionController.State.UNPACKING_MATERIAL
               && this.state != ConstructionController.State.SWAPPING_TOOL
               && this.state != ConstructionController.State.EATING
               // Storing returns to the pillar's return state, dropping out of a half-built pillar.
               && this.state != ConstructionController.State.FLATTEN_PILLARING
               && RouteExecutionRules.mayRunOpportunisticInventory(
                  this.routeRequiresTaskValidation,
                  this.hasPendingExcavationRouteCommit(),
                  this.actions.isIdle(),
                  this.bridgePlacementTarget != null || this.rollingScaffoldReplacementTarget != null
               )
               && this.maybeStoreInventoryOverflow(mc)) {
               return;
            }

            if (this.state == ConstructionController.State.UNPACKING_MATERIAL) {
               this.tickMaterialUnpack(mc);
            } else if (this.state == ConstructionController.State.SWAPPING_TOOL) {
               this.tickToolBoxSwap(mc);
            } else if (mc.player.isInLava()) {
               this.pause(mc, "真实玩家进入岩浆；沙子不会被当作岩浆安全方案");
            } else if (this.state == ConstructionController.State.RECOVERING_WATER) {
               this.tickWaterRecovery(mc);
            } else if (shouldBeginGeneralWaterRecovery(mc.player.isInWater(), this.hasActiveWaterBucketFall())) {
               this.beginWaterRecovery(mc);
               this.tickWaterRecovery(mc);
            } else if (this.state == ConstructionController.State.RECOVERING_FALL) {
               this.tickFallRecovery(mc);
            } else {
               boolean committedRouteMotion = (
                     this.state == ConstructionController.State.POSITIONING || this.state == ConstructionController.State.FLATTEN_PILLARING
                  )
                  && PlayerPhysicalState.routeAnchor(mc).isEmpty();
               if (committedRouteMotion || !this.handleDefense(mc, config)) {
                  if (this.state == ConstructionController.State.EATING) {
                     this.tickEating(mc, config);
                  } else if (!this.rollingScaffoldReplacementConfirmed
                     || this.state != ConstructionController.State.EXCAVATING && this.state != ConstructionController.State.POSITIONING) {
                     if (this.rollingScaffoldOverflowTarget != null
                        && this.rollingScaffoldReplacementTarget == null
                        && (this.state == ConstructionController.State.EXCAVATING || this.state == ConstructionController.State.POSITIONING)
                        && WaterBucketRecoveryRules.mayRunRollingOverflowRelease(this.hasActiveWaterBucketFall())) {
                        boolean overflowStillCarriesPlayer = this.isBlockSupportingPlayer(mc, this.rollingScaffoldOverflowTarget);
                        if (overflowStillCarriesPlayer) {
                           this.rollingScaffoldOverflowWasPlayerSupport = true;
                        } else if (RouteExecutionRules.mayReleaseRollingScaffoldOverflow(
                           this.stablePlayerStand(mc), this.rollingScaffoldOverflowWasPlayerSupport, overflowStillCarriesPlayer
                        )) {
                           this.tickRollingScaffoldOverflowRelease(mc, config);
                           return;
                        }
                     }

                     if (this.state != ConstructionController.State.POSITIONING || !this.tickFlattenCleanupRouteScaffoldRetirement(mc, config)) {
                        if (this.state == ConstructionController.State.FLATTEN_PILLARING) {
                           this.tickFlattenPillar(mc, config);
                        } else if (this.state == ConstructionController.State.POSITIONING
                           && this.stablePlayerStand(mc)
                           && this.bridgePlacementTarget == null
                           && this.rollingScaffoldReplacementTarget == null
                           && RouteExecutionRules.mayRunOpportunisticInventory(this.routeRequiresTaskValidation, false)
                           && this.actions.isIdle()
                           && this.batchBreaks.pendingCount() == 0
                           && this.breakPurpose == ConstructionController.BreakPurpose.NONE
                           && this.maybeStoreInventoryOverflow(mc)) {
                           AutoNavigator.release(mc);
                        } else if (config.autoEat && this.stablePlayerStand(mc) && shouldBeginEating(
                           mc.player.getFoodData().getFoodLevel(),
                           mc.player.getHealth(),
                           mc.player.getMaxHealth(),
                           config.minimumHealth,
                           this.state == ConstructionController.State.POSITIONING && this.routeIndex < this.route.size()
                        )) {
                           this.beginEating(mc, config);
                        } else if (this.stablePlayerStand(mc) && mc.player.getHealth() < config.minimumHealth) {
                           // No active threat at this point (defence ran first): stop working
                           // and let natural regeneration recover before any further risk.
                           this.pauseForLowHealth(mc, config);
                        } else if (this.state == ConstructionController.State.POSITIONING) {
                           this.tickPositioning(mc, config);
                        } else if (this.state == ConstructionController.State.LOADING) {
                           this.tickLoading(mc, config);
                        } else if (!this.stablePlayerStand(mc)) {
                           this.beginFallRecovery(mc, this.baseTaskState(), "真实玩家不在稳定地面上");
                        } else if (this.routeRetryCooldownTicks > 0) {
                           this.routeRetryCooldownTicks--;
                           AutoNavigator.release(mc);
                           this.detail = "安全路线重试退避（剩余 " + this.routeRetryCooldownTicks + " 刻）";
                        } else if (this.returningFromCombat && this.state != ConstructionController.State.DEFENDING) {
                           this.beginCombatReturn(mc, config);
                        } else {
                           if ((this.state == ConstructionController.State.EXCAVATING || this.state == ConstructionController.State.BUILDING_WALL)
                              && this.actions.isIdle()
                              && this.batchBreaks.pendingCount() == 0
                              && this.breakPurpose == ConstructionController.BreakPurpose.NONE) {
                              boolean pendingWorkCommit = this.hasPendingExcavationRouteCommit();
                              if (RouteExecutionRules.mayRunOpportunisticInventory(false, pendingWorkCommit)) {
                                 if (this.maybeStoreInventoryOverflow(mc)) {
                                    return;
                                 }

                                 if (this.maybeBeginNearbyMaterialPickup(mc)) {
                                    return;
                                 }
                              }
                           }

                           switch (this.state) {
                              case EXCAVATING:
                                 this.tickExcavation(mc, config);
                              case POSITIONING:
                              case LOADING:
                              case RECOVERING_WATER:
                              case RECOVERING_FALL:
                               case EATING:
                               case UNPACKING_MATERIAL:
                               case SWAPPING_TOOL:
                               default:
                                 break;
                              case CONTAINING_FLUID:
                                 this.tickFluid(mc, config);
                                 break;
                              case DEFENDING:
                                 this.tickDefense(mc, config, this.defenseEntity(mc));
                                 break;
                              case CLEANING_SCAFFOLDS:
                                 this.tickScaffoldCleanup(mc, config);
                                 break;
                              case BUILDING_WALL:
                                 this.tickWall(mc, config);
                           }
                        }
                     }
                  } else {
                     this.tickRollingScaffoldRetirement(mc, config);
                  }
               }
            }
         } else {
            this.onWorldChanged(mc);
         }
      }
   }

   private void tickExcavation(Minecraft mc, TerraLevelerConfig config) {
      if (this.resumeOrTickFinalScaffoldPillar(mc, config, ConstructionController.State.EXCAVATING,
         "高空挖掘临时柱事务中断后，无法重新走回已预检柱底")) {
         return;
      }

      if (this.flattenDirtHarvesting
         && this.breakPurpose == ConstructionController.BreakPurpose.NONE
         && this.actions.isIdle()
         && flattenSurfaceInventoryCount(mc) >= this.flattenDirtHarvestGoal) {
         this.finishFlattenDirtHarvest(mc, "batch-goal-reached");
      } else {
         if (this.breakPurpose == ConstructionController.BreakPurpose.VERTICAL_RECOVERY_SUPPORT) {
            this.tickVerticalRecoverySupportBreak(mc, config);
            return;
         }

         if (this.budgetReliefScaffoldTarget != null && this.breakPurpose == ConstructionController.BreakPurpose.NONE) {
            BlockPos relief = this.budgetReliefScaffoldTarget;
            if (!this.isTrackedScaffoldBlock(mc, relief)) {
               this.forgetScaffold(relief);
               this.budgetReliefScaffoldTarget = null;
               this.clearRouteFailures();
               this.routeRetryCooldownTicks = 1;
               TerraLevelerDiagnostics.log("SCAFFOLD", "budget-relief-already-gone target=" + relief.toShortString() + " active=" + this.activeScaffoldCount());
               return;
            }

            if (!this.safeReach(mc, relief) || this.isBlockSupportingPlayer(mc, relief)) {
               this.budgetReliefScaffoldTarget = null;
               this.routeRetryCooldownTicks = 1;
               // Remember the rejection: re-planning used to pick this same scaffold
               // again every tick because the candidate cursor restarted at zero.
               this.rememberBudgetReliefRejection(relief);
               // The departure sweep only skips obsoleteScaffoldRejected: without this it chose
               // the same unreachable scaffold again every few ticks (0.9.05 test: 3+ minutes
               // at -272,62,-367, route steps=0 but safeReach false on arrival).
               this.obsoleteScaffoldRejected.add(relief.immutable());
               TerraLevelerDiagnostics.log("SCAFFOLD", "budget-relief-goal-rejected target=" + relief.toShortString()
                  + " reason=not-safely-breakable-after-route rejected=" + this.budgetReliefRejectedTargets.size());
               return;
            }

            this.breakPurpose = ConstructionController.BreakPurpose.ROUTE_SCAFFOLD_CLEANUP;
            this.breakTarget = relief.immutable();
            TerraLevelerDiagnostics.log(
               "SCAFFOLD", "budget-relief-arrived target=" + relief.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
            );
         }

         if (this.breakPurpose == ConstructionController.BreakPurpose.ROUTE_SCAFFOLD_CLEANUP) {
            boolean budgetRelief = this.breakTarget != null && this.breakTarget.equals(this.budgetReliefScaffoldTarget);
            if (!this.isTrackedScaffoldBlock(mc, this.breakTarget)) {
               BlockPos stale = this.breakTarget;
               this.forgetScaffold(stale);
               if (budgetRelief) {
                  this.budgetReliefScaffoldTarget = null;
               }

               this.actions.cancel(mc, "route-scaffold-changed");
               this.clearBreak();
               this.clearRouteFailures();
               this.routeRetryCooldownTicks = 1;
               if (stale != null && stale.equals(this.obsoleteScaffoldTarget)) {
                  // The break request already removed it (client view): this is a retirement too.
                  this.obsoleteScaffoldTarget = null;
                  this.obsoleteScaffoldsRetired++;
               }
               TerraLevelerDiagnostics.log("SCAFFOLD", "route-obstacle-already-gone target=" + posText(stale));
            } else if (!this.handleBlockingAnimal(mc, config, this.breakTarget, "route-scaffold-cleanup")) {
               ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, this.breakTarget, config);
               if (status != null) {
                  boolean obsolete = this.breakTarget.equals(this.obsoleteScaffoldTarget);
                  if (status == ServerActionExecutor.Status.RUNNING) {
                     this.detail = budgetRelief
                        ? "回收自有临时脚手以释放路线预算 " + this.breakTarget.toShortString()
                        : obsolete
                        ? "顺手拆除附近已无施工用途的自有临时脚手 " + this.breakTarget.toShortString()
                        : "拆除挡住当前施工路线的自有临时脚手 " + this.breakTarget.toShortString();
                  } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                     BlockPos interrupted = this.breakTarget;
                     this.clearBreak();
                     this.clearRouteFailures();
                     this.routeRetryCooldownTicks = 2;
                     if (obsolete) {
                        this.obsoleteScaffoldTarget = null;
                        this.obsoleteScaffoldRejected.add(interrupted.immutable());
                     }
                     TerraLevelerDiagnostics.log("SCAFFOLD", "route-obstacle-reposition target=" + interrupted.toShortString());
                  } else if (status == ServerActionExecutor.Status.FAILED && obsolete) {
                     // Optional housekeeping: skip this scaffold instead of pausing the job.
                     BlockPos skipped = this.breakTarget;
                     String failure = this.actions.failure();
                     this.actions.cancel(mc, "obsolete-scaffold-failed");
                     this.clearBreak();
                     this.obsoleteScaffoldTarget = null;
                     this.obsoleteScaffoldRejected.add(skipped.immutable());
                     TerraLevelerDiagnostics.log("SCAFFOLD", "obsolete-scaffold-skipped target=" + skipped.toShortString()
                        + " reason=" + failure);
                  } else if (status == ServerActionExecutor.Status.FAILED) {
                     this.pause(mc, this.actionFailure("施工路线阻路脚手清理失败"));
                  } else {
                     BlockPos removed = this.breakTarget;
                     this.clearBreak();
                     if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
                        this.pause(mc, "施工路线阻路脚手清理返回异常状态：" + status);
                     } else {
                        this.forgetScaffold(removed);
                        if (budgetRelief) {
                           this.budgetReliefScaffoldTarget = null;
                        }

                        this.clearRouteFailures();
                        this.startActionCooldown(config);
                        if (obsolete) {
                           this.obsoleteScaffoldTarget = null;
                           this.obsoleteScaffoldsRetired++;
                        }
                        TerraLevelerDiagnostics.log(
                           "SCAFFOLD",
                           (budgetRelief ? "budget-relief-removed target=" : obsolete ? "obsolete-scaffold-retired target=" : "route-obstacle-removed target=")
                              + removed.toShortString()
                              + " active="
                              + this.scaffoldPositions.size()
                              + "/"
                              + this.jobScaffoldBudget
                        );
                     }
                  }
               }
            }
         } else if (this.breakPurpose != ConstructionController.BreakPurpose.EXCAVATION) {
            if (this.breakPurpose == ConstructionController.BreakPurpose.NONE && this.beginObsoleteScaffoldRetirement(mc)) {
               return;
            }

            if (this.breakPurpose == ConstructionController.BreakPurpose.NONE && this.beginProactiveBudgetRelief(mc)) {
               return;
            }

            int inspectedThisTick = 0;

            while (this.excavationIndex < this.excavationTargets.size()) {
               if (++inspectedThisTick > 512) {
                  AutoNavigator.release(mc);
                  this.detail = "本刻已确认 512 个空气/基岩体素；下一刻继续扫描（沙子消耗为 0）";
                  return;
               }

               BlockPos target = this.excavationTargets.get(this.excavationIndex);
               if (!this.deferredFluidRevisit && this.deferredFluidZone.contains(target)) {
                  // Deferred pockets sit at the end of the queue: everything else is done now.
                  this.deferredFluidRevisit = true;
                  TerraLevelerDiagnostics.log("FLUID", "flatten-fluid-zone-revisit target=" + target.toShortString()
                     + " zone=" + this.deferredFluidZone.size() + " remainingTargets=" + (this.excavationTargets.size() - this.excavationIndex));
               }
               if (this.batchBreaks.isPending(target)) {
                  this.detail = "批量挖掘气泡已提交队首方块，导航与其他 pending 不被阻塞 " + target.toShortString();
                  return;
               }
               if (this.abandonedFluidZone.contains(target)) {
                  this.advanceExcavation(mc, 1, "abandoned-fluid-zone");
                  continue;
               }
               if (target.equals(this.stuckWatchTarget) && this.stuckWatchTicks >= STUCK_EXCAVATION_TARGET_TICKS) {
                  if (this.deferStuckExcavationTarget(mc, target)) continue;
                  return;
               }

               ExcavationPlanner.CellKind kind = this.classifyExcavation(mc, target);
               ExcavationPlanner.Operation operation = ExcavationPlanner.operationFor(kind);
               if (operation != ExcavationPlanner.Operation.NONE) {
                  if (operation == ExcavationPlanner.Operation.WAIT_FOR_CHUNK) {
                     this.beginLoading(mc, target, ConstructionController.State.EXCAVATING);
                     return;
                  }

                  if (operation == ExcavationPlanner.Operation.CONTAIN_FLUID) {
                     if (this.fluidSnapshotSettleTicks-- > 0) {
                        AutoNavigator.release(mc);
                        this.detail = "等待流体完成服务器更新后再规划局部排流体（剩余 " + this.fluidSnapshotSettleTicks + " 刻）";
                        return;
                     }

                     this.beginFlattenFluidDrainTask(mc, target);
                     return;
                  }

                  if (config.flattenProtectBlockEntities && mc.level.getBlockEntity(target) != null) {
                     this.pause(mc, "平地削高区域遇到受保护的容器或方块实体 " + target.toShortString());
                     return;
                  }

                  if (this.safeReach(mc, target) && !this.isBlockSupportingPlayer(mc, target)) {
                     if (this.waitForActionCooldown(mc)) {
                        return;
                     }

                     if (this.isPendingExcavationRouteCommit(target)) {
                        this.commitReachedExcavationTarget(target, "batch-ready");
                     }

                     int bubbleSubmitted = this.tickBatchMiningBubble(mc, config, false, target.getY());
                     if (bubbleSubmitted > 0) {
                        this.detail = "本刻静止挖掘气泡已提交 " + bubbleSubmitted + " 个当前层计划方块";
                        return;
                     }

                     this.commitReachedExcavationTarget(target, "break-start");
                     this.breakPurpose = ConstructionController.BreakPurpose.EXCAVATION;
                     this.breakTarget = target.immutable();
                     this.tickExcavation(mc, config);
                     return;
                  }

                  if (this.breakPurpose == ConstructionController.BreakPurpose.NONE
                     && this.actions.isIdle()
                     && this.promoteExcavationReachableHere(mc, target)) {
                     continue;
                  }

                  if (this.beginObsoleteScaffoldSweep(mc, target)) {
                     return;
                  }
                  this.beginWorkRoute(
                     mc, target, target, ConstructionController.WorkAction.BREAK, ConstructionController.State.EXCAVATING, config, "前往可触及边界方块的安全工作站位"
                  );
                  return;
               }

               this.batchBreaks.forget(target);
               this.advanceExcavation(mc, 1, "already-clear-or-bedrock");
            }

            if (this.flattenDirtHarvesting) {
               int collected = flattenSurfaceInventoryCount(mc);
               if (collected > 0) {
                  this.finishFlattenDirtHarvest(mc, "harvest-targets-finished");
               } else if (this.flattenDirtPickupSettleTicks++ < 40) {
                  AutoNavigator.release(mc);
                  this.detail = "等待刚挖出的泥土掉落物进入背包（" + this.flattenDirtPickupSettleTicks + "/40）";
               } else {
                  this.pause(mc, "已挖完高于目标 Y 的候选土层，但没有捡到泥土；请检查背包空间或掉落物");
               }
            } else if (this.verifyFlattenExcavationBeforeFill(mc, config)) {
               TerraLevelerDiagnostics.log("SCAFFOLD", "flatten-prefill-cleanup-deferred active=" + this.activeScaffoldCount() + " fill=" + this.wallTargets.size());
               this.beginWallPhase(mc);
            }
         } else if (!this.handleBlockingAnimal(mc, config, this.breakTarget, "excavation-break")) {
            ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, this.breakTarget, config);
            if (status != null) {
               if (status == ServerActionExecutor.Status.RUNNING) {
                  this.detail = "锁定挖掘边界方块 " + this.breakTarget.toShortString();
               } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                  this.repositionInterruptedBreak(mc, config, ConstructionController.State.EXCAVATING, "挖掘期间被碰撞移出触及距离，重新选择安全站位");
               } else if (status == ServerActionExecutor.Status.FAILED) {
                  this.pause(mc, this.actionFailure("挖掘动作失败"));
               } else {
                  BlockPos finished = this.breakTarget;
                  this.clearBreak();
                  this.startActionCooldown(config);
                  if (status == ServerActionExecutor.Status.RECLASSIFY) {
                     this.detail = "方块破坏后出现纯液体，保持当前施工索引并重新分类 " + finished.toShortString();
                  } else if (status != ServerActionExecutor.Status.SUCCESS) {
                     this.pause(mc, "挖掘执行器返回了不适用于破坏动作的状态 " + status);
                  } else {
                     this.forgetScaffold(finished);
                     this.batchBreaks.forget(finished);
                     this.fluidSnapshotSettleTicks = 4;
                     this.advanceExcavation(mc, 1, "server-confirmed-break");
                  }
               }
            }
         }
      }
   }

   /**
    * Starts removing one owned scaffold within reach that no remaining excavation needs
    * (see {@link ObsoleteScaffoldRules}). Checked at most every 10 ticks, only while
    * standing idle between excavation steps of a flatten job.
    */
   private boolean beginObsoleteScaffoldRetirement(Minecraft mc) {
      if (this.scaffoldPositions.isEmpty()) return false;
      if (this.obsoleteScaffoldScanCooldown > 0) {
         this.obsoleteScaffoldScanCooldown--;
         return false;
      }
      this.obsoleteScaffoldScanCooldown = 10;
      if (!this.actions.isIdle()
         || this.batchBreaks.pendingCount() > 0
         || !this.stablePlayerStand(mc)
         || this.rollingScaffoldRetireTarget != null
         || this.rollingScaffoldReplacementTarget != null
         || this.rollingScaffoldOverflowTarget != null
         || this.bridgePlacementTarget != null
         || this.budgetReliefScaffoldTarget != null
         || this.hasActiveFlattenCleanupExcursion()
         || this.finalScaffoldPillarPhase != ConstructionController.FinalScaffoldPillarPhase.NONE) return false;
      BlockPos feet = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (feet == null) return false;
      Vec3 eye = mc.player.getEyePosition();
      Set<BlockPos> protectedSupports = this.routeSupports();
      BlockPos target = ObsoleteScaffoldRules.select(
            this.scaffoldPositions,
            feet,
            pos -> eye.distanceToSqr(Vec3.atCenterOf(pos)) <= WorkPoseRules.SAFE_REACH_SQUARED
               && !this.obsoleteScaffoldRejected.contains(pos)
               && !protectedSupports.contains(pos)
               && !this.flattenCleanupRouteScaffolds.contains(pos)
               && this.isTrackedScaffoldBlock(mc, pos)
               && this.safeReach(mc, pos)
               && !this.isBlockSupportingPlayer(mc, pos)
               && ObsoleteScaffoldRules.isObsolete(
                  pos,
                  this.excavationSpatialIndex.within(Vec3.atCenterOf(pos), ObsoleteScaffoldRules.KEEP_RADIUS),
                  candidate -> !this.scaffoldPositions.contains(candidate)
                     && (!mc.level.hasChunkAt(candidate)
                        || ExcavationPlanner.operationFor(this.classifyExcavation(mc, candidate)) != ExcavationPlanner.Operation.NONE)
               )
         )
         .orElse(null);
      if (target == null) return false;
      this.breakPurpose = ConstructionController.BreakPurpose.ROUTE_SCAFFOLD_CLEANUP;
      this.breakTarget = target;
      this.obsoleteScaffoldTarget = target;
      this.detail = "顺手拆除附近已无施工用途的自有临时脚手 " + target.toShortString();
      TerraLevelerDiagnostics.log("SCAFFOLD", "obsolete-scaffold-retire-start target=" + target.toShortString()
         + " feet=" + feet.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
         + " retired=" + this.obsoleteScaffoldsRetired);
      return true;
   }

   /** Nothing left to dig at or above the scaffold within {@link ObsoleteScaffoldRules#KEEP_RADIUS}. */
   private boolean obsoleteScaffold(Minecraft mc, BlockPos pos) {
      return ObsoleteScaffoldRules.isObsolete(
         pos,
         this.excavationSpatialIndex.within(Vec3.atCenterOf(pos), ObsoleteScaffoldRules.KEEP_RADIUS),
         candidate -> !this.scaffoldPositions.contains(candidate)
            && (!mc.level.hasChunkAt(candidate)
               || ExcavationPlanner.operationFor(this.classifyExcavation(mc, candidate)) != ExcavationPlanner.Operation.NONE)
      );
   }

   /**
    * Before walking off to a target more than {@link ObsoleteScaffoldRules#SWEEP_TRIGGER_DISTANCE}
    * blocks away, the obsolete own scaffolds near the player that can be reached on foot are
    * taken down first, while they are still at hand. In the 0.8.93 whole-island test only 71
    * scaffolds were retired in passing and 1156 were left at the end, about 950 of them above
    * Y=75, each needing a pillar from the flat ground in the final cleanup.
    */
   private boolean beginObsoleteScaffoldSweep(Minecraft mc, BlockPos nextTarget) {
      if (this.scaffoldPositions.isEmpty() || nextTarget == null
         || !this.actions.isIdle()
         || this.batchBreaks.pendingCount() > 0
         || this.flattenFluidTransaction != null
         || this.rollingScaffoldRetireTarget != null
         || this.rollingScaffoldReplacementTarget != null
         || this.rollingScaffoldOverflowTarget != null
         || this.bridgePlacementTarget != null
         || this.budgetReliefScaffoldTarget != null
         || this.hasActiveFlattenCleanupExcursion()
         || this.finalScaffoldPillarPhase != ConstructionController.FinalScaffoldPillarPhase.NONE
         || !this.stablePlayerStand(mc)) {
         return false;
      }
      BlockPos feet = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (feet == null || !ObsoleteScaffoldRules.leavingArea(feet, nextTarget) || feet.equals(this.obsoleteSweepExhaustedFeet)) {
         return false;
      }
      Set<BlockPos> protectedSupports = this.routeSupports();
      List<BlockPos> candidates = ObsoleteScaffoldRules.sweepCandidates(
         this.scaffoldPositions,
         feet,
         pos -> !this.obsoleteScaffoldRejected.contains(pos)
            && !protectedSupports.contains(pos)
            && !this.flattenCleanupRouteScaffolds.contains(pos)
            && mc.level.hasChunkAt(pos)
            && this.isTrackedScaffoldBlock(mc, pos)
            && !this.isBlockSupportingPlayer(mc, pos)
            && this.obsoleteScaffold(mc, pos)
      );
      SafeRoutePlanner.RoutePolicy policy = SafeRoutePlanner.RoutePolicy.flatteningFloorWithMaximumDrop(3);
      int searched = 0;
      for (BlockPos candidate : candidates) {
         if (searched >= ObsoleteScaffoldRules.SWEEP_SEARCHES_PER_DEPARTURE) break;
         searched++;
         List<BlockPos> poses = this.workPoses(mc, candidate, candidate, ConstructionController.WorkAction.BREAK);
         SafeRoutePlanner.PlanResult plan = poses.isEmpty()
            ? null
            : SafeRoutePlanner.findRouteWithinVisitLimit(mc, feet, poses, this.workArea(), policy, BUDGET_RELIEF_ROUTE_VISITS);
         if (plan == null || !plan.hasRoute()) {
            this.obsoleteScaffoldRejected.add(candidate.immutable());
            continue;
         }
         this.cancelOwnedWork(mc, "obsolete-scaffold-sweep");
         this.budgetReliefScaffoldTarget = candidate.immutable();
         this.route = plan.steps();
         this.routeIndex = 0;
         this.routeTaskKey = candidate.immutable();
         this.routeReturnState = ConstructionController.State.EXCAVATING;
         this.routePolicy = policy;
         this.routeRequiresTaskValidation = false;
         this.routeNeedsContinuation = false;
         this.routeNoProgressTicks = 0;
         this.routeCompletionDeferredTicks = 0;
         this.lastRouteDistance = Double.POSITIVE_INFINITY;
         this.clearRouteFailures();
         this.routeRetryCooldownTicks = 0;
         this.state = this.route.isEmpty() ? ConstructionController.State.EXCAVATING : ConstructionController.State.POSITIONING;
         this.resumeState = ConstructionController.State.EXCAVATING;
         this.detail = "离开前顺手拆除附近已无用途的自有脚手 " + candidate.toShortString();
         TerraLevelerDiagnostics.log("SCAFFOLD", "obsolete-sweep-route target=" + candidate.toShortString() + " feet=" + feet.toShortString()
            + " next=" + nextTarget.toShortString() + " steps=" + this.route.size() + " candidates=" + candidates.size()
            + " searched=" + searched + " active=" + this.activeScaffoldCount());
         return true;
      }
      this.obsoleteSweepExhaustedFeet = feet.immutable();
      if (!candidates.isEmpty()) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "obsolete-sweep-none-reachable feet=" + feet.toShortString() + " candidates=" + candidates.size()
            + " searched=" + searched);
      }
      return false;
   }

   /**
    * With the scaffold budget full, nearby high scaffolds are taken down with a pillar
    * before work continues in rolling-replacement mode. 0.8.74 started with 129/128 left
    * over from the previous session and rolled 276 times; its budget relief only ran from
    * a few route checks and tried once. At most once per 30 seconds, within 24 blocks.
    */
   private boolean beginProactiveBudgetRelief(Minecraft mc) {
      if (this.jobScaffoldBudget <= 0
         || this.activeScaffoldCount() < this.jobScaffoldBudget
         || !this.actions.isIdle() || this.batchBreaks.pendingCount() > 0
         || this.flattenFluidTransaction != null || this.finalScaffoldPillarTarget != null) {
         return false;
      }
      long now = mc.level.getGameTime();
      if (now - this.lastProactiveBudgetReliefTick < PROACTIVE_BUDGET_RELIEF_COOLDOWN_TICKS) return false;
      this.lastProactiveBudgetReliefTick = now;
      BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (start == null || !this.stablePlayerStand(mc)) return false;
      boolean begun = this.beginBudgetReliefPillar(mc, start, PROACTIVE_BUDGET_RELIEF_RADIUS);
      TerraLevelerDiagnostics.log("SCAFFOLD", "proactive-budget-relief begun=" + begun + " active=" + this.activeScaffoldCount()
         + "/" + this.jobScaffoldBudget + " start=" + start.toShortString());
      return begun;
   }

   private int tickBatchMiningBubble(Minecraft mc, TerraLevelerConfig config, boolean moving, int stationaryLayerY) {
      if (batchMiningEnabled(config)
         && this.actions.isIdle()
         && this.breakPurpose == ConstructionController.BreakPurpose.NONE
         && this.rollingScaffoldOverflowTarget == null
         && this.rollingScaffoldReplacementTarget == null
         && mc.level != null
         && mc.player != null
         && this.excavationSpatialIndex.size() != 0) {
         int sendBudget = this.batchBreaks.availableBudget(config.fastBreakBlocksPerTick);
         if (sendBudget <= 0) {
            return 0;
         }

         long gameTime = mc.level.getGameTime();
         Vec3 eye = mc.player.getEyePosition();
         Set<BlockPos> protectedRouteSupports = this.routeSupports();
         List<BlockPos> nearby = this.excavationSpatialIndex.within(eye, 4.5);
         BlockPos queueTarget = this.excavationIndex < this.excavationTargets.size()
            ? this.excavationTargets.get(this.excavationIndex)
            : null;
         if (this.flattenPlan != null
            && this.flattenPlan.isTreeTarget(queueTarget)) {
            nearby.removeIf(candidate -> !this.flattenPlan.isSameTree(queueTarget, candidate));
         }
         if (!this.deferredFluidZone.isEmpty()) nearby.removeIf(this.deferredFluidZone::contains);
         if (!this.abandonedFluidZone.isEmpty()) nearby.removeIf(this.abandonedFluidZone::contains);
         nearby.removeIf(
            candidate -> !this.batchBreaks.canSubmit(candidate, gameTime)
               || !this.batchBreakSafeCandidate(mc, candidate)
               || !BatchMiningSafetyRules.allowsCandidate(
                  moving, candidate.getY(), stationaryLayerY, this.scaffoldPositions.contains(candidate), protectedRouteSupports.contains(candidate)
               )
         );
         nearby.sort(Comparator.comparingDouble(position -> eye.distanceToSqr(Vec3.atCenterOf(position))));
         if (nearby.isEmpty()) {
            return 0;
         }

         Set<BlockState> checkedStates = new HashSet<>();
         BlockPos sessionTarget = null;
         ItemStack currentTool = mc.player.getInventory().getSelectedItem();
         boolean currentToolSafe = !currentTool.isDamageableItem() || currentTool.getMaxDamage() - currentTool.getDamageValue() > config.durabilityReserve;
         if (currentToolSafe) {
            for (BlockPos candidate : nearby) {
               BlockState state = mc.level.getBlockState(candidate);
               if (BatchBreakExecutor.canBatchWithStack(mc, candidate, state, currentTool)) {
                  sessionTarget = candidate;
                  break;
               }
            }
         }

         if (sessionTarget == null) {
            for (BlockPos candidate : nearby) {
               BlockState state = mc.level.getBlockState(candidate);
               if (checkedStates.add(state) && BatchBreakExecutor.hasFastSafeHotbarTool(mc, candidate, state, config)) {
                  BatchBreakExecutor.Preparation preparation = this.batchBreaks.prepareTool(mc, candidate, state, config);
                  if (preparation == BatchBreakExecutor.Preparation.WAIT_FOR_TOOL) {
                     TerraLevelerDiagnostics.log("BATCH_BREAK", "bubble-tool-settle moving=" + moving + " target=" + candidate.toShortString());
                     return 0;
                  }

                  if (preparation == BatchBreakExecutor.Preparation.FAILED) {
                     TerraLevelerDiagnostics.log(
                        "BATCH_BREAK",
                        "bubble-tool-unavailable moving=" + moving + " target=" + candidate.toShortString() + " reason=" + this.batchBreaks.failure()
                     );
                     return 0;
                  }

                  if (preparation == BatchBreakExecutor.Preparation.READY) {
                     sessionTarget = candidate;
                     break;
                  }
               }
            }
         }

         if (sessionTarget == null) {
            return 0;
         }

         ItemStack held = mc.player.getInventory().getSelectedItem();
         int toolBudget = Math.min(sendBudget, BatchBreakExecutor.safeToolBreakBudget(held, config.durabilityReserve));
         if (toolBudget <= 0) {
            return 0;
         }

         nearby.removeIf(candidate -> {
            BlockState candidateState = mc.level.getBlockState(candidate);
            float progress = BatchBreakExecutor.effectiveBreakProgress(mc, candidate, candidateState, held);
            return !BatchBreakExecutor.isBatchProgress(progress);
         });
         if (nearby.isEmpty()) {
            return 0;
         }

         BatchBreakExecutor.SubmitResult result = this.batchBreaks.submit(mc, nearby, toolBudget);
         if (result.failed()) {
            TerraLevelerDiagnostics.log("BATCH_BREAK", "bubble-submit-failed moving=" + moving + " reason=" + result.failure());
            return 0;
         }

         if (result.sent() > 0) {
            TerraLevelerDiagnostics.log(
               "BATCH_BREAK",
               "bubble moving="
                  + moving
                  + " candidates="
                  + nearby.size()
                  + " sent="
                  + result.sent()
                  + " pending="
                  + result.pending()
                  + " session="
                  + sessionTarget.toShortString()
                  + " sessionBlock="
                  + BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(sessionTarget).getBlock())
            );
         }

         return result.sent();
      } else {
         return 0;
      }
   }

   private static boolean batchMiningEnabled(TerraLevelerConfig config) {
      return config != null && config.fastBreakBlocksPerTick > 0 && config.operationDelayTicks == 0;
   }

   private boolean batchBreakSafeCandidate(Minecraft mc, BlockPos candidate) {
      if (this.scaffoldPositions.contains(candidate)) {
         return false;
      }

      if (!mc.level.hasChunkAt(candidate) || !mc.level.getWorldBorder().isWithinBounds(candidate)) {
         return false;
      }

      if (mc.level.getBlockEntity(candidate) != null) {
         return false;
      }

      if (mc.player.blockActionRestricted(mc.level, candidate, mc.gameMode.getPlayerMode())) {
         return false;
      }

      BlockState state = mc.level.getBlockState(candidate);
      if (BatchMiningSafetyRules.allowsFallingColumnPosition(mc.level.getBlockState(candidate.above()).getBlock() instanceof FallingBlock)
         && state.getFluidState().isEmpty()) {
         for (Direction direction : Direction.values()) {
            if (!mc.level.getFluidState(candidate.relative(direction)).isEmpty()) {
               return false;
            }
         }

         return ExcavationPlanner.operationFor(this.classifyExcavation(mc, candidate)) != ExcavationPlanner.Operation.BREAK
            ? false
            : this.safeReach(mc, candidate) && !this.isBlockSupportingPlayer(mc, candidate);
      } else {
         return false;
      }
   }

   private Set<BlockPos> routeSupports() {
      return this.state == ConstructionController.State.POSITIONING && this.routeIndex < this.route.size()
         ? RouteExecutionRules.protectedMiningSupports(this.route, this.routeIndex)
         : Set.of();
   }

   private static String routePlacementSupportSnapshot(Minecraft mc, BlockPos scaffold) {
      StringBuilder snapshot = new StringBuilder("[");

      for (Direction direction : Direction.values()) {
         if (snapshot.length() > 1) {
            snapshot.append(',');
         }

         BlockPos neighbor = scaffold.relative(direction);
         snapshot.append(direction.getSerializedName()).append('=');
         if (!mc.level.hasChunkAt(neighbor)) {
            snapshot.append("unloaded");
         } else {
            BlockState state = mc.level.getBlockState(neighbor);
            snapshot.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            if (!state.getFluidState().isEmpty()) {
               snapshot.append("/fluid");
            }

            if (state.getCollisionShape(mc.level, neighbor).isEmpty()) {
               snapshot.append("/open");
            }
         }
      }

      return snapshot.append(']').toString();
   }

   private boolean verifyFlattenExcavationBeforeFill(Minecraft mc, TerraLevelerConfig config) {
      FlattenPlanner.Result result = FlattenPlanner.rescan(mc, this.flattenPlan, config.flattenProtectBlockEntities);
      if (!result.ready()) {
         this.pause(mc, "平地填充前复扫失败：" + TerraLeveler.flattenPlanProblem(result));
         return false;
      } else {
         FlattenPlan verified = result.plan();
         this.flattenPlan = verified;
         BlockPos origin = mc.player == null ? null : mc.player.blockPosition();
         FlattenExcavationScheduler.Result scheduled = this.scheduleFlattenExcavation(mc, verified, origin, "prefill-rescan");
         FlattenExcavationScheduler.Result foliage = this.scheduleFlattenFoliage(mc, verified, origin, "prefill-rescan");
         List<BlockPos> foliageCleanupTargets = this.cleanupFlattenFoliageTargets(
            foliage.targets(), config, "prefill-rescan"
         );
         this.wallTargets = this.fillTargetsOutsideAbandonedFluid(verified, origin);
         this.wallIndex = 0;
         this.deferredFoliageTargets.clear();
         this.promotedFoliageRoots.clear();
         this.foliageAccessExhausted.clear();
         this.deferredFoliageTargets.addAll(foliage.targets());
         if (scheduled.targets().isEmpty() && foliageCleanupTargets.isEmpty()) {
            TerraLevelerDiagnostics.log(
               "PLAN",
               "flatten-prefill-verified residualExcavation=0 fill="
                  + verified.estimatedFillBlocks()
                  + " excludedOwnedScaffolds="
                  + scheduled.excludedScaffolds()
            );
            return true;
         } else {
            Set<BlockPos> found = new HashSet<>(scheduled.targets().isEmpty() ? foliageCleanupTargets : scheduled.targets());
            if (found.equals(this.lastPrefillReplanTargets)) {
               // The same targets came back untouched: re-queuing them again would only loop the rescan.
               this.pause(mc, "填土前复扫连续两次发现同样的 " + found.size() + " 个目标且无法处理，例如 "
                  + found.iterator().next().toShortString() + "；已停止复扫循环");
               return false;
            }
            this.lastPrefillReplanTargets = found;
            this.cancelOwnedWork(mc, "flatten-prefill-verification-found-work");
            this.flattenFoliageCleanup = scheduled.targets().isEmpty();
            this.setExcavationTargets(new ArrayList<>(this.flattenFoliageCleanup ? foliageCleanupTargets : scheduled.targets()));
            this.deferredRouteTargets.clear();
            this.excavationIndex = 0;
            this.completed = 0L;
            this.state = ConstructionController.State.EXCAVATING;
            this.resumeState = this.state;
            this.detail = this.flattenFoliageCleanup
               ? "主体削高完成，开始清理未自然腐烂的残留树叶"
               : "填土前复扫发现残留或新出现的主体方块，继续优先清除";
            TerraLevelerDiagnostics.log(
               "PLAN",
                "flatten-prefill-replan targets="
                   + scheduled.targets().size()
                   + " deferredFoliage="
                   + foliage.targets().size()
                   + " cleanupFoliage="
                   + foliageCleanupTargets.size()
                  + " foliageCleanup="
                  + this.flattenFoliageCleanup
                  + " breakable="
                  + verified.estimatedBlocks()
                  + " fill="
                  + verified.estimatedFillBlocks()
            );
            return false;
         }
      }
   }

   private void tickFluid(Minecraft mc, TerraLevelerConfig config) {
      if (this.flattenFluidTransaction != null) {
         this.tickFlattenFluidDrain(mc, config);
      } else {
         this.state = this.fluidReturnState;
      }
   }

   private void beginFlattenFluidDrainTask(Minecraft mc, BlockPos target) {
      if (this.flattenPlan == null) {
         this.pause(mc, "平地排流体缺少有效施工范围");
         return;
      }

      FlattenFluidDrainPlanner.PlanResult result = FlattenFluidDrainPlanner.plan(
         this.flattenPlan, target, pos -> fluidCell(mc, pos), FLATTEN_FLUID_LIMITS
      );
      switch (result.status()) {
         case READY:
            Item dirt = Blocks.DIRT.asItem();
            int looseDirt = inventoryCount(mc, dirt);
            int boxedDirt = containedInventoryCount(mc, dirt);
            int requiredDirt = result.transaction().requiredDirt();
            if (!transactionMaterialAvailable(looseDirt + boxedDirt, requiredDirt)) {
               if (hasPotentialMaterialBox(mc, dirt)) {
                  this.beginMaterialUnpack(mc, ConstructionController.State.EXCAVATING, dirt, requiredDirt - looseDirt);
               } else {
                  this.pause(mc, "本次局部排流体需要 " + requiredDirt + " 个泥土，但散装只有 " + looseDirt
                     + " 个、潜影盒内只有 " + boxedDirt + " 个");
               }
               return;
            }

            this.cancelOwnedWork(mc, "flatten-fluid-drain-start");
            this.flattenFluidTransaction = this.withUpstreamSources(mc, result.transaction());
            this.unreachableFluidPlacements.clear();
            this.fluidPlacementRotations = 0;
            this.fluidRecedeWaitTicks = 0;
            this.fluidObstructedTicks = 0;
            this.fluidInletSettleTicks = 0;
            this.fluidSealExtensions = 0;
            this.resetFluidRecession();
            this.fluidActionIndex = 0;
            this.fluidVerifiedTicks = 0;
            this.fluidReflowAttempts = 0;
            this.fluidReturnState = ConstructionController.State.EXCAVATING;
            this.fluidAdvancesExcavation = false;
            this.state = ConstructionController.State.CONTAINING_FLUID;
            this.resumeState = this.state;
            this.detail = "开始局部排流体：" + this.flattenFluidTransaction.fluidCells().size()
               + " 格，预计使用 " + requiredDirt + " 个泥土";
            TerraLevelerDiagnostics.log(
               "FLUID",
               "flatten-drain-start target=" + target.toShortString()
                  + " cells=" + this.flattenFluidTransaction.fluidCells().size()
                  + " inlets=" + this.flattenFluidTransaction.fluidCells().stream().filter(pos -> this.fluidFeederAt(mc, pos)).count()
                  + " dirt=" + requiredDirt
                  + " lava=" + this.flattenFluidTransaction.containsLava()
            );
            break;
         case NOT_FLUID:
            this.state = ConstructionController.State.EXCAVATING;
            this.detail = "流体快照已经变化，返回当前施工目标重新分类";
            break;
         case WAIT_FOR_CHUNK:
            this.beginLoading(mc, result.problemPos(), ConstructionController.State.EXCAVATING);
            break;
         case EXTERNAL_INLET:
            this.pause(mc, "允许选区外围一格封水后仍有外部流体补入，局部排流体已停止 " + posText(result.problemPos()));
            break;
         case UNSUPPORTED_FLUID:
            this.pause(mc, "局部排流体遇到不支持的流体 " + posText(result.problemPos()));
            break;
         case NO_PLACEMENT_SUPPORT:
            this.pause(mc, "局部流体没有可用于放置泥土的相邻支撑面 " + posText(result.problemPos()));
            break;
         default:
            this.pause(mc, "平地排流体规划失败：" + result.status() + " " + posText(result.problemPos()));
      }
   }

   /**
    * Dirt placed into drained fluid cells inside the area is itself above the target
    * height. Cells whose queue position was already passed (water flowed in after they
    * were dug) used to wait for the pre-fill rescan; in the 0.8.71 run one such block at
    * Y=88 sent the player 145 steps around the outside ridge and then left it without a
    * route back. Cells with no fluid next to them are dug right away instead.
    */
   private void requeueDrainFillForExcavation(Minecraft mc, List<BlockPos> drainedCells) {
      if (drainedCells.isEmpty() || mc.level == null) return;
      List<BlockPos> requeue = new ArrayList<>();
      for (BlockPos cell : drainedCells) {
         if (!this.excavationSpatialIndex.within(Vec3.atCenterOf(cell), 0.1).contains(cell)) continue;
         if (ExcavationPlanner.operationFor(this.classifyExcavation(mc, cell)) != ExcavationPlanner.Operation.BREAK) continue;
         boolean fluidNeighbour = false;
         for (Direction direction : Direction.values()) {
            if (!mc.level.getFluidState(cell.relative(direction)).isEmpty()) {
               fluidNeighbour = true;
               break;
            }
         }
         if (!fluidNeighbour) requeue.add(cell.immutable());
      }
      if (requeue.isEmpty()) return;
      this.setExcavationTargets(requeueAtIndex(this.excavationTargets, this.excavationIndex, requeue));
      TerraLevelerDiagnostics.log("FLUID", "flatten-drain-fill-requeued cells=" + requeue.size() + " of=" + drainedCells.size()
         + " excavationIndex=" + this.excavationIndex);
   }

   /** {@code targets} with {@code requeue} (top-down) inserted at {@code index}; later duplicates are dropped. */
   static List<BlockPos> requeueAtIndex(List<BlockPos> targets, int index, List<BlockPos> requeue) {
      int split = Math.max(0, Math.min(index, targets.size()));
      Set<BlockPos> inserted = new LinkedHashSet<>();
      requeue.stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()).forEach(inserted::add);
      List<BlockPos> result = new ArrayList<>(targets.size() + inserted.size());
      result.addAll(targets.subList(0, split));
      result.addAll(inserted);
      for (int position = split; position < targets.size(); position++) {
         BlockPos target = targets.get(position);
         if (!inserted.contains(target)) result.add(target);
      }
      return result;
   }

   private void tickFlattenFluidDrain(Minecraft mc, TerraLevelerConfig config) {
      if (this.fluidActionIndex >= this.flattenFluidTransaction.placements().size()) {
         FlattenFluidDrainPlanner.VerificationResult verification = FlattenFluidDrainPlanner.verify(
            this.flattenFluidTransaction, pos -> fluidCell(mc, pos)
         );
         switch (verification.status()) {
            case VERIFIED_DRY:
               AutoNavigator.release(mc);
               if (++this.fluidVerifiedTicks < this.flattenFluidTransaction.verificationTicks()) {
                  this.detail = "局部排流体保持干燥，等待稳定确认 " + this.fluidVerifiedTicks
                     + "/" + this.flattenFluidTransaction.verificationTicks();
                  return;
               }

               int drained = this.flattenFluidTransaction.fluidCells().size();
               List<BlockPos> drainedCells = List.copyOf(this.flattenFluidTransaction.fluidCells());
               this.clearFluidTask();
               this.requeueDrainFillForExcavation(mc, drainedCells);
               this.fluidSnapshotSettleTicks = 0;
               this.state = ConstructionController.State.EXCAVATING;
               this.resumeState = this.state;
               this.detail = "局部排流体已确认完成，重新处理当前施工目标";
               TerraLevelerDiagnostics.log("FLUID", "flatten-drain-complete cells=" + drained + " excavationIndex=" + this.excavationIndex);
               break;
            case WAIT_FOR_CHUNK:
               this.beginLoading(mc, verification.problemPos(), ConstructionController.State.CONTAINING_FLUID);
               break;
            case EXTERNAL_INLET:
               if (this.settleOrExtendFlattenFluidSeal(mc, verification.problemPos())) {
                  break;
               }
               this.pause(mc, "选区外围一格封水带在验证期间重新进水，局部排流体已停止 " + posText(verification.problemPos()));
               break;
            case UNSUPPORTED_FLUID:
               this.pause(mc, "局部排流体验证遇到不支持的流体 " + posText(verification.problemPos()));
               break;
            case REFLOW:
               this.fluidVerifiedTicks = 0;
               if (++this.fluidReflowAttempts > this.flattenFluidTransaction.maxReflowAttempts()) {
                  this.pause(mc, "同一局部排流体事务连续回流，已停止且不会超过选区外围一格 " + posText(verification.problemPos()));
                  return;
               }

               this.actions.cancel(mc, "flatten-fluid-reflow-retry");
               this.clearBreak();
               this.fluidActionIndex = 0;
               this.detail = "局部流体回流，重放同一有界事务 " + this.fluidReflowAttempts
                  + "/" + this.flattenFluidTransaction.maxReflowAttempts();
         }
         return;
      }

      if (this.waitForFlowingFluidToRecede(mc)) {
         return;
      }

      if (!this.prioritizeFlattenFluidPlacement(mc)) {
         if (this.deferFlattenFluidBody(mc, "no-supported-placement")) return;
         this.pause(mc, "局部排流体剩余放置均缺少已确认的相邻支撑面");
         return;
      }

      FlattenFluidDrainPlanner.Placement placement = this.flattenFluidTransaction.placements().get(this.fluidActionIndex);
      FluidCellKind current = fluidCell(mc, placement.target());
      if (current == FluidCellKind.UNLOADED) {
         this.beginLoading(mc, placement.target(), ConstructionController.State.CONTAINING_FLUID);
         return;
      }
      if (current == FluidCellKind.OTHER_FLUID) {
         this.pause(mc, "局部排流体遇到不支持的流体 " + placement.target().toShortString());
         return;
      }
      boolean targetStillFluid = current == FluidCellKind.WATER
         || current == FluidCellKind.LAVA;
      if (mayAdvancePastObservedDryFluidPlacement(targetStillFluid, this.actions.isIdle())) {
         this.fluidActionIndex++;
         this.detail = "排流体目标已由服务器更新为干燥方块，继续下一格";
         return;
      }

      FluidCellKind supportKind = fluidCell(mc, placement.support());
      if (supportKind == FluidCellKind.UNLOADED) {
         this.beginLoading(mc, placement.support(), ConstructionController.State.CONTAINING_FLUID);
         return;
      }
      if (!flattenDrainPlacementSupport(supportKind)) {
         BlockPos replannedTarget = placement.target();
         this.actions.cancel(mc, "flatten-fluid-support-changed");
         this.clearBreak();
         this.flattenFluidTransaction = null;
         this.fluidActionIndex = 0;
         this.fluidVerifiedTicks = 0;
         this.beginFlattenFluidDrainTask(mc, replannedTarget);
         return;
      }

      Item dirt = Blocks.DIRT.asItem();
      if (this.actions.isIdle() && hotbarCount(mc, dirt) <= 0 && !this.ensureHotbarMaterial(mc, dirt, 1)) {
         if (containedInventoryCount(mc, dirt) <= 0 && !hasPotentialMaterialBox(mc, dirt)) {
            this.pause(mc, "局部排流体仍需放置泥土，但散装泥土和潜影盒内泥土都已用完");
         } else {
            this.beginMaterialUnpack(mc, ConstructionController.State.CONTAINING_FLUID, dirt, 64);
         }
         return;
      }

      if (ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), placement.target())
         || !this.safeReach(mc, placement.support())) {
         this.beginWorkRoute(
            mc,
            placement.target(),
            placement.support(),
            ConstructionController.WorkAction.PLACE,
            ConstructionController.State.CONTAINING_FLUID,
            config,
            "前往干燥的局部排流体放置站位",
            this.fluidDeckY()
         );
         return;
      }
      if (this.waitForActionCooldown(mc) && this.actions.isIdle()) {
         return;
      }
      if (this.actions.isIdle() && this.handleObstructedFluidPlacement(mc, placement.target())) {
         return;
      }

      ServerActionExecutor.Status status = this.actions.tickPlace(
         mc, placement.target(), dirt, placement.support(), ServerActionExecutor.PlacementExpectation.FLUID_REMOVED
      );
      if (status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "放置泥土并等待服务器确认排流体 " + placement.target().toShortString()
            + "（" + (this.fluidActionIndex + 1) + "/" + this.flattenFluidTransaction.placements().size() + "）";
      } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
         this.beginWorkRoute(
            mc,
            placement.target(),
            placement.support(),
            ConstructionController.WorkAction.PLACE,
            ConstructionController.State.CONTAINING_FLUID,
            config,
            "放置排流体泥土时被碰撞移出触及距离，重新选择干燥站位",
            this.fluidDeckY()
         );
      } else if (status == ServerActionExecutor.Status.FAILED) {
         // 0.8.76 paused here (and again after every auto-retry) for one cell the client
         // refused three times; other cells come first and this one is tried again later.
         String failure = this.actionFailure("局部排流体泥土放置失败");
         if (!this.rotateOrDeferFlattenFluid(mc, "placement-failed")) this.pause(mc, failure);
      } else if (status != ServerActionExecutor.Status.SUCCESS) {
         this.pause(mc, "局部排流体泥土放置返回异常状态：" + status);
      } else {
         this.fluidActionIndex++;
         this.fluidPlacementRotations = 0;
         this.unreachableFluidPlacements.clear();
         this.fluidObstructedTicks = 0;
         this.startActionCooldown(config);
      }
   }

   /**
    * A drain placement that cannot be reached is moved behind the others so every other
    * frontier cell gets a try (with bridges, borrowing the temporary scaffold budget).
    * When none can be reached, the pocket is deferred if it is sealed.
    */
   private boolean rotateOrDeferFlattenFluid(Minecraft mc, String reason) {
      if (this.flattenFluidTransaction == null) return false;
      List<FlattenFluidDrainPlanner.Placement> placements = this.flattenFluidTransaction.placements();
      if (this.fluidActionIndex >= placements.size()) return false;
      BlockPos skipped = placements.get(this.fluidActionIndex).target();
      this.unreachableFluidPlacements.add(skipped.immutable());
      int open = 0;
      for (int index = this.fluidActionIndex; index < placements.size(); index++) {
         BlockPos target = placements.get(index).target();
         if (!this.unreachableFluidPlacements.contains(target) && stillFluid(fluidCell(mc, target))) open++;
      }
      if (open > 0) {
         this.fluidPlacementRotations++;
         this.actions.cancel(mc, "flatten-fluid-placement-rotated");
         this.clearRoute();
         this.state = ConstructionController.State.CONTAINING_FLUID;
         this.resumeState = this.state;
         this.routeRetryCooldownTicks = 2;
         TerraLevelerDiagnostics.log("FLUID", "flatten-drain-placement-rotated skipped=" + skipped.toShortString()
            + " reason=" + reason + " unreachable=" + this.unreachableFluidPlacements.size() + " stillOpen=" + open);
         return true;
      }
      return this.deferFlattenFluidBody(mc, reason);
   }

   /**
    * Puts off a fluid pocket that cannot be reached: the remaining fluid cells plus a
    * {@link #FLUID_ZONE_SHELL}-block shell move to the end of the excavation queue and are
    * kept out of batch mining, so the rest of the job continues around it. Only a sealed
    * pocket (no fluid cell next to open air beside or below it) is deferred; flowing fluid
    * still pauses. At the end the pocket is drained again; failing a second time pauses.
    */
   private boolean deferFlattenFluidBody(Minecraft mc, String reason) {
      if (this.flattenFluidTransaction == null || mc.level == null) return false;
      List<BlockPos> remainingFluid = new ArrayList<>();
      for (BlockPos cell : this.flattenFluidTransaction.fluidCells()) {
         FluidCellKind kind = fluidCell(mc, cell);
         if (kind == FluidCellKind.WATER || kind == FluidCellKind.LAVA) remainingFluid.add(cell);
      }
      if (remainingFluid.isEmpty()) return false;
      BlockPos flowing = null;
      for (BlockPos cell : remainingFluid) {
         for (Direction direction : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos neighbour = cell.relative(direction);
            FluidCellKind neighbourKind = fluidCell(mc, neighbour);
            if (neighbourKind == FluidCellKind.AIR || neighbourKind == FluidCellKind.FALL_THROUGH) {
               flowing = neighbour;
               break;
            }
         }
         if (flowing != null) break;
      }
      boolean nothingElseLeft = deferredFluidOnlyWorkRemains(this.excavationTargets, this.excavationIndex,
         pos -> this.deferredFluidZone.contains(pos) || inFluidShell(remainingFluid, pos));
      if (flowing == null && this.deferredFluidRevisit && nothingElseLeft) {
         return this.abandonFlattenFluidBody(mc, reason, remainingFluid);
      }
      if (flowing != null || this.deferredFluidRevisit && nothingElseLeft) {
         TerraLevelerDiagnostics.log("FLUID", "flatten-drain-defer-refused reason=" + reason + " flowingInto=" + posText(flowing)
            + " remaining=" + remainingFluid.size() + " revisit=" + this.deferredFluidRevisit + " lava=" + this.flattenFluidTransaction.containsLava());
         return false;
      }
      Set<BlockPos> zone = fluidZone(remainingFluid, FLUID_ZONE_SHELL);
      this.deferredFluidZone.addAll(zone);
      List<BlockPos> reordered = deferZoneTargets(this.excavationTargets, this.excavationIndex, this.deferredFluidZone::contains);
      boolean containsLava = this.flattenFluidTransaction.containsLava();
      this.actions.cancel(mc, "flatten-fluid-deferred");
      this.clearBreak();
      this.clearRoute();
      this.clearFluidTask();
      this.fluidPlacementRotations = 0;
      this.setExcavationTargets(reordered);
      this.clearRouteFailures();
      this.fluidSnapshotSettleTicks = 0;
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = this.state;
      this.detail = "流体腔暂时够不着，已封住并延后到最后处理：" + remainingFluid.getFirst().toShortString();
      TerraLevelerDiagnostics.log("FLUID", "flatten-drain-deferred reason=" + reason + " remainingFluid=" + remainingFluid.size()
         + " zone=" + zone.size() + " lava=" + containsLava + " first=" + remainingFluid.getFirst().toShortString()
         + " excavationIndex=" + this.excavationIndex + " remainingTargets=" + (reordered.size() - this.excavationIndex));
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：" + (containsLava ? "岩浆" : "水") + "腔（"
            + remainingFluid.getFirst().toShortString() + " 附近 " + remainingFluid.size()
            + " 格）暂时够不着，已延后，先施工其余部分，最后再回来处理。"));
      }
      return true;
   }

   /**
    * A sealed fluid pocket that is still out of reach when the job comes back to it at the end is
    * left in place: its cells and the shell around them are skipped and the job finishes. In the
    * 0.8.93 test the last 21 of 309321 blocks were a 7-cell line of water at Y=63 whose dry stands
    * were cut off by the water around them; the job paused on it 15 times and never finished.
    */
   private boolean abandonFlattenFluidBody(Minecraft mc, String reason, List<BlockPos> remainingFluid) {
      Set<BlockPos> zone = fluidZone(remainingFluid, FLUID_ZONE_SHELL);
      this.abandonedFluidZone.addAll(zone);
      List<BlockPos> kept = new ArrayList<>(this.excavationTargets.subList(0, Math.min(this.excavationIndex, this.excavationTargets.size())));
      int skipped = 0;
      for (int index = this.excavationIndex; index < this.excavationTargets.size(); index++) {
         BlockPos target = this.excavationTargets.get(index);
         if (this.abandonedFluidZone.contains(target)) {
            skipped++;
         } else {
            kept.add(target);
         }
      }
      boolean containsLava = this.flattenFluidTransaction.containsLava();
      this.actions.cancel(mc, "flatten-fluid-abandoned");
      this.clearBreak();
      this.clearRoute();
      this.clearFluidTask();
      this.fluidPlacementRotations = 0;
      this.setExcavationTargets(kept);
      this.clearRouteFailures();
      this.fluidSnapshotSettleTicks = 0;
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = this.state;
      BlockPos first = remainingFluid.getFirst();
      this.detail = "两次都够不着的封闭" + (containsLava ? "岩浆" : "水") + "已留下，继续收尾：" + first.toShortString();
      TerraLevelerDiagnostics.log("FLUID", "flatten-drain-abandoned reason=" + reason + " remainingFluid=" + remainingFluid.size()
         + " zone=" + zone.size() + " skippedTargets=" + skipped + " lava=" + containsLava + " first=" + first.toShortString());
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：" + (containsLava ? "岩浆" : "水") + "（" + first.toShortString()
            + " 附近 " + remainingFluid.size() + " 格）两次都够不着，已留下不处理，继续收尾；之后可以手动用泥土填掉。"));
      }
      return true;
   }

   private static boolean inFluidShell(List<BlockPos> fluid, BlockPos pos) {
      for (BlockPos cell : fluid) {
         if (Math.abs(cell.getX() - pos.getX()) <= FLUID_ZONE_SHELL && Math.abs(cell.getY() - pos.getY()) <= FLUID_ZONE_SHELL
            && Math.abs(cell.getZ() - pos.getZ()) <= FLUID_ZONE_SHELL) return true;
      }
      return false;
   }

   /** The fluid cells and every cell within {@code shell} blocks of them (cube neighbourhood). */
   static Set<BlockPos> fluidZone(java.util.Collection<BlockPos> fluid, int shell) {
      Set<BlockPos> zone = new HashSet<>();
      for (BlockPos cell : fluid) {
         for (int dx = -shell; dx <= shell; dx++) {
            for (int dy = -shell; dy <= shell; dy++) {
               for (int dz = -shell; dz <= shell; dz++) {
                  zone.add(cell.offset(dx, dy, dz).immutable());
               }
            }
         }
      }
      return zone;
   }

   /** Targets from {@code index} with the deferred ones moved, in order, behind all others. */
   static List<BlockPos> deferZoneTargets(List<BlockPos> targets, int index, java.util.function.Predicate<BlockPos> deferred) {
      int split = Math.max(0, Math.min(index, targets.size()));
      List<BlockPos> result = new ArrayList<>(targets.size());
      List<BlockPos> tail = new ArrayList<>();
      result.addAll(targets.subList(0, split));
      for (int position = split; position < targets.size(); position++) {
         BlockPos target = targets.get(position);
         if (deferred.test(target)) {
            tail.add(target);
         } else {
            result.add(target);
         }
      }
      result.addAll(tail);
      return result;
   }

   /** True when every target from {@code index} on lies in deferred fluid zones. */
   static boolean deferredFluidOnlyWorkRemains(List<BlockPos> targets, int index, java.util.function.Predicate<BlockPos> deferred) {
      for (int position = Math.max(0, index); position < targets.size(); position++) {
         if (!deferred.test(targets.get(position))) return false;
      }
      return true;
   }

   private boolean prioritizeFlattenFluidPlacement(Minecraft mc) {
      List<FlattenFluidDrainPlanner.Placement> placements = this.flattenFluidTransaction.placements();
      if (!this.actions.isIdle()) {
         return true;
      }
      FlattenFluidDrainPlanner.Placement currentPlacement = placements.get(this.fluidActionIndex);
      FluidCellKind currentKind = fluidCell(mc, currentPlacement.target());
      if (currentKind != FluidCellKind.WATER && currentKind != FluidCellKind.LAVA) {
         return true;
      }
      int selected = FlattenFluidExecutionRules.selectNextPlacement(
         this.fluidActionIndex,
         placements.size(),
         index -> flattenDrainPlacementSupport(fluidCell(mc, placements.get(index).support())),
         index -> this.unreachableFluidPlacements.contains(placements.get(index).target())
            || !stillFluid(fluidCell(mc, placements.get(index).target())),
         index -> this.fluidFeederAt(mc, placements.get(index).target()),
         index -> {
            FlattenFluidDrainPlanner.Placement candidate = placements.get(index);
            return !ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), candidate.target())
               && this.safeReach(mc, candidate.support());
         },
         index -> placements.get(index).target().getY(),
         index -> mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(placements.get(index).support()))
      );
      if (selected < 0) {
         return false;
      }
      if (selected == this.fluidActionIndex) {
         return true;
      }

      List<FlattenFluidDrainPlanner.Placement> reordered = new ArrayList<>(placements);
      Collections.swap(reordered, this.fluidActionIndex, selected);
      this.flattenFluidTransaction = new FlattenFluidDrainPlanner.DrainPlan(
         this.flattenFluidTransaction.fluidCells(),
         reordered,
         this.flattenFluidTransaction.externalWatchCells(),
         this.flattenFluidTransaction.verificationTicks(),
         this.flattenFluidTransaction.maxReflowAttempts(),
         this.flattenFluidTransaction.containsLava()
      );
      FlattenFluidDrainPlanner.Placement chosen = reordered.get(this.fluidActionIndex);
      TerraLevelerDiagnostics.log(
         "FLUID",
         "flatten-drain-frontier-selected fromIndex="
            + selected
            + " toIndex="
            + this.fluidActionIndex
            + " target="
            + chosen.target().toShortString()
            + " support="
            + chosen.support().toShortString()
            + " direct="
            + (!ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), chosen.target())
               && this.safeReach(mc, chosen.support()))
      );
      return true;
   }

   private static boolean stillFluid(FluidCellKind kind) {
      return kind == FluidCellKind.WATER || kind == FluidCellKind.LAVA;
   }

   private static boolean fluidSourceAt(Minecraft mc, BlockPos pos) {
      var fluid = mc.level.getFluidState(pos);
      return !fluid.isEmpty() && fluid.isSource();
   }

   private void resetFluidRecession() {
      this.fluidRecedeWaitTicks = 0;
      this.fluidRecedeDone = false;
      this.fluidRecedeLastWet = Integer.MAX_VALUE;
      this.fluidRecedeStallTicks = 0;
   }

   private FluidSourceTracer.Probe liveFluidProbe(Minecraft mc) {
      return new FluidSourceTracer.Probe() {
         @Override
         public boolean loaded(BlockPos position) {
            return mc.level.hasChunkAt(position);
         }

         @Override
         public int amount(BlockPos position) {
            BlockState state = mc.level.getBlockState(position);
            if (!(FluidBlocks.isPureFluid(state)) || !state.getFluidState().is(FluidTags.WATER)) return 0;
            return state.getFluidState().getAmount();
         }

         @Override
         public boolean source(BlockPos position) {
            BlockState state = mc.level.getBlockState(position);
            return FluidBlocks.isPureFluid(state) && state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource();
         }

         @Override
         public boolean support(BlockPos position) {
            return mc.level.hasChunkAt(position) && flattenDrainPlacementSupport(fluidCell(mc, position));
         }
      };
   }

   /**
    * Water entering through the seal ring is followed upstream outside the selection; when
    * it comes from a few source blocks (a spring), plugging them is added to the transaction
    * and done first, so the whole stream dries up instead of being dammed cell by cell.
    */
   private FlattenFluidDrainPlanner.DrainPlan withUpstreamSources(Minecraft mc, FlattenFluidDrainPlanner.DrainPlan plan) {
      if (plan.containsLava()) return plan;
      WorkArea area = this.workArea();
      List<BlockPos> inlets = plan.fluidCells().stream()
         .filter(pos -> !area.containsColumn(pos.getX(), pos.getZ()))
         .filter(pos -> stillFluid(fluidCell(mc, pos)))
         .toList();
      if (inlets.isEmpty()) return plan;
      return this.addUpstreamSources(mc, plan, inlets, List.of(), "start");
   }

   private FlattenFluidDrainPlanner.DrainPlan addUpstreamSources(
      Minecraft mc, FlattenFluidDrainPlanner.DrainPlan plan, List<BlockPos> starts, List<FlattenFluidDrainPlanner.Placement> extra, String phase
   ) {
      WorkArea area = this.workArea();
      FluidSourceTracer.Result trace = FluidSourceTracer.trace(
         starts, this.liveFluidProbe(mc), pos -> !area.containsColumn(pos.getX(), pos.getZ())
      );
      Set<BlockPos> planned = new HashSet<>();
      plan.placements().forEach(placement -> planned.add(placement.target()));
      List<FlattenFluidDrainPlanner.Placement> added = new ArrayList<>();
      if (trace.found()) {
         for (FlattenFluidDrainPlanner.Placement placement : trace.placements()) {
            if (planned.add(placement.target())) added.add(placement);
         }
      }
      for (FlattenFluidDrainPlanner.Placement placement : extra) {
         if (planned.add(placement.target())) added.add(placement);
      }
      TerraLevelerDiagnostics.log(
         "FLUID",
         "flatten-drain-source-trace phase=" + phase + " status=" + trace.status()
            + " sources=" + trace.sources().stream().map(BlockPos::toShortString).toList()
            + " visited=" + trace.visited() + " added=" + added.stream().map(p -> p.target().toShortString()).toList()
      );
      if (added.isEmpty()) return plan;
      List<FlattenFluidDrainPlanner.Placement> placements = new ArrayList<>(plan.placements());
      placements.addAll(added);
      List<BlockPos> cells = new ArrayList<>(plan.fluidCells());
      Set<BlockPos> addedTargets = new HashSet<>();
      for (FlattenFluidDrainPlanner.Placement placement : added) {
         addedTargets.add(placement.target());
         if (!cells.contains(placement.target())) cells.add(placement.target());
      }
      List<BlockPos> watch = plan.externalWatchCells().stream().filter(pos -> !addedTargets.contains(pos)).toList();
      return new FlattenFluidDrainPlanner.DrainPlan(cells, placements, watch, plan.verificationTicks(), plan.maxReflowAttempts(), plan.containsLava());
   }

   /**
    * Water showed up in the seal ring during verification. Flowing water left over after
    * the sources were plugged gets time to drain; water that keeps coming is traced to its
    * sources again and the ring cell itself is sealed. 0.8.77 paused here once the stream,
    * dammed where it entered, spilled in one block further along.
    */
   private boolean settleOrExtendFlattenFluidSeal(Minecraft mc, BlockPos inlet) {
      if (inlet == null) return false;
      if (!fluidSourceAt(mc, inlet) && this.fluidInletSettleTicks++ < FLUID_INLET_SETTLE_TICKS) {
         AutoNavigator.release(mc);
         this.fluidVerifiedTicks = 0;
         this.detail = "选区外围还有残留流水，等待它退去（" + this.fluidInletSettleTicks + "/" + FLUID_INLET_SETTLE_TICKS + "）";
         return true;
      }
      this.fluidInletSettleTicks = 0;
      if (this.fluidSealExtensions >= MAX_FLUID_SEAL_EXTENSIONS) return false;
      List<FlattenFluidDrainPlanner.Placement> extra = new ArrayList<>();
      Set<BlockPos> planned = new HashSet<>();
      this.flattenFluidTransaction.placements().forEach(placement -> planned.add(placement.target()));
      BlockPos support = FluidSourceTracer.supportFor(inlet, planned, this.liveFluidProbe(mc));
      if (support != null) extra.add(new FlattenFluidDrainPlanner.Placement(inlet, support));
      int before = this.flattenFluidTransaction.placements().size();
      this.flattenFluidTransaction = this.addUpstreamSources(mc, this.flattenFluidTransaction, List.of(inlet), extra, "verify");
      int added = this.flattenFluidTransaction.placements().size() - before;
      if (added <= 0) return false;
      this.fluidSealExtensions++;
      this.fluidVerifiedTicks = 0;
      this.resetFluidRecession();
      this.unreachableFluidPlacements.clear();
      this.detail = "外围又有水流进来，追加封堵 " + added + " 格（第 " + this.fluidSealExtensions + " 次）";
      TerraLevelerDiagnostics.log("FLUID", "flatten-drain-seal-extended inlet=" + inlet.toShortString() + " added=" + added
         + " extension=" + this.fluidSealExtensions + " placements=" + this.flattenFluidTransaction.placements().size());
      return true;
   }

   /**
    * A cell that keeps the pocket filled: a source block, or fluid in the one-block seal
    * ring outside the selection, through which water from outside flows in. 0.8.76 found
    * no source inside a stream fed from -196,84,-496 (six blocks outside) and waited three
    * seconds after every single placement for water that could never recede.
    */
   private boolean fluidFeederAt(Minecraft mc, BlockPos pos) {
      if (fluidSourceAt(mc, pos)) return true;
      return stillFluid(fluidCell(mc, pos)) && !this.workArea().containsColumn(pos.getX(), pos.getZ());
   }

   /**
    * Once every source of the pocket is filled, the flowing cells drain on their own
    * within a few seconds; filling them would only cost time and dirt.
    */
   private boolean waitForFlowingFluidToRecede(Minecraft mc) {
      if (this.fluidRecedeDone || !this.actions.isIdle()) return false;
      List<FlattenFluidDrainPlanner.Placement> placements = this.flattenFluidTransaction.placements();
      int wet = 0;
      for (int index = this.fluidActionIndex; index < placements.size(); index++) {
         BlockPos target = placements.get(index).target();
         if (!stillFluid(fluidCell(mc, target))) continue;
         if (this.fluidFeederAt(mc, target)) return false;
         wet++;
      }
      this.fluidRecedeStallTicks = wet < this.fluidRecedeLastWet ? 0 : this.fluidRecedeStallTicks + 1;
      this.fluidRecedeLastWet = wet;
      if (!FlattenFluidExecutionRules.shouldKeepWaitingForRecession(
         false, wet, this.fluidRecedeWaitTicks, this.fluidRecedeStallTicks, FLUID_RECEDE_MAX_TICKS, FLUID_RECEDE_STALL_TICKS
      )) {
         if (this.fluidRecedeWaitTicks > 0) {
            TerraLevelerDiagnostics.log("FLUID", "flatten-drain-recession-finished waitedTicks=" + this.fluidRecedeWaitTicks + " wetLeft=" + wet);
         }
         this.fluidRecedeDone = true;
         return false;
      }
      if (this.fluidRecedeWaitTicks++ == 0) {
         TerraLevelerDiagnostics.log("FLUID", "flatten-drain-sources-filled waiting-for-recession wet=" + wet + " maxTicks=" + FLUID_RECEDE_MAX_TICKS);
      }
      AutoNavigator.release(mc);
      this.detail = "水源和入水口已堵住，等待流水自行退去（还剩 " + wet + " 格有水，已等 " + this.fluidRecedeWaitTicks + " 刻）";
      return true;
   }

   /**
    * An entity in the cell (a fish or mob carried by the stream) makes the client refuse the
    * placement outright: 0.8.76 got three client-side Fail results at -179,77,-486 and paused.
    * Other open cells are filled first; with none left, wait up to ten seconds for it to move.
    */
   private boolean handleObstructedFluidPlacement(Minecraft mc, BlockPos target) {
      if (mc.level.isUnobstructed(Blocks.DIRT.defaultBlockState(), target, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
         this.fluidObstructedTicks = 0;
         return false;
      }
      List<String> blockers = mc.level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(target))
         .stream()
         .map(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())
         .distinct()
         .toList();
      String names = blockers.isEmpty() ? "实体" : String.join("、", blockers);
      if (this.fluidObstructedTicks++ == 0) {
         TerraLevelerDiagnostics.log("FLUID", "flatten-drain-placement-obstructed target=" + target.toShortString() + " entities=" + blockers);
      }
      List<FlattenFluidDrainPlanner.Placement> placements = this.flattenFluidTransaction.placements();
      for (int index = this.fluidActionIndex; index < placements.size(); index++) {
         FlattenFluidDrainPlanner.Placement other = placements.get(index);
         if (other.target().equals(target) || this.unreachableFluidPlacements.contains(other.target())) continue;
         if (stillFluid(fluidCell(mc, other.target())) && flattenDrainPlacementSupport(fluidCell(mc, other.support()))) {
            this.unreachableFluidPlacements.add(target.immutable());
            this.fluidObstructedTicks = 0;
            this.actions.cancel(mc, "flatten-fluid-placement-obstructed");
            this.clearRoute();
            return true;
         }
      }
      if (this.fluidObstructedTicks > FLUID_OBSTRUCTION_WAIT_TICKS) {
         this.pause(mc, "排流体位置 " + target.toShortString() + " 一直被" + names + "挡住，无法放置泥土");
         return true;
      }
      AutoNavigator.release(mc);
      this.detail = "等待挡住排流体位置的" + names + "离开 " + target.toShortString();
      return true;
   }

   static boolean mayAdvancePastObservedDryFluidPlacement(boolean targetStillFluid, boolean actionIdle) {
      return !targetStillFluid && actionIdle;
   }

   private void tickScaffoldCleanup(Minecraft mc, TerraLevelerConfig config) {
      if (this.resumeOrTickFinalScaffoldPillar(mc, config, ConstructionController.State.CLEANING_SCAFFOLDS,
         "最后高空脚手临时柱事务中断后，无法重新走回已预检柱底")) {
         return;
      }

      if (this.pendingFlattenCleanupReplan != null) {
         this.tickPendingFlattenCleanupReplan(mc, config);
         return;
      }

      if (this.breakPurpose == ConstructionController.BreakPurpose.NONE
         && this.actions.isIdle()
         && RouteExecutionRules.shouldBeginFlattenCleanupRouteReturn(
            this.flattenFinalCleanupPass, this.flattenCleanupRouteScaffolds.size(), this.flattenCleanupRouteReturnPending
         )
         && this.flattenCleanupRouteReturnAnchor != null) {
         this.beginFlattenCleanupRouteReturn(mc);
      } else if (this.breakPurpose == ConstructionController.BreakPurpose.SCAFFOLD_CLEANUP) {
         DestructiveSupportDropPlanner.Result supportDrop = this.scaffoldCleanupDrop;
         BlockPos supportDropStart = this.flattenCleanupFallStart;
         if (!this.handleBlockingAnimal(mc, config, this.breakTarget, "scaffold-break")) {
            ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, this.breakTarget, config);
            if (status != null) {
               if (status == ServerActionExecutor.Status.RUNNING) {
                  this.detail = "清理临时脚手 " + this.breakTarget.toShortString();
               } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                  if (supportDrop != null) {
                     this.pause(mc, "最终脚下脚手自拆时失去正常触及距离；保留脚手账本并等待自动重试");
                  } else {
                     this.repositionInterruptedBreak(mc, config, ConstructionController.State.CLEANING_SCAFFOLDS, "清理临时脚手时被碰撞移出触及距离，重新选择安全站位");
                  }
               } else if (status == ServerActionExecutor.Status.FAILED) {
                  this.pause(mc, this.actionFailure("临时脚手清理失败"));
               } else {
                  BlockPos removed = this.breakTarget;
                  this.clearBreak();
                  if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
                     this.pause(mc, "脚手清理返回异常状态 " + status);
                  } else {
                      this.forgetScaffold(removed);
                      this.scaffoldCleanupIndex++;
                      this.startActionCooldown(config);
                      if (supportDrop != null) {
                         this.fallRecoveryScaffoldCleanupTarget = removed.immutable();
                         this.fallRecoveryScaffoldCleanupDrop = supportDrop;
                         this.flattenCleanupFallStart = supportDropStart;
                         TerraLevelerDiagnostics.log(
                           "RECOVERY",
                           "final-scaffold-support-drop-confirmed target="
                              + removed.toShortString()
                              + " landing="
                              + supportDrop.landing().toShortString()
                              + " dropDistance="
                              + supportDrop.dropDistance()
                              + " landingType="
                              + supportDrop.status()
                        );
                        this.beginFallRecovery(
                           mc,
                           ConstructionController.State.CLEANING_SCAFFOLDS,
                           "服务器已确认最终脚下自有脚手拆除，等待自然坠落到预检落点 " + supportDrop.landing().toShortString()
                        );
                      } else {
                         this.continueOrReturnFlattenCleanupExcursion(mc, removed);
                      }
                  }
               }
            }
         }
      } else {
         while (this.scaffoldCleanupIndex < this.scaffoldCleanupTargets.size()) {
            BlockPos target = this.scaffoldCleanupTargets.get(this.scaffoldCleanupIndex);
            if (!mc.level.hasChunkAt(target)) {
               if (this.hasActiveFlattenCleanupExcursion()) {
                  this.returnFromFlattenCleanupExcursion(mc, target, "next-target-unloaded");
                  return;
               }
               this.beginLoading(mc, target, ConstructionController.State.CLEANING_SCAFFOLDS);
               return;
            }

            if (this.parkedCleanupTargets.containsKey(target) && this.isTrackedScaffoldBlock(mc, target) && !this.safeReach(mc, target)) {
               if (this.hasActiveFlattenCleanupExcursion()) {
                  this.returnFromFlattenCleanupExcursion(mc, target, "only-parked-targets-left");
                  return;
               }
               if (this.leaveParkedCleanupTargetsBehind(mc)) {
                  continue;
               }
            }

            if (this.isTrackedScaffoldBlock(mc, target)) {
                BlockPos currentAnchor = PlayerPhysicalState.routeAnchor(mc).orElse(null);
                if (this.isFlattenCleanupTopologyBlocked(target, currentAnchor, this.activeScaffoldCount())) {
                   if (this.beginFinalScaffoldPillarCleanup(mc, target, currentAnchor, "previous-deterministic-failure")) {
                      return;
                   }
                   if (!this.parkUnreachableCleanupTarget(mc, target, "previous-deterministic-failure")
                      && !this.deferBlockedFlattenCleanupTarget(mc, target, currentAnchor, "previous-deterministic-failure", false)) {
                      this.pause(mc, this.deterministicTopologyPauseReason(target, currentAnchor));
                   }
                  return;
               }

               if (this.safeReach(mc, target) && !this.isBlockSupportingPlayer(mc, target)) {
                  if (!this.validateDirectFlattenCleanupRemoval(mc, target)) {
                     return;
                  }
                  if (this.waitForActionCooldown(mc)) {
                     return;
                  }

                  this.breakPurpose = ConstructionController.BreakPurpose.SCAFFOLD_CLEANUP;
                  this.breakTarget = target;
                  this.tickScaffoldCleanup(mc, config);
                  return;
               }

               if (this.beginFinalScaffoldSupportDrop(mc, target)) {
                  if (this.waitForActionCooldown(mc)) {
                     return;
                  }

                  this.tickScaffoldCleanup(mc, config);
                  return;
               }

               if (this.beginClusterPillarCleanup(mc, target)) {
                  return;
               }

               this.beginWorkRoute(
                  mc, target, target, ConstructionController.WorkAction.BREAK, ConstructionController.State.CLEANING_SCAFFOLDS, config, "前往临时脚手清理站位"
               );
               return;
            }

            this.forgetScaffold(target);
            this.scaffoldCleanupIndex++;
            this.cancelMotionAndRoute(mc, "scaffold-already-gone");
            if (this.continueOrReturnFlattenCleanupExcursion(mc, target)) {
               return;
            }
         }

         this.beginWallPhase(mc);
      }
   }

   private boolean hasActiveFlattenCleanupExcursion() {
      return this.hasFlattenCleanupTransaction() && !this.routeIsFlattenCleanupReturn;
   }

   private boolean hasFlattenCleanupTransaction() {
      return RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass)
         && this.flattenCleanupRouteReturnAnchor != null
         && this.flattenCleanupRouteTask != null;
   }

   private int currentScaffoldLimit() {
      boolean committedCleanupRoute = this.routeReturnState == ConstructionController.State.CLEANING_SCAFFOLDS
         && this.hasActiveFlattenCleanupExcursion();
      int cleanupLimit = RouteExecutionRules.effectiveScaffoldLimit(
         this.jobScaffoldBudget, this.flattenCleanupReservedScaffoldPeak, committedCleanupRoute
      );
      int temporaryRouteLimit = this.route.isEmpty() ? 0 : this.routeReservedScaffoldBudget;
      return Math.max(cleanupLimit, temporaryRouteLimit);
   }

   private boolean isInActiveFlattenCleanupCluster(BlockPos target) {
      return this.hasActiveFlattenCleanupExcursion()
         && FlattenCleanupExcursionRules.mayContinue(
            this.flattenCleanupRouteTask,
            target,
            this.flattenCleanupRouteReturnAnchor,
            this.sameCleanupCluster(this.flattenCleanupRouteTask, target)
         );
   }

   private boolean sameCleanupCluster(BlockPos first, BlockPos second) {
      Integer firstCluster = this.scaffoldCleanupClusters.get(first);
      return firstCluster != null && firstCluster.equals(this.scaffoldCleanupClusters.get(second));
   }

   private boolean continueOrReturnFlattenCleanupExcursion(Minecraft mc, BlockPos completed) {
      if (!this.hasActiveFlattenCleanupExcursion()) {
         return false;
      }

      BlockPos next = this.scaffoldCleanupIndex < this.scaffoldCleanupTargets.size()
         ? this.scaffoldCleanupTargets.get(this.scaffoldCleanupIndex)
         : null;
      if (next != null && mc.level.hasChunkAt(next) && this.isInActiveFlattenCleanupCluster(next)) {
         this.flattenCleanupRouteReturnPending = false;
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-excursion-continue completed="
               + completed.toShortString()
               + " next="
               + next.toShortString()
               + " anchor="
               + this.flattenCleanupRouteReturnAnchor.toShortString()
               + " committedSteps="
               + this.flattenCleanupOutboundRoute.size()
               + " routeCreated="
               + this.flattenCleanupRouteScaffolds.size()
         );
         return false;
      }

      this.returnFromFlattenCleanupExcursion(
         mc,
         next,
         next == null ? "targets-complete" : !mc.level.hasChunkAt(next) ? "next-target-unloaded" : "cluster-complete"
      );
      return true;
   }

   private void returnFromFlattenCleanupExcursion(Minecraft mc, BlockPos next, String reason) {
      this.flattenCleanupRouteReturnPending = !this.flattenCleanupRouteScaffolds.isEmpty();
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-excursion-return reason="
            + reason
            + " initialTask="
            + posText(this.flattenCleanupRouteTask)
            + " next="
            + posText(next)
            + " anchor="
            + posText(this.flattenCleanupRouteReturnAnchor)
            + " committedSteps="
            + this.flattenCleanupOutboundRoute.size()
            + " routeCreated="
            + this.flattenCleanupRouteScaffolds.size()
      );
      this.beginFlattenCleanupRouteReturn(mc);
   }

   private boolean validateDirectFlattenCleanupRemoval(Minecraft mc, BlockPos target) {
      if (!this.hasActiveFlattenCleanupExcursion()) {
         return true;
      }

      SafeRoutePlanner.RoutePolicy base = SafeRoutePlanner.RoutePolicy.flatteningFloor();
      SafeRoutePlanner.RoutePolicy returnPolicy = new SafeRoutePlanner.RoutePolicy(
         false, base.interiorPolicy(), base.minimumFeetY(), 1
      );
      RouteTopologySimulator.Result topology = RouteTopologySimulator.validateRemoval(
         SafeRoutePlanner.routeEnvironment(mc),
         this.workArea(),
         this.flattenCleanupRouteReturnAnchor,
         this.flattenCleanupOutboundRoute,
         target,
         returnPolicy
      );
      if (topology.valid()) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-topology-direct-continuation-accepted task="
               + target.toShortString()
               + " committedSteps="
               + this.flattenCleanupOutboundRoute.size()
         );
         return true;
      }

      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-topology-direct-continuation-rejected status="
            + topology.status()
            + " conflict="
            + posText(topology.conflict())
            + " conflictCell="
            + topology.conflictCell()
            + " stepStatus="
            + topology.stepStatus()
            + " task="
            + target.toShortString()
      );
      BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(this.flattenCleanupRouteReturnAnchor);
      this.handleFinalCleanupTopologyFailure(mc, target, start, topology.status().name());
      return false;
   }

   private boolean beginFinalScaffoldSupportDrop(Minecraft mc, BlockPos target) {
      if (!FlattenCleanupExcursionRules.mayDropOwnedSupport(
         true,
         this.flattenFinalCleanupPass,
         this.scaffoldPositions.contains(target),
         this.isBlockSupportingPlayer(mc, target),
         this.safeReach(mc, target),
         this.hasFlattenCleanupTransaction()
      )) {
         return false;
      }

      DestructiveSupportDropPlanner.Result landing = DestructiveSupportDropPlanner.findLanding(
         this.supportDropEnvironment(mc),
         target,
         DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(
            mc.player.getHealth(), mc.player.getAbsorptionAmount()
         )
      );
      if (!landing.allowed()) {
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "final-scaffold-support-drop-rejected target="
               + target.toShortString()
               + " status="
               + landing.status()
               + " conflict="
               + posText(landing.conflict())
         );
         return false;
      }

      this.breakPurpose = ConstructionController.BreakPurpose.SCAFFOLD_CLEANUP;
      this.breakTarget = target.immutable();
      this.scaffoldCleanupDrop = landing;
      this.flattenCleanupFallStart = PlayerPhysicalState.routeAnchor(mc).orElse(mc.player.blockPosition()).immutable();
      this.detail = "当前清理目标是脚下自有脚手；准备直接拆除并自然坠落到 "
         + landing.landing().toShortString()
         + "，落差 "
         + landing.dropDistance();
      TerraLevelerDiagnostics.log(
         "RECOVERY",
         "final-scaffold-support-drop-authorized target="
            + target.toShortString()
            + " landing="
            + landing.landing().toShortString()
            + " dropDistance="
            + landing.dropDistance()
            + " landingType="
            + landing.status()
      );
      return true;
   }

   private void rescheduleFlattenCleanupAfterFall(
      BlockPos removed, DestructiveSupportDropPlanner.Result supportDrop, BlockPos landing
   ) {
      BlockPos previousAnchor = this.flattenCleanupRouteReturnAnchor;
      int routeCreated = this.flattenCleanupRouteScaffolds.size();
      List<BlockPos> primaryTargets = FlattenCleanupExcursionRules.remainingPrimaryTargets(
         this.scaffoldCleanupTargets,
         this.scaffoldCleanupIndex,
         Set.copyOf(this.scaffoldPositions),
         Set.copyOf(this.flattenCleanupRouteScaffolds)
      );
      FlattenScaffoldCleanupScheduler.Result schedule = FlattenScaffoldCleanupScheduler.schedule(
         primaryTargets, landing
      );
      this.scaffoldCleanupTargets = schedule.targets();
      this.scaffoldCleanupClusters = schedule.clusterIds();
      this.scaffoldCleanupIndex = 0;
      this.flattenCleanupRouteScaffolds.clear();
      this.flattenCleanupRouteRetireTarget = null;
      this.flattenCleanupRouteReturnAnchor = null;
      this.flattenCleanupRouteTask = null;
      this.flattenCleanupRouteReturnPending = false;
      this.routeIsFlattenCleanupReturn = false;
      this.flattenCleanupOutboundRoute.clear();
      this.flattenCleanupExcursionDeferredTargets.clear();
      this.flattenCleanupReservedScaffoldPeak = 0;
      this.resetFlattenCleanupTopologyPlanning();
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         (supportDrop == null
               ? "flatten-cleanup-excursion-rebased-after-unexpected-fall"
               : "flatten-cleanup-excursion-rebased-after-support-drop")
            + " removed="
            + posText(removed)
            + " landing="
            + landing.toShortString()
            + " expectedLanding="
            + (supportDrop == null ? "none" : supportDrop.landing().toShortString())
            + " oldAnchor="
            + posText(previousAnchor)
            + " preservedTargets="
            + schedule.targets().size()
            + " routeCreated="
            + routeCreated
            + " deferredAccessScaffolds="
            + routeCreated
            + " globalActive="
            + this.activeScaffoldCount()
            + " clusters="
            + schedule.clusterCount()
      );
   }

   private void handleFinalCleanupTopologyFailure(Minecraft mc, BlockPos task, BlockPos start, String status) {
      this.pendingFlattenCleanupReplan = null;
      if (this.beginFinalScaffoldPillarCleanup(mc, task, start, status)) {
         return;
      }
      this.flattenCleanupTopologyBlocks.put(
         task.immutable(), new FlattenCleanupTopologyBlock(start.immutable(), this.activeScaffoldCount())
      );
      boolean deferred = this.parkUnreachableCleanupTarget(mc, task, "topology-" + status)
         || this.deferFinalCleanupTopologyTarget(mc, task, start, status);
      if (FlattenCleanupExcursionRules.mayContinueAfterTopologyDeferral(
         deferred, this.hasActiveFlattenCleanupExcursion()
      )) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-topology-continue-current-transaction task="
               + task.toShortString()
               + " status="
               + status
               + " deferredCount="
               + this.flattenCleanupExcursionDeferredTargets.size()
               + " routeCreated="
               + this.flattenCleanupRouteScaffolds.size()
         );
         return;
      }

      if (this.flattenCleanupRouteReturnAnchor != null && !this.flattenCleanupRouteScaffolds.isEmpty()) {
         this.flattenCleanupRouteReturnPending = true;
         this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
         this.resumeState = this.state;
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-topology-return-current-transaction task="
               + task.toShortString()
               + " status="
               + status
               + " deferred="
               + deferred
               + " routeCreated="
               + this.flattenCleanupRouteScaffolds.size()
         );
         this.beginFlattenCleanupRouteReturn(mc);
         return;
      }

      if (this.flattenCleanupRouteReturnAnchor != null) {
         this.flattenCleanupRouteReturnAnchor = null;
         this.flattenCleanupRouteTask = null;
         this.flattenCleanupRouteReturnPending = false;
         this.flattenCleanupOutboundRoute.clear();
         this.flattenCleanupExcursionDeferredTargets.clear();
         this.flattenCleanupReservedScaffoldPeak = 0;
      }
      if (deferred) {
         return;
      }

      this.pause(mc, this.deterministicTopologyPauseReason(task, start) + "（" + status + "）");
   }

   /**
    * Puts an unreachable cleanup target, with the scaffolds touching it, behind every other
    * remaining target of this pass. 0.8.75 retried two scaffolds 31 blocks up three times
    * (about 40 s each) and paused with 29 easy scaffolds still left.
    */
   private boolean parkUnreachableCleanupTarget(Minecraft mc, BlockPos task, String reason) {
      if (task == null) return false;
      int index = this.scaffoldCleanupTargets.indexOf(task);
      if (index < 0 || index < this.scaffoldCleanupIndex) return false;
      for (int i = this.scaffoldCleanupIndex; i < this.scaffoldCleanupTargets.size(); i++) {
         BlockPos candidate = this.scaffoldCleanupTargets.get(i);
         boolean group = candidate.equals(task)
            || this.sameCleanupCluster(task, candidate)
               && Math.abs(candidate.getX() - task.getX()) <= 3
               && Math.abs(candidate.getY() - task.getY()) <= 3
               && Math.abs(candidate.getZ() - task.getZ()) <= 3;
         if (group) this.parkedCleanupTargets.put(candidate.immutable(), this.scaffoldRemovalSerial);
      }
      this.scaffoldCleanupTargets = List.copyOf(CleanupParkingRules.parkRemaining(
         this.scaffoldCleanupTargets, this.scaffoldCleanupIndex, this.parkedCleanupTargets.keySet()
      ));
      int open = CleanupParkingRules.openRemaining(this.scaffoldCleanupTargets, this.scaffoldCleanupIndex, this.parkedCleanupTargets.keySet());
      this.pendingFlattenCleanupReplan = null;
      this.clearRouteFailures();
      this.routeRetryCooldownTicks = 1;
      this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
      this.resumeState = this.state;
      this.detail = "脚手 " + task.toShortString() + " 暂时无法到达，先清理其余 " + open + " 个脚手，最后再试";
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-target-parked task=" + task.toShortString() + " reason=" + reason
            + " parked=" + this.parkedCleanupTargets.size() + " open=" + open + " active=" + this.activeScaffoldCount()
      );
      return true;
   }

   /**
    * Every remaining target is parked. They are tried again once if any scaffold was removed
    * since they were parked (that may have freed their pillar column); otherwise they stay
    * where they are, recorded in the ledger, and the job finishes instead of pausing.
    */
   private boolean leaveParkedCleanupTargetsBehind(Minecraft mc) {
      if (CleanupParkingRules.openRemaining(this.scaffoldCleanupTargets, this.scaffoldCleanupIndex, this.parkedCleanupTargets.keySet()) > 0) {
         // Another reordering brought a parked target forward: put it back and go on.
         this.scaffoldCleanupTargets = List.copyOf(CleanupParkingRules.parkRemaining(
            this.scaffoldCleanupTargets, this.scaffoldCleanupIndex, this.parkedCleanupTargets.keySet()
         ));
         return true;
      }
      long parkedAt = this.parkedCleanupTargets.values().stream().mapToLong(Long::longValue).min().orElse(this.scaffoldRemovalSerial);
      if (CleanupParkingRules.mayRetryParked(parkedAt, this.scaffoldRemovalSerial, this.parkedCleanupRetries)) {
         this.parkedCleanupRetries++;
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-parked-retry count=" + this.parkedCleanupTargets.size() + " retry=" + this.parkedCleanupRetries
               + " removedSinceParked=" + (this.scaffoldRemovalSerial - parkedAt)
         );
         this.parkedCleanupTargets.clear();
         this.clusterPillarRejected.clear();
         this.pillarBlockersPrioritized.clear();
         this.resetFlattenCleanupTopologyPlanning();
         this.clearRouteFailures();
         return false;
      }

      List<BlockPos> left = new ArrayList<>();
      for (int i = this.scaffoldCleanupIndex; i < this.scaffoldCleanupTargets.size(); i++) {
         BlockPos target = this.scaffoldCleanupTargets.get(i);
         if (this.scaffoldPositions.contains(target)) {
            this.leftBehindScaffolds.add(target.immutable());
            left.add(target);
         }
      }
      this.scaffoldCleanupIndex = this.scaffoldCleanupTargets.size();
      this.parkedCleanupTargets.clear();
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-left-behind count=" + left.size() + " positions="
            + left.stream().map(BlockPos::toShortString).toList()
      );
      if (!left.isEmpty() && mc.player != null) {
         String sample = String.join("；", left.stream().limit(6).map(BlockPos::toShortString).toList());
         mc.player.sendSystemMessage(Component.literal(
            "TerraLeveler：有 " + left.size() + " 个临时脚手无法到达，已留在原处（" + sample + (left.size() > 6 ? " 等" : "")
               + "）。它们仍记在脚手账本里，下次框选到这里时会再处理。"
         ));
      }
      return true;
   }

   private boolean hasScaffoldsLeftToClean() {
      for (BlockPos pos : this.scaffoldPositions) {
         if (!this.leftBehindScaffolds.contains(pos)) return true;
      }
      return false;
   }

   private boolean deferFinalCleanupTopologyTarget(Minecraft mc, BlockPos task, BlockPos start, String status) {
      return this.deferBlockedFlattenCleanupTarget(mc, task, start, status, this.hasActiveFlattenCleanupExcursion());
   }

   private boolean deferBlockedFlattenCleanupTarget(
      Minecraft mc, BlockPos task, BlockPos start, String status, boolean sameClusterOnly
   ) {
      if (task == null
         || start == null
         || this.scaffoldCleanupIndex >= this.scaffoldCleanupTargets.size() - 1
         || !task.equals(this.scaffoldCleanupTargets.get(this.scaffoldCleanupIndex))) {
         return false;
      }

      for (int index = this.scaffoldCleanupIndex + 1; index < this.scaffoldCleanupTargets.size(); index++) {
         BlockPos candidate = this.scaffoldCleanupTargets.get(index);
         if (!this.scaffoldPositions.contains(candidate)
             || this.parkedCleanupTargets.containsKey(candidate)
             || this.leftBehindScaffolds.contains(candidate)
             || sameClusterOnly && !this.sameCleanupCluster(task, candidate)
             || this.isFlattenCleanupTopologyBlocked(candidate, start, this.activeScaffoldCount())) {
            continue;
         }

         List<BlockPos> reordered;
         if (sameClusterOnly) {
            reordered = new ArrayList<>(this.scaffoldCleanupTargets);
            reordered.set(this.scaffoldCleanupIndex, candidate.immutable());
            reordered.set(index, task.immutable());
         } else {
            reordered = FlattenScaffoldCleanupScheduler.prioritizeCluster(
               this.scaffoldCleanupTargets, this.scaffoldCleanupIndex, this.scaffoldCleanupClusters, candidate
            );
         }
         this.scaffoldCleanupTargets = List.copyOf(reordered);
         this.flattenCleanupExcursionDeferredTargets.add(task.immutable());
          this.pendingFlattenCleanupReplan = null;
          this.routeRetryCooldownTicks = 1;
         this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
         this.resumeState = this.state;
         this.detail = sameClusterOnly
             ? "当前脚手的往返路线存在确定性冲突，下一 tick 改试同簇另一个脚手"
             : "当前脚手在相同世界状态下已确定失败，下一 tick 改试另一个脚手";
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            (sameClusterOnly
                  ? "flatten-cleanup-topology-target-deferred task="
                  : "flatten-cleanup-capacity-target-deferred task=")
               + task.toShortString()
               + " replacement="
               + candidate.toShortString()
               + " replacementOriginalIndex="
               + index
               + " status="
               + status
         );
         return true;
      }
      return false;
   }

   private boolean isFlattenCleanupTopologyBlocked(BlockPos task, BlockPos anchor, int activeScaffolds) {
      FlattenCleanupTopologyBlock block = this.flattenCleanupTopologyBlocks.get(task);
      return block != null && block.matches(anchor, activeScaffolds);
   }

   static boolean sameFlattenCleanupTopologyFailureState(
      BlockPos failedStart, int failedActiveScaffolds, BlockPos currentStart, int currentActiveScaffolds
   ) {
      return failedStart != null
         && failedStart.equals(currentStart)
         && failedActiveScaffolds == currentActiveScaffolds;
   }

   static boolean mayScheduleFlattenCleanupTopologyReplan(Set<BlockPos> forbiddenPlacements, BlockPos conflict) {
      return conflict != null
         && forbiddenPlacements != null
         && forbiddenPlacements.size() < MAX_FINAL_CLEANUP_TOPOLOGY_CONFLICTS
         && !forbiddenPlacements.contains(conflict);
   }

   private String deterministicTopologyPauseReason(BlockPos task, BlockPos anchor) {
      return "确定性拓扑阻塞：剩余脚手在当前起点、脚手账本下均无可逆清理路线；不会自动重复寻路。目标 "
         + posText(task)
         + "，起点 "
         + posText(anchor)
         + "，活动脚手 "
         + this.activeScaffoldCount();
   }

   private void tickPendingFlattenCleanupReplan(Minecraft mc, TerraLevelerConfig config) {
      PendingFlattenCleanupReplan pending = this.pendingFlattenCleanupReplan;
      this.pendingFlattenCleanupReplan = null;
      BlockPos currentAnchor = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      BlockPos currentTask = this.scaffoldCleanupIndex < this.scaffoldCleanupTargets.size()
         ? this.scaffoldCleanupTargets.get(this.scaffoldCleanupIndex)
         : null;
      boolean valid = currentAnchor != null
         && currentAnchor.equals(pending.start())
         && pending.activeScaffoldCount() == this.activeScaffoldCount()
         && pending.taskKey().equals(currentTask)
         && this.isTrackedScaffoldBlock(mc, pending.taskKey());
      if (!valid) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-topology-session-invalidated task="
               + pending.taskKey().toShortString()
               + " savedStart="
               + pending.start().toShortString()
               + " currentStart="
               + posText(currentAnchor)
               + " savedScaffolds="
               + pending.activeScaffoldCount()
               + " currentScaffolds="
               + this.activeScaffoldCount()
               + " currentTask="
               + posText(currentTask)
         );
         return;
      }

      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-topology-next-tick-replan task="
            + pending.taskKey().toShortString()
            + " candidate="
            + (pending.topologyReplanAttempt() + 1)
            + " forbiddenPlacements="
            + pending.forbiddenScaffoldPlacements().size()
      );
      this.beginRoute(
         mc,
         pending.taskKey(),
         pending.goals(),
         pending.returnState(),
         config,
         pending.reason(),
         pending.validateTaskAtGoal(),
         pending.forbiddenScaffoldPlacements(),
         pending.topologyReplanAttempt()
      );
   }

   private void resetFlattenCleanupTopologyPlanning() {
      this.pendingFlattenCleanupReplan = null;
      this.flattenCleanupTopologyBlocks.clear();
   }

   private void beginWallPhase(Minecraft mc) {
      this.cancelOwnedWork(mc, "scaffold-cleanup-complete");
      this.state = ConstructionController.State.BUILDING_WALL;
      this.resumeState = this.state;
      this.detail = "开始逐格确认地表方块";
      this.detail = "开始在目标 Y 沿 X/Z 连续铺设单层泥土/草方块地表";
      // The final rescan re-enters this phase for leftover cells; announce it once per job.
      if (mc.player != null && !this.fillPhaseAnnounced) {
         mc.player.sendSystemMessage(Component.literal("TerraLeveler：削高阶段完成，开始沿 X/Z 铺设单层泥土/草方块地表；不会灌满地下空洞或流体。"));
      }
      this.fillPhaseAnnounced = true;
   }

   private void tickWall(Minecraft mc, TerraLevelerConfig config) {
      BlockItem configuredWallBlockItem = blockItem(this.jobWallBlock);
      BlockItem wallBlockItem = configuredWallBlockItem;
      if (configuredWallBlockItem != null) {
         if (this.actions.isIdle() || !isFlattenSurfaceMaterial(this.flattenPlacementMaterial)) {
            this.flattenPlacementMaterial = preferredFlattenSurfaceMaterial(mc);
         }

         if (this.flattenPlacementMaterial instanceof BlockItem blockItem) {
            wallBlockItem = blockItem;
         }
      }

      if (wallBlockItem == null) {
         this.pause(mc, "地表方块 ID 不是可放置方块：" + this.jobWallBlock);
      } else {
         Block expectedWall = wallBlockItem.getBlock();
         if (this.flattenEscapeDestination != null) {
            this.tickFlattenEscape(mc, config);
         } else if (this.breakPurpose == ConstructionController.BreakPurpose.WALL_OBSTRUCTION) {
            if (config.flattenProtectBlockEntities && mc.level.getBlockEntity(this.breakTarget) != null) {
               this.pause(mc, "填充位置存在受保护的容器或方块实体 " + this.breakTarget.toShortString());
            } else if (!this.handleBlockingAnimal(mc, config, this.breakTarget, "wall-break")) {
               ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, this.breakTarget, config);
               if (status != null) {
                  if (status == ServerActionExecutor.Status.RUNNING) {
                     this.detail = "移除地表位置的错误方块 " + this.breakTarget.toShortString();
                  } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                     this.repositionInterruptedBreak(mc, config, ConstructionController.State.BUILDING_WALL, "清理地表位置时被碰撞移出触及距离，重新选择安全站位");
                  } else if (status == ServerActionExecutor.Status.FAILED) {
                     this.pause(mc, this.actionFailure("地表位置清理失败"));
                  } else {
                     BlockPos removed = this.breakTarget;
                     this.clearBreak();
                     if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
                        this.pause(mc, "地表位置清理返回异常状态 " + status);
                     } else {
                        this.forgetScaffold(removed);
                        this.startActionCooldown(config);
                     }
                  }
               }
            }
         } else {
            while (true) {
               BlockState current;
               BlockPos target;
               while (true) {
                  if (this.wallIndex >= this.wallTargets.size()) {
                     if (this.hasScaffoldsLeftToClean()) {
                        this.cancelOwnedWork(mc, "final-scaffold-cleanup");
                         this.flattenFinalCleanupPass++;
                         this.resetFlattenCleanupTopologyPlanning();
                          if (this.flattenFinalCleanupPass == 1) {
                            this.flattenCleanupRouteScaffolds.clear();
                            this.flattenCleanupRouteRetireTarget = null;
                            this.flattenCleanupRouteReturnAnchor = null;
                            this.flattenCleanupRouteTask = null;
                            this.flattenCleanupRouteReturnPending = false;
                             this.routeIsFlattenCleanupReturn = false;
                             this.flattenCleanupOutboundRoute.clear();
                             this.flattenCleanupExcursionDeferredTargets.clear();
                             this.flattenCleanupReservedScaffoldPeak = 0;
                         }

                        boolean finalCleanupBridgeAllowed = RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass);
                        this.scaffoldCleanupTargets = this.orderedScaffoldCleanupTargets(mc, "final-audit");
                        this.scaffoldCleanupIndex = 0;
                        this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
                        this.resumeState = this.state;
                        this.detail = "地表阶段结束；清理施工期间新增或残留的临时脚手";
                        TerraLevelerDiagnostics.log(
                           "SCAFFOLD",
                           "final-audit cleanup-required ownedScaffoldsRemaining="
                              + this.scaffoldPositions.size()
                              + " pass="
                              + this.flattenFinalCleanupPass
                              + " bridgeAllowed="
                              + finalCleanupBridgeAllowed
                        );
                        return;
                     }

                     if (this.discardPendingJunkBeforeCompletion(mc)) {
                        return;
                     }

                     if (!this.verifyFlattenCompletion(mc, config)) {
                        return;
                     }

                     TerraLevelerDiagnostics.log("SCAFFOLD", "final-audit ownedScaffoldsRemaining=0 placements=" + this.scaffoldsPlaced);
                     this.cancelOwnedWork(mc, "all-complete");
                     this.state = ConstructionController.State.COMPLETE;
                     if (this.islandRegionRun) {
                        this.detail = "岛屿当前区域已完成削高、地表和临时脚手清理";
                     } else {
                        this.detail = "平地机已完成削高、单层泥土/草方块地表和临时脚手清理";
                        mc.player.sendSystemMessage(Component.literal("TerraLeveler：平地施工已经完成。"));
                        TerraLevelerAlerts.finished(mc);
                     }

                     TerraLevelerDiagnostics.log(
                        "STATE",
                        "complete excavated="
                           + this.excavationIndex
                           + " walls="
                           + this.wallIndex
                           + " scaffoldPlacements="
                           + this.scaffoldsPlaced
                           + " activeScaffolds="
                           + this.activeScaffoldCount()
                     );
                     return;
                  }

                  if (this.actions.isIdle()) {
                     this.flattenPlacementMaterial = preferredFlattenSurfaceMaterial(mc);
                     wallBlockItem = (BlockItem)this.flattenPlacementMaterial;
                     expectedWall = wallBlockItem.getBlock();
                  }

                  target = this.wallTargets.get(this.wallIndex);
                  if (!mc.level.hasChunkAt(target)) {
                     this.beginLoading(mc, target, ConstructionController.State.BUILDING_WALL);
                     return;
                  }

                  current = mc.level.getBlockState(target);
                  if (!current.is(Blocks.BEDROCK)) {
                     if (FluidBlocks.isPureFluid(current)
                        || current.getCollisionShape(mc.level, target).isEmpty()) {
                        break;
                     }

                     if (config.flattenProtectBlockEntities && mc.level.getBlockEntity(target) != null) {
                        this.pause(mc, "填充位置已经出现受保护的容器或方块实体 " + target.toShortString());
                        return;
                     }

                     if (!current.is(Blocks.DIRT) && !current.is(Blocks.GRASS_BLOCK)) {
                        break;
                     }

                     this.flattenPlacementMaterial = null;
                     this.clearHotbarMaterialRetry();
                     this.advanceWall(mc, "flatten-surface-already-dirt-or-grass");
                  } else {
                     this.advanceWall(mc, "bedrock-foundation");
                  }
               }

               if (current.isAir() || current.getBlock() == expectedWall || FluidBlocks.isPureFluid(current)) {
                  if (target.getY() == this.flattenPlan.targetGroundY() && this.actions.isIdle()) {
                     int selectedIndex = FlattenRefillQueue.promoteNearestSupported(
                        this.wallTargets,
                        this.wallIndex,
                        mc.player.blockPosition(),
                        candidate -> candidate.getY() == this.flattenPlan.targetGroundY() && findFlattenSurfaceSupport(mc, candidate) != null
                     );
                     if (selectedIndex < 0) {
                        this.pause(mc, "剩余目标 Y 表面与任何可点击固体都不相邻；按单层表面模式不会向地下洞穴或流体灌泥土，请先提供一个起始支撑");
                        return;
                     }

                     if (selectedIndex != this.wallIndex) {
                        BlockPos promoted = this.wallTargets.get(this.wallIndex);
                        TerraLevelerDiagnostics.log(
                           "FLATTEN",
                           "surface-target-promoted target="
                              + promoted.toShortString()
                              + " fromIndex="
                              + selectedIndex
                              + " wall="
                              + this.wallIndex
                              + "/"
                              + this.wallTargets.size()
                        );
                        this.detail = "优先铺设人物附近且已有侧向支撑的目标 Y 表面 " + promoted.toShortString();
                        continue;
                     }
                  }

                  int looseWallMaterial = inventoryCount(mc, wallBlockItem);
                  int hotbarWallMaterial = hotbarCount(mc, wallBlockItem);
                  int wallStackSize = wallBlockItem.getDefaultMaxStackSize();
                  if (current.getBlock() != expectedWall
                     && this.actions.isIdle()
                     && hotbarWallMaterial <= wallStackSize / 2
                     && looseWallMaterial > hotbarWallMaterial) {
                     this.ensureHotbarMaterial(mc, wallBlockItem, Math.min(wallStackSize, looseWallMaterial));
                  }

                  int wallRestock = materialRestockAmount(looseWallMaterial, this.wallTargets.size() - this.wallIndex, wallStackSize) > 0
                     ? fillExtractionAmount(looseWallMaterial, this.wallTargets.size() - this.wallIndex, wallStackSize)
                     : 0;
                  if (current.getBlock() != expectedWall && this.actions.isIdle() && wallRestock > 0 && hasPotentialMaterialBox(mc, wallBlockItem)) {
                     this.beginMaterialUnpack(mc, ConstructionController.State.BUILDING_WALL, wallBlockItem, wallRestock);
                     return;
                  }

                  if (current.getBlock() != expectedWall && this.actions.isIdle() && !this.ensureHotbarMaterial(mc, wallBlockItem, 1)) {
                     int selectedAvailable = inventoryCount(mc, wallBlockItem);
                     int available = flattenSurfaceInventoryCount(mc);
                     if (selectedAvailable == 0 && available > 0) {
                        Item replacement = preferredFlattenSurfaceMaterial(mc);
                        TerraLevelerDiagnostics.log(
                           "MATERIAL",
                           "flatten-surface-material-switch exhausted="
                              + materialName(wallBlockItem)
                              + " replacement="
                              + materialName(replacement)
                              + " combinedLoose="
                              + available
                              + " wall="
                              + this.wallIndex
                              + "/"
                              + this.wallTargets.size()
                        );
                        this.flattenPlacementMaterial = replacement;
                        this.clearHotbarMaterialRetry();
                        continue;
                     }

                     if (available == 0 && this.beginFlattenDirtHarvest(mc, wallBlockItem)) {
                        return;
                     }

                     if (selectedAvailable > 0 && this.waitForHotbarMaterialRetry(mc, wallBlockItem)) {
                        return;
                     }

                     if (selectedAvailable > 0) {
                        this.pause(
                           mc,
                           "背包中有 "
                              + materialName(wallBlockItem)
                              + " 但连续 2 秒无法换入快捷栏；playerMenu="
                              + (mc.player.containerMenu == mc.player.inventoryMenu)
                              + " visibleContainer="
                              + (mc.screen instanceof AbstractContainerScreen)
                        );
                     } else {
                        this.pause(mc, "泥土/草方块不足；当前单层地表仍需约 " + (this.wallTargets.size() - this.wallIndex) + " 块，且选区内目标 Y 以上没有可安全回采的泥土");
                     }

                     return;
                  }

                  this.clearHotbarMaterialRetry();
                  BlockPos support = target.getY() == this.flattenPlan.targetGroundY()
                     ? findFlattenSurfaceSupport(mc, target)
                     : target.below();
                  if (stablePlacementSupport(mc, support)) {
                     if (!ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), target) && this.safeReach(mc, support)) {
                        if (this.waitForActionCooldown(mc) && this.actions.isIdle()) {
                           return;
                        }

                        if (this.actions.isIdle() && this.handlePlacementBlocker(mc, config, target)) {
                           return;
                        }

                        ServerActionExecutor.Status status = this.actions
                           .tickPlace(mc, target, wallBlockItem, support, ServerActionExecutor.PlacementExpectation.SOLID_BLOCK);
                        if (status == ServerActionExecutor.Status.RUNNING) {
                           this.detail = "放置并等待服务器确认地表方块 " + target.toShortString();
                           return;
                        }

                        if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                           this.beginWorkRoute(
                              mc,
                              target,
                              support,
                              ConstructionController.WorkAction.PLACE,
                              ConstructionController.State.BUILDING_WALL,
                              config,
                              "真实玩家挡住地表位置，重新选择站位"
                           );
                           return;
                        }

                        if (status == ServerActionExecutor.Status.FAILED) {
                           this.pause(mc, this.actionFailure("地表放置失败"));
                           return;
                        }

                        if (status != ServerActionExecutor.Status.SUCCESS) {
                           this.pause(mc, "地表放置返回异常状态 " + status);
                           return;
                        }

                        this.startActionCooldown(config);
                        this.clearHotbarMaterialRetry();
                        this.flattenPlacementMaterial = null;

                        this.advanceWall(mc, "server-confirmed-exact-wall-block");
                        return;
                     }

                     if (this.actions.isIdle() && this.promoteFillPlaceableHere(mc)) {
                        continue;
                     }

                     this.beginWorkRoute(
                        mc, target, support, ConstructionController.WorkAction.PLACE, ConstructionController.State.BUILDING_WALL, config, "离开地表目标并前往安全放置站位"
                     );
                     return;
                  }

                  if (FlattenRefillQueue.promotePendingSupport(this.wallTargets, this.wallIndex, support)) {
                     TerraLevelerDiagnostics.log(
                        "FLATTEN",
                        "support-promoted support=" + support.toShortString() + " beforeDependent=" + target.toShortString() + " index=" + this.wallIndex
                     );
                     this.detail = "先补回上层目标下方的计划内地表支撑 " + support.toShortString();
                     continue;
                  }

                  this.pause(mc, "目标 Y 表面没有可用的侧向或下方支撑 " + target.toShortString());
                  return;
               }

               if (this.safeReach(mc, target) && !this.isBlockSupportingPlayer(mc, target)) {
                  if (this.waitForActionCooldown(mc)) {
                     return;
                  }

                  this.breakPurpose = ConstructionController.BreakPurpose.WALL_OBSTRUCTION;
                  this.breakTarget = target;
                  this.tickWall(mc, config);
                  return;
               }

               this.beginWorkRoute(
                  mc, target, target, ConstructionController.WorkAction.BREAK, ConstructionController.State.BUILDING_WALL, config, "前往地表错误方块清理站位"
               );
               return;
            }
         }
      }
   }

   private boolean verifyFlattenCompletion(Minecraft mc, TerraLevelerConfig config) {
      FlattenPlanner.Result result = FlattenPlanner.rescan(mc, this.flattenPlan, config.flattenProtectBlockEntities);
      if (!result.ready()) {
         this.pause(mc, "平地最终复扫失败：" + TerraLeveler.flattenPlanProblem(result));
         return false;
      }

      FlattenPlan verified = result.plan();
      BlockPos origin = mc.player == null ? null : mc.player.blockPosition();
      FlattenExcavationScheduler.Result scheduled = this.scheduleFlattenExcavation(mc, verified, origin, "final-rescan");
      FlattenExcavationScheduler.Result foliage = this.scheduleFlattenFoliage(mc, verified, origin, "final-rescan");
      List<BlockPos> foliageCleanupTargets = this.cleanupFlattenFoliageTargets(
         foliage.targets(), config, "final-rescan"
      );
      List<BlockPos> residualFill = this.fillTargetsOutsideAbandonedFluid(verified, origin);
      if (scheduled.targets().isEmpty() && foliageCleanupTargets.isEmpty() && residualFill.isEmpty()) {
         return true;
      }
      Set<BlockPos> found = new HashSet<>(scheduled.targets());
      found.addAll(foliageCleanupTargets);
      found.addAll(residualFill);
      if (found.equals(this.lastFinalReplanTargets)) {
         this.pause(mc, "最终复扫连续两次发现同样的 " + found.size() + " 个目标且无法处理，例如 "
            + found.iterator().next().toShortString() + "；已停止复扫循环");
         return false;
      }
      this.lastFinalReplanTargets = found;

      this.cancelOwnedWork(mc, "flatten-final-verification-found-work");
      this.flattenPlan = verified;
      this.deferredFoliageTargets.clear();
      this.promotedFoliageRoots.clear();
      this.foliageAccessExhausted.clear();
      this.deferredFoliageTargets.addAll(foliage.targets());
      this.flattenFoliageCleanup = scheduled.targets().isEmpty();
      this.setExcavationTargets(new ArrayList<>(this.flattenFoliageCleanup ? foliageCleanupTargets : scheduled.targets()));
      this.wallTargets = new ArrayList<>(residualFill);
      this.deferredRouteTargets.clear();
      this.excavationIndex = 0;
      this.wallIndex = 0;
      this.completed = 0L;
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = this.state;
      this.detail = this.flattenFoliageCleanup
         ? "最终复扫发现残留树叶，清理完成后再结束施工"
         : "最终复扫发现地形变化，重新处理遗漏：挖掘 " + verified.estimatedBlocks() + "，泥土 " + verified.estimatedFillBlocks();
      TerraLevelerDiagnostics.log(
         "PLAN",
         "flatten-final-replan excavation="
            + scheduled.targets().size()
            + " deferredFoliage="
            + foliage.targets().size()
            + " cleanupFoliage="
            + foliageCleanupTargets.size()
            + " foliageCleanup="
            + this.flattenFoliageCleanup
            + " excludedOwnedScaffolds="
            + scheduled.excludedScaffolds()
            + " fill="
            + verified.estimatedFillBlocks()
      );
      return false;
   }

   private void beginWorkRoute(
      Minecraft mc,
      BlockPos taskTarget,
      BlockPos interactionTarget,
      ConstructionController.WorkAction action,
      ConstructionController.State returnState,
      TerraLevelerConfig config,
      String reason
   ) {
      this.beginWorkRoute(mc, taskTarget, interactionTarget, action, returnState, config, reason, Integer.MIN_VALUE);
   }

   private void beginWorkRoute(
      Minecraft mc,
      BlockPos taskTarget,
      BlockPos interactionTarget,
      ConstructionController.WorkAction action,
      ConstructionController.State returnState,
      TerraLevelerConfig config,
      String reason,
      int minimumPoseY
   ) {
      BlockPos currentAnchor = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      boolean canWorkHere = this.canPerformFromCurrentPlayer(mc, taskTarget, interactionTarget, action);
      List<BlockPos> poses = this.workPoses(mc, taskTarget, interactionTarget, action);
      if (minimumPoseY != Integer.MIN_VALUE) {
         poses.removeIf(pose -> !WorkPoseRules.fluidDeckPose(pose, minimumPoseY - 1));
      }

      if (!canWorkHere && currentAnchor != null) {
         poses.removeIf(currentAnchor::equals);
      }

      if (poses.isEmpty()) {
         if (returnState == ConstructionController.State.EXCAVATING
            && this.promoteFoliageAccessTarget(mc, taskTarget, "no-safe-work-pose")) {
            return;
         }
         if (returnState != ConstructionController.State.EXCAVATING
            || currentAnchor == null
            || !this.beginReachableRouteScaffoldCleanup(mc, taskTarget, currentAnchor)) {
            if (returnState != ConstructionController.State.EXCAVATING
               || !this.deferUnreachableExcavationTarget(mc, config, taskTarget, SafeRoutePlanner.PlanStatus.NO_SAFE_GOAL)
                  && !this.beginHighExcavationPillarAccess(mc, config, taskTarget, currentAnchor, SafeRoutePlanner.PlanStatus.NO_SAFE_GOAL)) {
               if (returnState == ConstructionController.State.CONTAINING_FLUID
                  && this.rotateOrDeferFlattenFluid(mc, "no-work-pose " + taskTarget.toShortString())) {
                  return;
               }
               this.pause(mc, "目标周围没有可触及的候选工作站位 " + taskTarget.toShortString());
            }
         }
      } else {
         if (action == ConstructionController.WorkAction.PLACE
            && returnState == ConstructionController.State.BUILDING_WALL
            && this.flattenPlan != null
            && poses.size() > 1
            && this.fillLookaheadTried.add(taskTarget.immutable())) {
            this.preferFillLookaheadPoses(taskTarget, poses);
         }
         this.beginRoute(mc, taskTarget, poses, returnState, config, reason, true);
      }
   }

   private int fluidDeckY() {
      if (this.flattenFluidTransaction != null && !this.flattenFluidTransaction.fluidCells().isEmpty()) {
         return this.flattenFluidTransaction.fluidCells().getFirst().getY() + 1;
      }
      return Integer.MIN_VALUE;
   }

   private boolean handleDefense(Minecraft mc, TerraLevelerConfig config) {
      boolean active = this.defenseTargetId >= 0
         || this.state == ConstructionController.State.DEFENDING
         || this.state == ConstructionController.State.POSITIONING && this.routeReturnState == ConstructionController.State.DEFENDING;
      if (!config.autoDefend) {
         if (active) {
            this.finishDefense(mc, config, "设置中关闭了自动反击");
         }

         return false;
      } else {
         long gameTime = mc.level.getGameTime();
         this.ignoredDefenseEntities.prune(gameTime);
         DefensiveCombat.Threat detected = DefensiveCombat.findThreat(
            mc, config.defenseRange, entityId -> this.ignoredDefenseEntities.contains(entityId, gameTime)
         );
         if (detected != null
            && detected.reason() == DefensiveCombat.Reason.NEARBY_HOSTILE
            && gameTime < this.nearbyDefenseCooldownUntilTick) {
            detected = null;
         }
         LivingEntity current = this.defenseEntity(mc);
         if (detected == null && current != null && this.defenseReason == DefensiveCombat.Reason.ROUTE_BLOCKER) {
            detected = DefensiveCombat.refreshRouteBlocker(mc, current, config.defenseRange);
         }
         if (detected == null && current != null && this.defenseReason == DefensiveCombat.Reason.PLACEMENT_BLOCKER) {
            detected = DefensiveCombat.refreshPlacementBlocker(mc, current, config.defenseRange, config.clearBlockingAnimals);
         }

         if (detected == null || current != null && detected.entity().getId() == current.getId() && detected.reason() != DefensiveCombat.Reason.RECENT_ATTACKER
            )
          {
            if (!active && detected != null) {
               this.beginDefense(mc, detected);
               current = detected.entity();
               active = true;
            }
         } else {
            this.beginDefense(mc, detected);
            current = detected.entity();
            active = true;
         }

         if (!active) {
            return false;
         }

         this.defenseTicks++;
         if (this.defenseReason == DefensiveCombat.Reason.NEARBY_HOSTILE
            && this.defenseTicks > MAX_NEARBY_DEFENSE_EPISODE_TICKS) {
            this.nearbyDefenseCooldownUntilTick = gameTime + NEARBY_DEFENSE_COOLDOWN_TICKS;
            TerraLevelerDiagnostics.log(
               "COMBAT",
               "episode-timeout elapsedTicks="
                  + this.defenseTicks
                  + " cooldownUntil="
                  + this.nearbyDefenseCooldownUntilTick
                  + " returnState="
                  + this.defenseReturnState
            );
            this.finishDefense(mc, config, "nearby-hostile defense episode exceeded 30 seconds");
            return false;
         }

         if (current != null && current.isAlive()) {
            if (detected != null && detected.entity().getId() == current.getId()) {
               this.defenseNoThreatTicks = 0;
            } else if (++this.defenseNoThreatTicks > 40) {
               this.finishDefense(mc, config, "连续 40 刻未再确认威胁");
               return false;
            }

            if (this.state == ConstructionController.State.POSITIONING && this.routeReturnState == ConstructionController.State.DEFENDING) {
               BlockPos now = current.blockPosition();
               if (this.defenseRouteTarget == null || now.distManhattan(this.defenseRouteTarget) <= 2) {
                  this.tickPositioning(mc, config);
                  return true;
               }

               this.cancelMotionAndRoute(mc, "combat-target-moved");
               this.state = ConstructionController.State.DEFENDING;
               this.defenseApproachCooldown = 0;
               TerraLevelerDiagnostics.log(
                  "COMBAT",
                  "route-replan target="
                     + DefensiveCombat.describe(current)
                     + " old="
                     + this.defenseRouteTarget.toShortString()
                     + " new="
                     + now.toShortString()
               );
            }

            this.state = ConstructionController.State.DEFENDING;
            this.tickDefense(mc, config, current);
            return true;
         } else {
            this.finishDefense(mc, config, "威胁已消失或被击杀");
            return false;
         }
      }
   }

   private void beginDefense(Minecraft mc, DefensiveCombat.Threat threat) {
      boolean alreadyDefending = this.defenseTargetId >= 0;
      ConstructionController.State returnState = alreadyDefending ? this.defenseReturnState : this.baseTaskState();
      if (!alreadyDefending) {
         if (this.combatReturnAnchor == null) {
            this.combatReturnAnchor = PlayerPhysicalState.routeAnchor(mc).orElse(null);
         }

         if (this.combatOriginalSelectedSlot < 0) {
            this.combatOriginalSelectedSlot = mc.player.getInventory().getSelectedSlot();
         }

         TerraLevelerDiagnostics.log(
            "COMBAT",
            "return-anchor-saved anchor="
               + posText(this.combatReturnAnchor)
               + " taskState="
               + returnState
               + " player="
               + mc.player.blockPosition().toShortString()
               + " selectedSlot="
               + this.combatOriginalSelectedSlot
         );
      }

      if (this.state == ConstructionController.State.EATING) {
         this.cancelEating(mc, "combat-preempt");
      }

      boolean cleanupCheckpointAccepted = this.checkpointFlattenCleanupOutboundPosition(mc, "combat-preempt", false);
      if (FlattenCleanupCheckpointRules.shouldAbandonRecordedRoute(
         this.hasActiveFlattenCleanupExcursion(), cleanupCheckpointAccepted
      )) {
         this.abortInvalidFlattenCleanupReturn(mc, "combat-preempt-checkpoint-disconnected");
      }
      this.cancelOwnedWork(mc, "combat-preempt");
      this.defenseReturnState = returnState;
      this.defenseTargetId = threat.entity().getId();
      this.defenseReason = threat.reason();
      this.defenseNoThreatTicks = 0;
      this.defenseApproachCooldown = 0;
      this.defenseRouteTarget = null;
      if (!alreadyDefending) {
         this.defenseTicks = 0;
      }
      this.state = ConstructionController.State.DEFENDING;
      this.resumeState = this.defenseReturnState;
      this.detail = threat.reason() == DefensiveCombat.Reason.PLACEMENT_BLOCKER
         ? "要放方块的位置被生物占着，准备清除 " + threat.entity().getName().getString()
         : threat.reason() == DefensiveCombat.Reason.ROUTE_BLOCKER
         ? "施工路线被生物挡住，准备清除 " + threat.entity().getName().getString()
         : "发现威胁，暂停施工并准备反击 " + threat.entity().getName().getString();
      TerraLevelerDiagnostics.log(
         "COMBAT",
         "threat-acquired target="
            + DefensiveCombat.describe(threat.entity())
            + " name="
            + threat.entity().getName().getString()
            + " reason="
            + threat.reason()
            + " distance="
            + String.format("%.2f", threat.distance())
            + " returnState="
            + this.defenseReturnState
            + " freecam="
            + AutoNavigator.isFreeCamera(mc)
      );
   }

   private void beginEating(Minecraft mc, TerraLevelerConfig config) {
      this.eatingReturnState = this.baseTaskState();
      boolean cleanupCheckpointAccepted = this.checkpointFlattenCleanupOutboundPosition(mc, "auto-eat-start", false);
      if (FlattenCleanupCheckpointRules.shouldAbandonRecordedRoute(
         this.hasActiveFlattenCleanupExcursion(), cleanupCheckpointAccepted
      )) {
         this.abortInvalidFlattenCleanupReturn(mc, "auto-eat-checkpoint-disconnected");
      }
      this.cancelOwnedWork(mc, "auto-eat-start");
      this.eatingPreviousSelectedSlot = mc.player.getInventory().getSelectedSlot();
      this.eatingStartFoodLevel = mc.player.getFoodData().getFoodLevel();
      this.eatingLastFoodLevel = this.eatingStartFoodLevel;
      this.eatingTicks = 0;
      this.eatingUseRetryTicks = 0;
      this.eatingSlot = -1;
      this.eatingItem = "none";
      this.state = ConstructionController.State.EATING;
      this.resumeState = this.eatingReturnState;
      TerraLevelerDiagnostics.log(
         "FOOD", "start hunger=" + this.eatingStartFoodLevel + "/20 returnState=" + this.eatingReturnState + " health=" + mc.player.getHealth()
      );
      if (!this.startFoodUse(mc)) {
         this.pause(mc, "饱食度为 " + this.eatingStartFoodLevel + "/20，但背包中没有普通安全食物");
      }
   }

   private void tickEating(Minecraft mc, TerraLevelerConfig config) {
      AutoNavigator.release(mc);
      // Hold the use key only while an eat is actually in progress. A held key
      // with no item in use makes vanilla right-click whatever block the
      // crosshair targets (doors, levers, chests) instead of eating.
      mc.options.keyUse.setDown(this.eatingSlot >= 0 && mc.player.isUsingItem());
      this.ownsUseKey = true;
      this.eatingTicks++;
      int foodLevel = mc.player.getFoodData().getFoodLevel();
      if (foodLevel > this.eatingLastFoodLevel) {
         TerraLevelerDiagnostics.log(
            "FOOD",
            "consumed item="
               + this.eatingItem
               + " slot="
               + this.eatingSlot
               + " hungerBefore="
               + this.eatingLastFoodLevel
               + " hungerAfter="
               + foodLevel
               + " elapsedTicks="
               + this.eatingTicks
         );
         this.eatingLastFoodLevel = foodLevel;
      }

      if (foodLevel >= 20 || !config.autoEat) {
         this.finishEating(mc, foodLevel >= 20 ? "full" : "disabled");
      } else if (mc.player.isUsingItem()) {
         if (this.eatingTicks % 20 == 0) {
            TerraLevelerDiagnostics.log(
               "FOOD", "using item=" + this.eatingItem + " slot=" + this.eatingSlot + " hunger=" + foodLevel + "/20 elapsedTicks=" + this.eatingTicks
            );
         }

         this.detail = "饱食度 " + foodLevel + "/20，正在自动进食 " + this.eatingItem;
      } else if (this.eatingTicks > 300) {
         TerraLevelerDiagnostics.log("FOOD", "timeout item=" + this.eatingItem + " slot=" + this.eatingSlot + " hunger=" + foodLevel + "/20");
         this.pause(mc, "自动进食超过 15 秒仍未完成");
      } else if (this.eatingUseRetryTicks > 0) {
         this.eatingUseRetryTicks--;
      } else {
         if (!this.startFoodUse(mc)) {
            this.pause(mc, "饱食度为 " + foodLevel + "/20，但背包中没有普通安全食物");
         }
      }
   }

   private boolean startFoodUse(Minecraft mc) {
      int slot = this.selectSafeFoodIntoHotbar(mc);
      if (slot == -2) {
         this.detail = "食物正在换入快捷栏，等待服务器确认";
         return true;
      }

      if (slot < 0) {
         TerraLevelerDiagnostics.log(
            "FOOD", "unavailable hunger=" + mc.player.getFoodData().getFoodLevel() + "/20 inventorySize=" + mc.player.getInventory().getContainerSize()
         );
         return false;
      } else {
         mc.player.getInventory().setSelectedSlot(slot);
         ItemStack stack = mc.player.getInventory().getItem(slot);
         this.eatingSlot = slot;
         this.eatingItem = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
         this.eatingUseRetryTicks = 10;
         mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
         mc.options.keyUse.setDown(mc.player.isUsingItem());
         this.ownsUseKey = true;
         TerraLevelerDiagnostics.log(
            "FOOD",
            "use-request item="
               + this.eatingItem
               + " slot="
               + slot
               + " count="
               + stack.getCount()
               + " hunger="
               + mc.player.getFoodData().getFoodLevel()
               + "/20 using="
               + mc.player.isUsingItem()
         );
         this.detail = "饱食度不足，开始自动进食 " + this.eatingItem;
         return true;
      }
   }

   private int selectSafeFoodIntoHotbar(Minecraft mc) {
      Inventory inventory = mc.player.getInventory();
      if (this.eatingFoodHotbar >= 0) {
         ItemStack hotbarNow = inventory.getItem(this.eatingFoodHotbar);
         if (hotbarNow.isEmpty()) {
            this.releaseFoodLease(mc, "consumed-stack");
            return -2;
         }

         if (!ItemStack.isSameItemSameComponents(hotbarNow, this.eatingLeasedFood)) {
            TerraLevelerDiagnostics.log(
               "FOOD",
               "food-lease-invalid hotbar=" + this.eatingFoodHotbar + " expected=" + this.eatingLeasedFood + " actual=" + hotbarNow
            );
            this.clearFoodLease();
            return -1;
         }

         inventory.setSelectedSlot(this.eatingFoodHotbar);
         if (this.eatingEquipSettleTicks > 0) {
            this.eatingEquipSettleTicks--;
            return -2;
         }

         return this.eatingFoodHotbar;
      }

      int bestSlot = -1;
      int bestNutrition = -1;

      for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
         ItemStack stack = inventory.getItem(slot);
         FoodProperties food = (FoodProperties)stack.get(DataComponents.FOOD);
         if (!stack.isEmpty() && food != null && isSafeOrdinaryFood(stack)) {
            int nutrition = food.nutrition();
            if (preferFoodCandidate(nutrition, slot < 9, bestNutrition, bestSlot >= 0 && bestSlot < 9)) {
               bestSlot = slot;
               bestNutrition = nutrition;
            }
         }
      }

      if (bestSlot < 0) {
         return -1;
      }

      if (bestSlot < 9) {
         return bestSlot;
      }

      if (mc.player.containerMenu != mc.player.inventoryMenu) {
         TerraLevelerDiagnostics.log("FOOD", "auto-hotbar-blocked source=" + bestSlot + " reason=non-player-container-open");
         return -1;
      }

      Slot menuSlot = inventoryMenuSlot(mc, bestSlot);
      if (menuSlot == null) {
         TerraLevelerDiagnostics.log("FOOD", "auto-hotbar-blocked source=" + bestSlot + " reason=inventory-menu-slot-not-found");
         return -1;
      }

      int destination = temporaryHotbarDestination(inventory.getSelectedSlot(), slot -> inventory.getItem(slot).isEmpty());
      this.eatingFoodSourceInventory = bestSlot;
      this.eatingFoodHotbar = destination;
      this.eatingLeasedFood = inventory.getItem(bestSlot).copy();
      this.eatingDisplacedStack = inventory.getItem(destination).copy();
      this.eatingEquipSettleTicks = 2;
      mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index, destination, ContainerInput.SWAP, mc.player);
      inventory.setSelectedSlot(destination);
      TerraLevelerDiagnostics.log(
         "FOOD",
         "food-lease-acquired sourceInventory="
            + this.eatingFoodSourceInventory
            + " destinationHotbar="
            + destination
            + " food="
            + this.eatingLeasedFood
            + " displaced="
            + this.eatingDisplacedStack
            + " settleTicks="
            + this.eatingEquipSettleTicks
      );
      return -2;
   }

   static boolean preferFoodCandidate(int nutrition, boolean inHotbar, int bestNutrition, boolean bestInHotbar) {
      return nutrition > bestNutrition || nutrition == bestNutrition && inHotbar && !bestInHotbar;
   }

   /**
    * Eating used to start at 19/20 and abort committed routes. Now it waits until
    * a typical food item is not wasted (14), or regeneration has stopped while
    * injured (below 18), or health is critical. A committed route is only
    * interrupted when sprinting is lost (6) or health is critical.
    */
   static boolean shouldBeginEating(int foodLevel, float health, float maxHealth, int minimumHealth,
                                    boolean committedRoute) {
      if (foodLevel >= 20) return false;
      boolean critical = health < minimumHealth;
      if (committedRoute && foodLevel > STARVING_FOOD_LEVEL && !critical) return false;
      return foodLevel <= HUNGRY_FOOD_LEVEL
         || health < maxHealth && foodLevel < NATURAL_REGENERATION_FOOD_LEVEL
         || critical;
   }

   /** Health at which a low-health pause resumes on its own (with hysteresis). */
   static float lowHealthResumeThreshold(int minimumHealth, float maxHealth) {
      return Math.min(maxHealth, minimumHealth + LOW_HEALTH_RESUME_MARGIN);
   }

   private void pauseForLowHealth(Minecraft mc, TerraLevelerConfig config) {
      float resumeAt = lowHealthResumeThreshold(config.minimumHealth, mc.player.getMaxHealth());
      String reason = String.format(
         "生命值 %.1f 低于警戒值 %d（回复到 %.0f）", mc.player.getHealth(), config.minimumHealth, resumeAt
      );
      this.pause(mc, reason, ConstructionController.PauseKind.WAIT_FOR_HEALTH);
   }

   static boolean isSafeOrdinaryFood(ItemStack stack) {
      return isSafeOrdinaryFoodId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
   }

   static boolean isSafeOrdinaryFoodId(String id) {
      return !Set.of(
            "minecraft:rotten_flesh",
            "minecraft:spider_eye",
            "minecraft:poisonous_potato",
            "minecraft:pufferfish",
            "minecraft:chorus_fruit",
            "minecraft:suspicious_stew",
            "minecraft:apple",
            // Valuable emergency food is never eaten for ordinary hunger.
            "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple",
            // Raw chicken has a 30% chance to inflict Hunger.
            "minecraft:chicken"
         )
         .contains(id);
   }

   private void finishEating(Minecraft mc, String reason) {
      ConstructionController.State next = normalizeTaskState(this.eatingReturnState);
      TerraLevelerDiagnostics.log(
         "FOOD",
         "finish reason="
            + reason
            + " hunger="
            + mc.player.getFoodData().getFoodLevel()
            + "/20 startHunger="
            + this.eatingStartFoodLevel
            + " elapsedTicks="
            + this.eatingTicks
            + " resume="
            + next
      );
      this.releaseFoodControls(mc, "finish-" + reason);
      this.clearEatingRuntime();
      this.state = next;
      this.resumeState = next;
      this.detail = "进食完成，恢复施工";
   }

   private void cancelEating(Minecraft mc, String reason) {
      if (mc.player != null && mc.player.isUsingItem()) {
         mc.gameMode.releaseUsingItem(mc.player);
      }

      TerraLevelerDiagnostics.log(
         "FOOD", "cancel reason=" + reason + " item=" + this.eatingItem + " hunger=" + (mc.player == null ? "null" : mc.player.getFoodData().getFoodLevel())
      );
      this.releaseFoodControls(mc, "cancel-" + reason);
      this.clearEatingRuntime();
   }

   private void releaseFoodControls(Minecraft mc, String reason) {
      if (this.ownsUseKey) {
         mc.options.keyUse.setDown(false);
      }

      this.ownsUseKey = false;
      this.releaseFoodLease(mc, reason);
      if (mc.player != null && this.eatingPreviousSelectedSlot >= 0 && this.eatingPreviousSelectedSlot < 9) {
         int before = mc.player.getInventory().getSelectedSlot();
         mc.player.getInventory().setSelectedSlot(this.eatingPreviousSelectedSlot);
         TerraLevelerDiagnostics.log("FOOD", "selection-restored reason=" + reason + " from=" + before + " to=" + this.eatingPreviousSelectedSlot);
      }
   }

   private void clearEatingRuntime() {
      this.eatingTicks = 0;
      this.eatingUseRetryTicks = 0;
      this.eatingSlot = -1;
      this.eatingItem = "none";
      this.eatingPreviousSelectedSlot = -1;
      this.clearFoodLease();
      this.ownsUseKey = false;
   }

   private void releaseFoodLease(Minecraft mc, String reason) {
      if (mc.player == null || this.eatingFoodHotbar < 0 || this.eatingFoodSourceInventory < 9) {
         this.clearFoodLease();
         return;
      }

      Inventory inventory = mc.player.getInventory();
      ItemStack hotbarNow = inventory.getItem(this.eatingFoodHotbar);
      ItemStack sourceNow = inventory.getItem(this.eatingFoodSourceInventory);
      boolean safe = foodLeaseRestorable(
         hotbarNow, this.eatingLeasedFood, sourceNow, this.eatingDisplacedStack, mc.player.containerMenu == mc.player.inventoryMenu
      );
      Slot sourceMenuSlot = safe ? inventoryMenuSlot(mc, this.eatingFoodSourceInventory) : null;
      if (sourceMenuSlot != null && mc.gameMode != null) {
         mc.gameMode.handleContainerInput(
            mc.player.inventoryMenu.containerId, sourceMenuSlot.index, this.eatingFoodHotbar, ContainerInput.SWAP, mc.player
         );
         TerraLevelerDiagnostics.log(
            "FOOD",
            "food-lease-restored reason="
               + reason
               + " foodToInventory="
               + this.eatingFoodSourceInventory
               + " displacedToHotbar="
               + this.eatingFoodHotbar
               + " remainingFood="
               + hotbarNow
               + " displaced="
               + this.eatingDisplacedStack
         );
      } else {
         TerraLevelerDiagnostics.log(
            "FOOD",
            "food-lease-restore-skipped reason="
               + reason
               + " sourceInventory="
               + this.eatingFoodSourceInventory
               + " hotbar="
               + this.eatingFoodHotbar
               + " expectedFood="
               + this.eatingLeasedFood
               + " actualFood="
               + hotbarNow
               + " expectedDisplaced="
               + this.eatingDisplacedStack
               + " actualSource="
               + sourceNow
         );
      }

      this.clearFoodLease();
   }

   private void clearFoodLease() {
      this.eatingFoodSourceInventory = -1;
      this.eatingFoodHotbar = -1;
      this.eatingLeasedFood = ItemStack.EMPTY;
      this.eatingDisplacedStack = ItemStack.EMPTY;
      this.eatingEquipSettleTicks = 0;
   }

   static boolean foodLeaseRestorable(
      ItemStack hotbarNow, ItemStack leasedFood, ItemStack sourceNow, ItemStack displacedStack, boolean playerInventoryOpen
   ) {
      boolean leasedFoodStillPresent = hotbarNow.isEmpty() || ItemStack.isSameItemSameComponents(hotbarNow, leasedFood);
      return leasedFoodStillPresent && sameStackKind(sourceNow, displacedStack) && playerInventoryOpen;
   }

   private void tickDefense(Minecraft mc, TerraLevelerConfig config, LivingEntity target) {
      if (target != null) {
         DefensiveCombat.WeaponChoice choice = this.combatWeaponHotbar >= 0
            ? DefensiveCombat.weaponChoice(mc, this.combatWeaponHotbar)
            : DefensiveCombat.selectBestWeapon(mc, config.durabilityReserve);
         ItemStack weapon = choice.stack();
         double distance = mc.player.distanceTo(target);
         boolean inRange = mc.player.isWithinAttackRange(weapon, target.getBoundingBox(), 0.0);
         boolean visible = mc.player.hasLineOfSight(target);
         float cooldown = mc.player.getAttackStrengthScale(0.0F);
         if (inRange && visible) {
            AutoNavigator.release(mc);
            AutoNavigator.lookAt(mc, target.getEyePosition());
            if (cooldown >= 0.9F) {
               if (!this.ensureCombatWeapon(mc, choice)) {
                  this.detail = "武器已移入快捷栏，等待服务器同步后攻击 " + target.getName().getString();
                  return;
               }

               choice = DefensiveCombat.weaponChoice(mc, mc.player.getInventory().getSelectedSlot());
               weapon = choice.stack();
               mc.gameMode.attack(mc.player, target);
               mc.player.swing(InteractionHand.MAIN_HAND);
               TerraLevelerDiagnostics.log(
                  "COMBAT",
                  "attack target="
                     + DefensiveCombat.describe(target)
                     + " distance="
                     + String.format("%.2f", distance)
                     + " slot="
                     + choice.slot()
                     + " weapon="
                     + weapon
                     + " category="
                     + choice.category()
                     + " damage="
                     + String.format("%.2f", choice.damage())
                     + " speed="
                     + String.format("%.2f", choice.speed())
                     + " cooldown="
                     + String.format("%.3f", cooldown)
                     + " targetHealth="
                     + String.format("%.2f", target.getHealth())
                     + " reason="
                     + this.defenseReason
                     + " freecam="
                     + AutoNavigator.isFreeCamera(mc)
               );
               this.detail = "正在反击 " + target.getName().getString() + "，命中后等待完整攻击冷却";
            } else {
               if (this.defenseTicks % 20 == 0) {
                  TerraLevelerDiagnostics.log(
                     "COMBAT",
                     "attack-wait target="
                        + DefensiveCombat.describe(target)
                        + " distance="
                        + String.format("%.2f", distance)
                        + " cooldown="
                        + String.format("%.3f", cooldown)
                        + " visible=true"
                  );
               }

               this.detail = "锁定威胁并等待攻击冷却 " + target.getName().getString();
            }
         } else if (this.defenseApproachCooldown > 0) {
            this.defenseApproachCooldown--;
            AutoNavigator.release(mc);
            this.detail = "重新评估怪物位置与安全接近路线";
         } else {
            List<BlockPos> goals = this.combatPoses(mc, target);
            BlockPos anchor = PlayerPhysicalState.routeAnchor(mc).orElse(null);
            if (anchor != null && goals.contains(anchor)) {
               this.defenseApproachStalls++;
            } else {
               this.defenseApproachStalls = 0;
            }
            if (goals.isEmpty()) {
               this.abandonUnreachableThreat(mc, config, target, visible
                  ? "目标周围没有安全近战站位"
                  : "目标隔着方块，周围没有看得见它的安全站位", visible ? 100L : UNSEEN_THREAT_IGNORE_TICKS);
            } else if (this.defenseApproachStalls > MAX_DEFENSE_APPROACH_STALLS) {
               // 0.8.74: a zombie 2.4 blocks away behind a wall kept the player "already at
               // the approach pose" but unable to hit it, re-planning every tick for 40 s.
               this.abandonUnreachableThreat(mc, config, target, "已在接近站位但仍无法命中目标", UNSEEN_THREAT_IGNORE_TICKS);
            } else {
               this.defenseRouteTarget = target.blockPosition();
               TerraLevelerDiagnostics.log(
                  "COMBAT",
                  "approach-plan target="
                     + DefensiveCombat.describe(target)
                     + " distance="
                     + String.format("%.2f", distance)
                     + " visible="
                     + visible
                     + " goals="
                     + goals.size()
                     + " targetPos="
                     + this.defenseRouteTarget.toShortString()
               );
               this.beginRoute(mc, this.defenseRouteTarget, goals, ConstructionController.State.DEFENDING, config, "沿安全路线接近威胁");
            }
         }
      }
   }

   private List<BlockPos> combatPoses(Minecraft mc, LivingEntity target) {
      List<BlockPos> result = new ArrayList<>();
      BlockPos center = target.blockPosition();
      Vec3 targetEye = target.getEyePosition();

      for (int y = center.getY() - 2; y <= center.getY() + 2; y++) {
         for (int x = center.getX() - 3; x <= center.getX() + 3; x++) {
            for (int z = center.getZ() - 3; z <= center.getZ() + 3; z++) {
               BlockPos pose = new BlockPos(x, y, z);
               if (SafeRoutePlanner.canOccupy(mc, pose) && SafeRoutePlanner.hasFloor(mc, pose)) {
                  double dx = x + 0.5 - target.getX();
                  double dy = y + 1.62 - target.getEyeY();
                  double dz = z + 0.5 - target.getZ();
                  // A pose is only useful if the target can be seen (and so hit) from it.
                  if (!(dx * dx + dy * dy + dz * dz > 9.0)
                     && clearSight(mc, new Vec3(x + 0.5, y + 1.62, z + 0.5), targetEye)) {
                     result.add(pose.immutable());
                  }
               }
            }
         }
      }

      result.sort(Comparator.comparingDouble(pos -> mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)));
      return result;
   }

   private LivingEntity defenseEntity(Minecraft mc) {
      if (this.defenseTargetId >= 0 && mc.level != null) {
         return mc.level.getEntity(this.defenseTargetId) instanceof LivingEntity living ? living : null;
      } else {
         return null;
      }
   }

   private static boolean clearSight(Minecraft mc, Vec3 from, Vec3 to) {
      return mc.level.clip(new net.minecraft.world.level.ClipContext(
         from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player
      )).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
   }

   private void abandonUnreachableThreat(Minecraft mc, TerraLevelerConfig config, LivingEntity target, String reason) {
      this.abandonUnreachableThreat(mc, config, target, reason, 100L);
   }

   private void abandonUnreachableThreat(Minecraft mc, TerraLevelerConfig config, LivingEntity target, String reason, long ignoreTicks) {
      long ignoreUntilTick = mc.level.getGameTime() + ignoreTicks;
      this.ignoredDefenseEntities.ignore(target.getId(), ignoreUntilTick);
      TerraLevelerDiagnostics.log(
         "COMBAT",
         "unreachable target="
            + DefensiveCombat.describe(target)
            + " reason="
            + reason
            + " ignoreUntil="
            + ignoreUntilTick
            + " ignoredCount="
            + this.ignoredDefenseEntities.size()
            + " returnState="
            + this.defenseReturnState
      );
      this.finishDefense(mc, config, reason + "，暂时忽略 " + ignoreTicks + " 刻");
   }

   private void finishDefense(Minecraft mc, TerraLevelerConfig config, String reason) {
      ConstructionController.State next = normalizeTaskState(this.defenseReturnState);
      this.cancelOwnedWork(mc, "combat-finish");
      this.releaseCombatWeapon(mc, reason);
      TerraLevelerDiagnostics.log(
         "COMBAT",
         "finish targetId="
            + this.defenseTargetId
            + " reason="
            + reason
            + " elapsedTicks="
            + this.defenseTicks
            + " resume="
            + next
            + " health="
            + (mc.player == null ? "null" : mc.player.getHealth())
      );
      this.defenseTargetId = -1;
      this.defenseReason = null;
      this.defenseNoThreatTicks = 0;
      this.defenseApproachCooldown = 0;
      this.defenseRouteTarget = null;
      this.defenseTicks = 0;
      this.defenseApproachStalls = 0;
      this.state = next;
      this.resumeState = next;
      this.clearRouteFailures();
      if (this.combatReturnAnchor != null && SafeRoutePlanner.canOccupy(mc, this.combatReturnAnchor) && SafeRoutePlanner.hasFloor(mc, this.combatReturnAnchor)) {
         this.returningFromCombat = true;
         TerraLevelerDiagnostics.log(
            "COMBAT",
            "return-required from=" + mc.player.blockPosition().toShortString() + " to=" + this.combatReturnAnchor.toShortString() + " resume=" + next
         );
         this.beginCombatReturn(mc, config);
      } else {
         TerraLevelerDiagnostics.log("COMBAT", "return-skipped anchor=" + posText(this.combatReturnAnchor) + " reason=anchor-no-longer-safe resume=" + next);
         this.combatReturnAnchor = null;
         this.returningFromCombat = false;
         this.detail = "威胁处理结束，原站位不可用，从当前位置恢复施工：" + reason;
      }
   }

   private boolean ensureCombatWeapon(Minecraft mc, DefensiveCombat.WeaponChoice choice) {
      Inventory inventory = mc.player.getInventory();
      if (this.combatWeaponHotbar >= 0) {
         ItemStack current = inventory.getItem(this.combatWeaponHotbar);
         if (!ItemStack.isSameItemSameComponents(current, this.combatLeasedWeapon)) {
            TerraLevelerDiagnostics.log(
               "COMBAT", "weapon-lease-invalid hotbar=" + this.combatWeaponHotbar + " expected=" + this.combatLeasedWeapon + " actual=" + current
            );
            this.clearCombatWeaponLease();
            return false;
         } else {
            inventory.setSelectedSlot(this.combatWeaponHotbar);
            if (this.combatEquipSettleTicks > 0) {
               this.combatEquipSettleTicks--;
               return false;
            } else {
               return true;
            }
         }
      } else if (choice.slot() < 9) {
         inventory.setSelectedSlot(choice.slot());
         return true;
      } else if (mc.player.containerMenu != mc.player.inventoryMenu) {
         TerraLevelerDiagnostics.log("COMBAT", "weapon-lease-blocked source=" + choice.slot() + " reason=non-player-container-open");
         return false;
      } else {
         Slot sourceMenuSlot = inventoryMenuSlot(mc, choice.slot());
         if (sourceMenuSlot == null) {
            TerraLevelerDiagnostics.log("COMBAT", "weapon-lease-blocked source=" + choice.slot() + " reason=inventory-menu-slot-not-found");
            return false;
         } else {
            int destination = temporaryHotbarDestination(inventory.getSelectedSlot(), slot -> inventory.getItem(slot).isEmpty());
            this.combatWeaponSourceInventory = choice.slot();
            this.combatWeaponHotbar = destination;
            this.combatLeasedWeapon = inventory.getItem(choice.slot()).copy();
            this.combatDisplacedStack = inventory.getItem(destination).copy();
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, sourceMenuSlot.index, destination, ContainerInput.SWAP, mc.player);
            inventory.setSelectedSlot(destination);
            this.combatEquipSettleTicks = 2;
            TerraLevelerDiagnostics.log(
               "COMBAT",
               "weapon-lease-acquired sourceInventory="
                  + this.combatWeaponSourceInventory
                  + " destinationHotbar="
                  + destination
                  + " weapon="
                  + this.combatLeasedWeapon
                  + " displaced="
                  + this.combatDisplacedStack
                  + " settleTicks="
                  + this.combatEquipSettleTicks
            );
            return false;
         }
      }
   }

   private void releaseCombatWeapon(Minecraft mc, String reason) {
      if (mc.player == null) {
         this.clearCombatWeaponLease();
         this.combatOriginalSelectedSlot = -1;
      } else {
         Inventory inventory = mc.player.getInventory();
         if (this.combatWeaponHotbar >= 0 && this.combatWeaponSourceInventory >= 9) {
            ItemStack hotbarNow = inventory.getItem(this.combatWeaponHotbar);
            ItemStack sourceNow = inventory.getItem(this.combatWeaponSourceInventory);
            boolean safe = ItemStack.isSameItemSameComponents(hotbarNow, this.combatLeasedWeapon)
               && sameStackKind(sourceNow, this.combatDisplacedStack)
               && mc.player.containerMenu == mc.player.inventoryMenu;
            Slot sourceMenuSlot = safe ? inventoryMenuSlot(mc, this.combatWeaponSourceInventory) : null;
            if (sourceMenuSlot != null && mc.gameMode != null) {
               mc.gameMode
                  .handleContainerInput(mc.player.inventoryMenu.containerId, sourceMenuSlot.index, this.combatWeaponHotbar, ContainerInput.SWAP, mc.player);
               TerraLevelerDiagnostics.log(
                  "COMBAT",
                  "weapon-lease-restored reason="
                     + reason
                     + " weaponToInventory="
                     + this.combatWeaponSourceInventory
                     + " displacedToHotbar="
                     + this.combatWeaponHotbar
                     + " weapon="
                     + this.combatLeasedWeapon
                     + " displaced="
                     + this.combatDisplacedStack
               );
            } else {
               TerraLevelerDiagnostics.log(
                  "COMBAT",
                  "weapon-lease-restore-skipped reason="
                     + reason
                     + " safe="
                     + safe
                     + " hotbarNow="
                     + hotbarNow
                     + " sourceNow="
                     + sourceNow
                     + " expectedWeapon="
                     + this.combatLeasedWeapon
                     + " expectedDisplaced="
                     + this.combatDisplacedStack
               );
            }
         }

         if (this.combatOriginalSelectedSlot >= 0 && this.combatOriginalSelectedSlot < 9) {
            inventory.setSelectedSlot(this.combatOriginalSelectedSlot);
         }

         this.clearCombatWeaponLease();
         this.combatOriginalSelectedSlot = -1;
      }
   }

   private static Slot inventoryMenuSlot(Minecraft mc, int inventorySlot) {
      for (Slot candidate : mc.player.inventoryMenu.slots) {
         if (candidate.container == mc.player.getInventory() && candidate.getContainerSlot() == inventorySlot) {
            return candidate;
         }
      }

      return null;
   }

   static int temporaryHotbarDestination(int selectedSlot, IntPredicate emptySlot) {
      for (int slot = 0; slot < 9; slot++) {
         if (emptySlot.test(slot)) {
            return slot;
         }
      }

      return selectedSlot;
   }

   private static boolean sameStackKind(ItemStack current, ItemStack expected) {
      return expected.isEmpty() ? current.isEmpty() : ItemStack.isSameItemSameComponents(current, expected);
   }

   private void clearCombatWeaponLease() {
      this.combatWeaponSourceInventory = -1;
      this.combatWeaponHotbar = -1;
      this.combatLeasedWeapon = ItemStack.EMPTY;
      this.combatDisplacedStack = ItemStack.EMPTY;
      this.combatEquipSettleTicks = 0;
   }

   private void clearDefenseForPause() {
      this.defenseTargetId = -1;
      this.defenseReason = null;
      this.defenseNoThreatTicks = 0;
      this.defenseApproachCooldown = 0;
      this.defenseRouteTarget = null;
      this.defenseTicks = 0;
      this.combatReturnAnchor = null;
      this.returningFromCombat = false;
   }

   private void beginCombatReturn(Minecraft mc, TerraLevelerConfig config) {
      if (this.combatReturnAnchor == null) {
         this.returningFromCombat = false;
      } else {
         BlockPos current = PlayerPhysicalState.routeAnchor(mc).orElse(null);
         if (this.combatReturnAnchor.equals(current) && horizontalDistance(mc, this.combatReturnAnchor) <= 0.2) {
            TerraLevelerDiagnostics.log("COMBAT", "return-arrived anchor=" + this.combatReturnAnchor.toShortString() + " resume=" + this.defenseReturnState);
            this.combatReturnAnchor = null;
            this.returningFromCombat = false;
            this.state = normalizeTaskState(this.defenseReturnState);
            this.clearRouteFailures();
            this.detail = "已返回战斗前站位，继续施工";
         } else if (SafeRoutePlanner.canOccupy(mc, this.combatReturnAnchor) && SafeRoutePlanner.hasFloor(mc, this.combatReturnAnchor)) {
            TerraLevelerDiagnostics.log(
               "COMBAT", "return-plan from=" + posText(current) + " to=" + this.combatReturnAnchor.toShortString() + " failures=" + this.routeFailures
            );
            this.beginRoute(
               mc, this.combatReturnAnchor, List.of(this.combatReturnAnchor), normalizeTaskState(this.defenseReturnState), config, "战斗结束，返回施工前的安全站位", false
            );
         } else {
            TerraLevelerDiagnostics.log("COMBAT", "return-abandoned anchor=" + this.combatReturnAnchor.toShortString() + " reason=anchor-changed");
            this.combatReturnAnchor = null;
            this.returningFromCombat = false;
            this.state = normalizeTaskState(this.defenseReturnState);
            this.clearRouteFailures();
            this.detail = "战斗前站位已经变化，从当前位置重新规划施工";
         }
      }
   }

   private void captureRetainedInventoryBaseline(Minecraft mc) {
      this.retainedInventoryBaseline.clear();
      if (mc.player != null) {
         Inventory inventory = mc.player.getInventory();

          for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
             ItemStack stack = inventory.getItem(slot);
             if (!stack.isEmpty()) {
                this.retainedInventoryBaseline.merge(stack.getItem(), stack.getCount(), Integer::sum);
             }
         }

         TerraLevelerDiagnostics.log("MATERIAL", "inventory-baseline retainedItemTypes=" + this.retainedInventoryBaseline.size()
            + " managedPickup=" + configuredPickupMaterialNames());
      }
   }

   private boolean refreshPickupMaterialConfiguration(Minecraft mc, TerraLevelerConfig config) {
      if (config == null) return false;
      String fingerprint = pickupMaterialFingerprint(config);
      if (this.jobPickupMaterialFingerprint.isEmpty()) {
         this.jobPickupItems = configuredPickupItems(config);
         this.jobPickupMaterialFingerprint = fingerprint;
         return false;
      }
      if (fingerprint.equals(this.jobPickupMaterialFingerprint)) return false;

      if (this.inventoryDiscardTransaction != null) {
         this.detail = "施工收集物配置已变化，正在完成当前库存事务";
         return true;
      }
      if (this.state == ConstructionController.State.UNPACKING_MATERIAL) {
         this.materialStopRequested = false;
         this.beginMaterialRollback(mc, "施工收集物配置已变化，正在回滚旧材料事务");
         return true;
      }

      this.jobPickupItems = configuredPickupItems(config);
      this.jobPickupMaterialFingerprint = fingerprint;
      this.clearMaterialUnpack();
      this.pendingUnexpectedPickupDiscard.clear();
      this.pendingUnexpectedPickupGraceUntil.clear();
      this.pendingUnexpectedPickupObserved.clear();
      this.inventoryDiscardSplitBlockedItems.clear();
      this.inventoryDiscardObservedPickupRevision = -1L;
      this.inventoryDiscardStableTicks = 0;
      this.materialStorageCooldownTicks = 0;
      this.dirtPickupCooldownTicks = 0;
      this.captureRetainedInventoryBaseline(mc);
      this.detail = "施工收集物配置已更新，已重新建立材料基线和脚手选择";
      TerraLevelerDiagnostics.log(
         "MATERIAL",
         "pickup-material-config-changed items=" + configuredPickupMaterialNames()
            + " limit=" + config.pickupItemLimit
            + " baseline-reset=true"
      );
      return false;
   }

   private boolean tickDirectedItemDiscardTransaction(Minecraft mc) {
      DirectedItemDiscardTransaction transaction = this.directedItemDiscardTransaction;
      DirectedItemDiscardTransaction.Result result = transaction.tick(mc);
      if (result == DirectedItemDiscardTransaction.Result.IN_PROGRESS) return true;

      this.directedItemDiscardTransaction = null;
      this.inventoryDiscardSettleTicks = INVENTORY_DISCARD_SETTLE_TICKS;
      if (result == DirectedItemDiscardTransaction.Result.FAILED) {
         for (Item item : transaction.discardedByItem().keySet()) {
            movePendingUnexpectedPickupToTail(item);
         }
         this.detail = "定向丢弃未获服务器确认，稍后重试 " + materialName(transaction.item());
         TerraLevelerDiagnostics.log("MATERIAL", "directed-discard-failed item=" + materialName(transaction.item())
            + " reason=" + transaction.failureReason());
         return true;
      }

      for (Entry<Item, Integer> thrown : transaction.discardedByItem().entrySet()) {
         Item item = thrown.getKey();
         int pending = this.pendingUnexpectedPickupDiscard.getOrDefault(item, 0);
         if (pending <= 0) continue;
         int remaining = Math.max(0, pending - thrown.getValue());
         if (remaining > 0) {
            this.pendingUnexpectedPickupDiscard.put(item, remaining);
         } else {
            removePendingUnexpectedPickup(item);
         }
      }
      TerraLevelerDiagnostics.log("MATERIAL", "directed-discard-confirmed item=" + materialName(transaction.item())
         + " discarded=" + transaction.discardedCount() + " types=" + transaction.discardedByItem().size()
         + " pendingTypes=" + this.pendingUnexpectedPickupDiscard.size());
      return true;
   }

   private boolean tickInventoryDiscardTransaction(Minecraft mc) {
      InventoryDiscardTransaction transaction = this.inventoryDiscardTransaction;
      InventoryDiscardTransaction.Result result = transaction.tick(mc);
      if (result == InventoryDiscardTransaction.Result.IN_PROGRESS) return true;

      if (result == InventoryDiscardTransaction.Result.FAILED && transaction.restoreCursor(mc)) {
         // Items were still on the cursor; give the server a few ticks to confirm
         // the put-back before the transaction is released.
         TerraLevelerDiagnostics.log("MATERIAL", "discard-group-split-cursor-restore item=" + materialName(transaction.item()));
         return true;
      }

      this.inventoryDiscardTransaction = null;
      if (result == InventoryDiscardTransaction.Result.FAILED) {
         this.inventoryDiscardSplitBlockedItems.add(transaction.item());
         this.inventoryDiscardSettleTicks = INVENTORY_DISCARD_SETTLE_TICKS * 5;
         this.detail = "库存拆分未获服务器确认，" + materialName(transaction.item()) + " 已切换为安全逐个清理";
         TerraLevelerDiagnostics.log(
            "MATERIAL",
            "discard-group-split-disabled item=" + materialName(transaction.item())
               + " reason=" + transaction.failureReason()
               + " fallback=single-item"
         );
         this.applyPendingUserRequest();
         return true;
      }

      int pending = this.pendingUnexpectedPickupDiscard.getOrDefault(transaction.item(), 0);
      int remaining = pending - transaction.discardedCount();
      if (remaining > 0) {
         this.pendingUnexpectedPickupDiscard.put(transaction.item(), remaining);
      } else {
         removePendingUnexpectedPickup(transaction.item());
      }
      this.inventoryDiscardSettleTicks = INVENTORY_DISCARD_SETTLE_TICKS;
      TerraLevelerDiagnostics.log(
         "MATERIAL",
         "discard-group-complete item=" + materialName(transaction.item())
            + " discarded=" + transaction.discardedCount()
            + " pending=" + Math.max(0, remaining)
      );
      this.applyPendingUserRequest();
      return true;
   }

   /** Applies a pause/resume or stop that arrived while an inventory transaction was settling. */
   private void applyPendingUserRequest() {
      ConstructionController.PendingUserRequest request = this.pendingUserRequest;
      this.pendingUserRequest = ConstructionController.PendingUserRequest.NONE;
      if (request == ConstructionController.PendingUserRequest.STOP) {
         TerraLevelerDiagnostics.log("STATE", "stop-deferred-applied");
         this.stop();
      } else if (request == ConstructionController.PendingUserRequest.TOGGLE) {
         TerraLevelerDiagnostics.log("STATE", "toggle-deferred-applied state=" + this.state);
         this.toggleInternal(null);
      }
   }

   private boolean maybeStoreInventoryOverflow(Minecraft mc) {
      if (this.inventoryDiscardSettleTicks > 0 || mc.player == null || mc.gameMode == null
         || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return false;
      if (this.inventoryDiscardObservedPickupRevision != this.unexpectedPickupRevision) {
         this.inventoryDiscardObservedPickupRevision = this.unexpectedPickupRevision;
         this.inventoryDiscardStableTicks = 0;
      } else if (this.inventoryDiscardStableTicks < INVENTORY_DISCARD_STABILITY_TICKS) {
         this.inventoryDiscardStableTicks++;
      }
      Inventory inventory = mc.player.getInventory();
      prunePendingUnexpectedPickups(mc);
      if (this.discardEnabledMetalItem(mc, inventory)) {
         return true;
      }
      if (this.manageConfiguredPickupOverflow(mc, inventory)) {
         return true;
      }
      boolean urgent = this.inventoryDiscardUrgent(inventory);
      if (this.discardUnexpectedItemType(mc, inventory, urgent)) {
         return true;
      }
      for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
         ItemStack stack = inventory.getItem(slot);
          if (stack.isEmpty() || slot == inventory.getSelectedSlot() || isManagedPickupMaterial(stack.getItem())
             || isLooseDiscardProtected(stack.getItem(), TerraLeveler.CONFIG)) continue;
          Item itemToDiscard = stack.getItem();
          int stackCount = stack.getCount();
          int baseline = this.retainedInventoryBaseline.getOrDefault(itemToDiscard, 0);
          int currentCount = inventoryCount(mc, itemToDiscard);
          int forcedDiscard = this.pendingUnexpectedPickupDiscard.getOrDefault(itemToDiscard, 0);
          // Queued pickups wait for a directed throw clear of the work area. Dropping them
          // at the player's feet only made TerraLeveler collect the same stack again two seconds later.
          if (forcedDiscard > 0 && !urgent) continue;
          if (forcedDiscard <= 0 && isManagedConstructionMaterial(itemToDiscard)) continue;
          boolean wholeUnexpectedStack = forcedDiscard > 0
             && canDiscardWholeInventoryStack(baseline, currentCount, stackCount, forcedDiscard);
           if (forcedDiscard > 0 && !wholeUnexpectedStack
              && !this.inventoryDiscardSplitBlockedItems.contains(itemToDiscard)) {
              int outsideCount = inventoryCountOutsideSlot(mc, itemToDiscard, slot);
              int protectedInStack = protectedBaselineInStack(baseline, stackCount, outsideCount);
              int newInStack = stackCount - protectedInStack;
              int destinationSlot = findEmptyInventorySlot(mc, slot);
              Slot sourceMenuSlot = inventoryMenuSlot(mc, slot);
              if (protectedInStack > 0 && newInStack > 0 && destinationSlot >= 0 && sourceMenuSlot != null) {
                 if (this.inventoryDiscardStableTicks < INVENTORY_DISCARD_STABILITY_TICKS) {
                    AutoNavigator.release(mc);
                    this.detail = "等待库存拾取稳定后再整理 " + materialName(itemToDiscard);
                    return true;
                 }
                 Slot destinationMenuSlot = inventoryMenuSlot(mc, destinationSlot);
                 if (destinationMenuSlot != null) {
                    this.inventoryDiscardTransaction = InventoryDiscardTransaction.begin(
                       itemToDiscard,
                       sourceMenuSlot.index,
                       destinationMenuSlot.index,
                       stackCount,
                       protectedInStack
                    );
                    TerraLevelerDiagnostics.log(
                       "MATERIAL",
                       "discard-group-split-start item=" + materialName(itemToDiscard)
                          + " source=" + slot
                          + " destination=" + destinationSlot
                          + " preserve=" + protectedInStack
                          + " discard=" + newInStack
                          + " baseline=" + baseline
                          + " current=" + currentCount
                          + " outside=" + outsideCount
                    );
                    return true;
                 }
              }
           }
           if (forcedDiscard > 0 && !wholeUnexpectedStack
              && this.inventoryDiscardSplitBlockedItems.contains(itemToDiscard)) {
              TerraLevelerDiagnostics.log(
                 "MATERIAL",
                 "discard-group-split-fallback item=" + materialName(itemToDiscard)
                    + " slot=" + slot
                    + " forced=" + forcedDiscard
                    + " reason=split-disabled-after-server-rejection"
              );
           }
           int excess = wholeUnexpectedStack
              ? stackCount
              : forcedDiscard > 0
              ? Math.min(forcedDiscard, stackCount)
              : unwantedInventoryExcess(baseline, currentCount);
           if (excess > 0) {
              Slot menuSlot = inventoryMenuSlot(mc, slot);
              if (menuSlot != null) {
                int button = wholeUnexpectedStack
                   ? 1
                   : forcedDiscard > 0
                   ? discardButtonForInventoryExcess(baseline, currentCount, stackCount, excess)
                   : discardButtonForInventoryExcess(baseline, currentCount, stackCount, excess);
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index, button, ContainerInput.THROW, mc.player);
                this.inventoryDiscardSettleTicks = INVENTORY_DISCARD_SETTLE_TICKS;
                if (forcedDiscard > 0) {
                   int discarded = button == 1 ? stackCount : 1;
                   int remaining = forcedDiscard - discarded;
                    if (remaining > 0) {
                       this.pendingUnexpectedPickupDiscard.put(itemToDiscard, remaining);
                    } else {
                       removePendingUnexpectedPickup(itemToDiscard);
                   }
                }
                TerraLevelerDiagnostics.log("MATERIAL", "discard-new-item item=" + materialName(itemToDiscard)
                   + " excess=" + excess + " forced=" + forcedDiscard + " slot=" + slot + " button=" + button);
                return true;
            }
         }
      }
      return false;
   }

   /**
    * Keeps each configured construction material below its loose inventory allowance.
    * Storage is preferred when a compatible shulker has room; otherwise only one
    * complete inventory stack is dropped so the material can be collected again later.
    */
   private boolean manageConfiguredPickupOverflow(Minecraft mc, Inventory inventory) {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      if (config == null || this.materialStorageCooldownTicks > 0) return false;

      int limit = Math.max(0, config.pickupItemLimit);
      int slotLimit = Math.min(36, inventory.getContainerSize());
      for (Item item : this.activePickupMaterialItems()) {
         if (isProtectedRetainedInventoryItem(item)) continue;
         int currentCount = inventoryCount(mc, item);
         int baseline = this.retainedInventoryBaseline.getOrDefault(item, 0);
         int allowed = configuredPickupAllowance(limit, baseline);
         // While the job still needs this material, one extra stack is tolerated: 0.8.71
         // extracted a full stack (loose 32 -> 96) and immediately stored it again.
         boolean fillPhase = isFlattenSurfaceMaterial(item) && this.excavationIndex >= this.excavationTargets.size()
            && this.wallIndex < this.wallTargets.size();
         if (currentCount <= overflowStoreThreshold(allowed, this.remainingConstructionDemand(item), fillPhase)) continue;

         int[] stackCounts = new int[slotLimit];
         for (int slot = 0; slot < slotLimit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            stackCounts[slot] = stack.is(item) ? stack.getCount() : 0;
         }

         // Never the selected slot: emptying the stack in hand let an inventory mod
         // refill it during the transaction, which hid the result and paused the job.
         int sourceSlot = materialDepositSource(allowed, stackCounts, inventory.getSelectedSlot());
         if (sourceSlot >= 0) {
            int sourceCount = stackCounts[sourceSlot];
            MaterialStorageBoxCandidate box = findMaterialStorageBoxCandidate(mc, item, sourceCount);
            if (box != null) {
               if (!this.beginMaterialStore(mc, this.baseTaskState(), item, sourceSlot, box, true, currentCount - allowed)) {
                  // Usually a cursor or screen is still busy. Overflow storage is not worth a
                  // 30-second pause; try again shortly and let the work continue meanwhile.
                  this.materialStorageCooldownTicks = 40;
                  TerraLevelerDiagnostics.log("MATERIAL", "pickup-overflow-store-deferred item=" + materialName(item)
                     + " loose=" + currentCount + " allowed=" + allowed + " boxSlot=" + box.slot()
                     + " carried=" + !mc.player.inventoryMenu.getCarried().isEmpty());
                  return false;
               }
               TerraLevelerDiagnostics.log(
                  "MATERIAL",
                  "pickup-overflow-store item=" + materialName(item)
                     + " loose=" + currentCount
                     + " allowed=" + allowed
                     + " stack=" + sourceCount
                     + " boxSlot=" + box.slot()
               );
               return true;
            }
         }

         // Without storage room, only material the job can no longer use is dropped:
         // fill/scaffold demand that the boxes cannot supply stays loose, unless the
         // inventory is nearly full (then the plain allowance applies).
         int keep = this.configuredMaterialKeepLimit(mc, item);
         if (currentCount <= keep) continue;
         // Whole stacks only, thrown away from the work area. An excess smaller
         // than one stack is tolerated rather than split or dropped item by item.
         int discardSlot = wholeDiscardablePickupStack(inventory, item, currentCount, keep, 0, slotLimit);
         Slot menuSlot = discardSlot < 0 ? null : inventoryMenuSlot(mc, discardSlot);
         if (menuSlot == null || !this.stablePlayerStand(mc)) continue;
         DiscardView discardView = selectSafeDiscardView(mc, this.inventoryDiscardUrgent(inventory));
         if (discardView == null) continue;
         ItemStack stack = inventory.getItem(discardSlot);
         AutoNavigator.release(mc);
         this.directedItemDiscardTransaction = DirectedItemDiscardTransaction.begin(
            item, List.of(new DirectedItemDiscardTransaction.Source(menuSlot.index, stack.getCount())),
            discardView.yaw(), discardView.pitch()
         );
         this.materialStorageCooldownTicks = INVENTORY_DISCARD_SETTLE_TICKS;
         TerraLevelerDiagnostics.log(
            "MATERIAL",
            "pickup-overflow-discard-stack item=" + materialName(item)
               + " slot=" + discardSlot
               + " stack=" + stack.getCount()
               + " looseBefore=" + currentCount
               + " allowed=" + allowed
               + " keep=" + keep
               + " baseline=" + baseline
               + " directed=true"
         );
         return true;
      }
      return false;
   }

   /**
    * Loose amount of a configured material that is never discarded: the normal
    * allowance plus the part of the remaining job demand (flatten surface or trench
    * wall, and the free scaffold budget) that the carried boxes cannot supply.
    * 0.8.70 kept the whole demand loose, so excavated dirt filled the inventory
    * although the boxes already held far more than the fill needed.
    */
   private int configuredMaterialKeepLimit(Minecraft mc, Item item) {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      int limit = config == null ? 64 : Math.max(0, config.pickupItemLimit);
      int baseline = this.retainedInventoryBaseline.getOrDefault(item, 0);
      Inventory inventory = mc.player.getInventory();
      return configuredMaterialKeepLimit(
         configuredPickupAllowance(limit, baseline),
         this.remainingConstructionDemand(item),
         storedMaterialSupply(inventory, item),
         inventoryUnderPressure(emptyMainInventorySlots(inventory))
      );
   }

   private int nearbyPickupLimit(Minecraft mc, Item item) {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      int limit = config == null ? 64 : Math.max(0, config.pickupItemLimit);
      Inventory inventory = mc.player.getInventory();
      int demand = this.remainingConstructionDemand(item);
      boolean supplyShort = inventoryCount(mc, item) + storedMaterialSupply(inventory, item) < demand;
      return nearbyPickupLimit(limit, this.configuredMaterialKeepLimit(mc, item), supplyShort,
         inventoryUnderPressure(emptyMainInventorySlots(inventory)));
   }

   private boolean storeAgainSoon(Minecraft mc, Item item) {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      int limit = config == null ? 64 : Math.max(0, config.pickupItemLimit);
      int allowance = configuredPickupAllowance(limit, this.retainedInventoryBaseline.getOrDefault(item, 0));
      return storeAgainSoon(inventoryCount(mc, item), allowance, emptyMainInventorySlots(mc.player.getInventory()));
   }

   static boolean storeAgainSoon(int loose, int allowance, int emptySlots) {
      return loose > allowance + 64 || inventoryUnderPressure(emptySlots) && loose > allowance;
   }

   static int configuredMaterialKeepLimit(int allowance, int remainingDemand, int storedSupply, boolean inventoryPressure) {
      if (inventoryPressure) return Math.max(0, allowance);
      return Math.max(0, allowance) + Math.max(0, remainingDemand - Math.max(0, storedSupply));
   }

   /**
    * Loose amount up to which TerraLeveler walks to nearby drops of a configured material:
    * the configured limit, or the keep limit while the boxes cannot cover the job.
    * Never while the inventory is nearly full.
    */
   static int nearbyPickupLimit(int configuredLimit, int keepLimit, boolean supplyShort, boolean inventoryPressure) {
      if (inventoryPressure) return 0;
      return supplyShort ? Math.max(configuredLimit, keepLimit) : Math.max(0, configuredLimit);
   }

   /** Items of this kind inside carried shulker boxes that quickShulker can take out again. */
   private static int storedMaterialSupply(Inventory inventory, Item item) {
      if (quickShulkerUnsupported || item == null) return 0;
      int supply = 0;
      for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
         supply += totalContainedItemCount(inventory.getItem(slot), item);
      }
      return supply;
   }

   static int emptyMainInventorySlots(Inventory inventory) {
      int empty = 0;
      for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
         if (inventory.getItem(slot).isEmpty()) empty++;
      }
      return empty;
   }

   static boolean inventoryUnderPressure(int emptySlots) {
      return emptySlots < INVENTORY_FREE_SLOT_RESERVE;
   }

   /**
    * Queued pickups are normally thrown only when they land clear of the work area.
    * They are thrown from anywhere once the inventory is nearly full or the job has
    * no construction work left.
    */
   private boolean inventoryDiscardUrgent(Inventory inventory) {
      return inventoryUnderPressure(emptyMainInventorySlots(inventory)) || this.constructionWorkExhausted();
   }

   private boolean constructionWorkExhausted() {
      return this.excavationIndex >= this.excavationTargets.size()
         && this.wallIndex >= this.wallTargets.size();
   }

   private boolean hasDiscardablePendingJunk(Inventory inventory) {
      int slotLimit = Math.min(36, inventory.getContainerSize());
      for (Entry<Item, Integer> entry : this.pendingUnexpectedPickupDiscard.entrySet()) {
         if (entry.getValue() > 0 && inventoryHasDiscardableStack(inventory, entry.getKey(), slotLimit)) return true;
      }
      return false;
   }

   /** Construction pickups still carried are thrown out before the job reports completion (bounded). */
   private boolean discardPendingJunkBeforeCompletion(Minecraft mc) {
      if (mc.player == null || this.completionJunkDiscardTicks >= MAX_COMPLETION_JUNK_DISCARD_TICKS) return false;
      if (this.directedItemDiscardTransaction != null) {
         this.completionJunkDiscardTicks++;
         return true;
      }
      Inventory inventory = mc.player.getInventory();
      prunePendingUnexpectedPickups(mc);
      if (!this.hasDiscardablePendingJunk(inventory)) return false;
      this.completionJunkDiscardTicks++;
      if (this.inventoryDiscardSettleTicks <= 0 && mc.player.inventoryMenu.getCarried().isEmpty()
         && mc.player.containerMenu == mc.player.inventoryMenu) {
         this.discardUnexpectedItemType(mc, inventory, true);
      }
      if (this.directedItemDiscardTransaction == null) {
         this.detail = "完成前清理施工期间捡到的杂物：等待安全投掷方向";
      }
      return true;
   }

   /**
    * True when a carried stack is a queued construction pickup that may be dropped
    * whole: unprotected, and the inventory alone still covers the baseline.
    */
   static boolean mayDropCarriedPickup(boolean protectedItem, boolean queuedPickup,
                                       int inventoryCountWithoutCarried, int baseline) {
      return !protectedItem && queuedPickup && inventoryCountWithoutCarried >= Math.max(0, baseline);
   }

   /**
    * A carried construction material (dirt) may be dropped only while the inventory
    * alone still holds its loose allowance; it is surplus the job was storing anyway.
    */
   static boolean mayDropCarriedMaterialExcess(boolean protectedItem, int inventoryCountWithoutCarried, int allowance) {
      return !protectedItem && inventoryCountWithoutCarried >= Math.max(0, allowance);
   }

   /**
    * Throws one whole stack of a queued construction pickup (ctrl+Q on its slot) so a
    * transaction that needs an empty slot can start in a stone-filled inventory. Thrown
    * stacks cannot be picked up again for two seconds, longer than the transaction.
    */
   private boolean throwJunkStackForSpace(Minecraft mc, String reason) {
      if (mc.gameMode == null || mc.player.containerMenu != mc.player.inventoryMenu
         || !mc.player.inventoryMenu.getCarried().isEmpty() || this.inventoryDiscardSettleTicks > 0) return false;
      int slot = this.droppableJunkSlot(mc, null);
      Slot menuSlot = slot < 0 ? null : inventoryMenuSlot(mc, slot);
      if (menuSlot == null) return false;
      ItemStack stack = mc.player.getInventory().getItem(slot);
      Item item = stack.getItem();
      int count = stack.getCount();
      mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index, 1, ContainerInput.THROW, mc.player);
      this.inventoryDiscardSettleTicks = INVENTORY_DISCARD_SETTLE_TICKS;
      int remaining = this.pendingUnexpectedPickupDiscard.getOrDefault(item, 0) - count;
      if (remaining > 0) {
         this.pendingUnexpectedPickupDiscard.put(item, remaining);
      } else {
         removePendingUnexpectedPickup(item);
      }
      TerraLevelerDiagnostics.log("MATERIAL", "space-junk-thrown item=" + materialName(item) + " slot=" + slot + " count=" + count
         + " reason=" + reason);
      return true;
   }

   /** A main-inventory slot holding a queued construction pickup that may be dropped whole, or -1. */
   private int droppableJunkSlot(Minecraft mc, Item excluded) {
      Inventory inventory = mc.player.getInventory();
      for (int candidate = 9; candidate < Math.min(36, inventory.getContainerSize()); candidate++) {
         ItemStack stack = inventory.getItem(candidate);
         if (stack.isEmpty() || isShulkerBoxStack(stack) || stack.is(excluded)) continue;
         Item item = stack.getItem();
         if (isManagedPickupMaterial(item)) continue;
         int inventoryWithout = inventoryCount(mc, item) - stack.getCount();
         if (mayDropCarriedPickup(
            isLooseDiscardProtected(item, TerraLeveler.CONFIG) || isProtectedRetainedInventoryItem(item),
            this.pendingUnexpectedPickupDiscard.getOrDefault(item, 0) > 0,
            inventoryWithout,
            this.retainedInventoryBaseline.getOrDefault(item, 0)
         )) {
            return candidate;
         }
      }
      return -1;
   }

   private int remainingConstructionDemand(Item item) {
      if (item == null) return 0;
      int demand = 0;
      int remainingWall = Math.max(0, this.wallTargets.size() - this.wallIndex);
      if (isFlattenSurfaceMaterial(item)) {
         demand += remainingWall;
      }
      if (validScaffold(item)) {
         demand += Math.min(64, Math.max(0, this.jobScaffoldBudget - this.activeScaffoldCount()));
      }
      return demand;
   }

   private boolean discardEnabledMetalItem(Minecraft mc, Inventory inventory) {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      if (config == null || !config.discardGoldItems && !config.discardIronItems) return false;
      int slotLimit = Math.min(36, inventory.getContainerSize());
      for (int slot = 0; slot < slotLimit; slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (stack.isEmpty() || slot == inventory.getSelectedSlot()
            || !InventoryRetentionRules.isEnabledMetalDiscard(stack.getItem(), config)) continue;
         Slot menuSlot = inventoryMenuSlot(mc, slot);
         if (menuSlot == null) continue;
         int count = stack.getCount();
         if (!this.stablePlayerStand(mc)) return false;
         DiscardView discardView = selectSafeDiscardView(mc, this.inventoryDiscardUrgent(inventory));
         if (discardView == null) {
            return false;
         }
         AutoNavigator.release(mc);
         this.directedItemDiscardTransaction = DirectedItemDiscardTransaction.begin(
            stack.getItem(), List.of(new DirectedItemDiscardTransaction.Source(menuSlot.index, count)),
            discardView.yaw(), discardView.pitch()
         );
         TerraLevelerDiagnostics.log("MATERIAL", "directed-discard-metal-start item=" + materialName(stack.getItem())
            + " slot=" + slot + " count=" + count + " yaw=" + discardView.yaw() + " pitch=" + discardView.pitch());
         return true;
      }
      return false;
   }

   private static int wholeDiscardablePickupStack(Inventory inventory, Item item,
                                                   int currentCount, int baseline, int limit, int slotLimit) {
      for (int slot = 0; slot < slotLimit; slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (slot != inventory.getSelectedSlot() && stack.is(item)
            && mayDropConfiguredPickupStack(currentCount, baseline, limit, stack.getCount())) {
            return slot;
         }
      }
      return -1;
   }

   static int configuredPickupAllowance(int limit, int retainedBaseline) {
      return Math.max(Math.max(0, limit), Math.max(0, retainedBaseline));
   }

   static boolean mayDropConfiguredPickupStack(int currentCount, int retainedBaseline,
                                                int limit, int stackCount) {
      return currentCount > configuredPickupAllowance(limit, retainedBaseline)
         && stackCount > 0
         && currentCount - stackCount >= Math.max(0, retainedBaseline);
   }

   static boolean mayDiscardConfiguredPickupSingle(int currentCount, int retainedBaseline, int limit) {
      return currentCount > configuredPickupAllowance(limit, retainedBaseline)
         && currentCount - 1 >= Math.max(0, retainedBaseline);
   }

   /**
    * Drops every loose stack of an unexpected, non-protected item type as a bulk action.
    * Ordinary incidental materials are intentionally not protected by the construction baseline.
    */
   private boolean discardUnexpectedItemType(Minecraft mc, Inventory inventory, boolean urgent) {
      Item item = null;
      int slotLimit = Math.min(36, inventory.getContainerSize());
      for (Entry<Item, Integer> entry : this.pendingUnexpectedPickupDiscard.entrySet()) {
         if (entry.getValue() > 0 && inventoryHasDiscardableStack(inventory, entry.getKey(), slotLimit)) {
            item = entry.getKey();
            break;
         }
      }
      if (item == null) return false;

      int currentCount = inventoryCount(mc, item);
      int pending = Math.min(currentCount, this.pendingUnexpectedPickupDiscard.getOrDefault(item, 0));
      if (pending <= 0) {
         return false;
      }
      this.pendingUnexpectedPickupDiscard.put(item, pending);
      if (!this.stablePlayerStand(mc)) return false;

      DiscardView discardView = selectSafeDiscardView(mc, urgent);
      if (discardView == null) {
         // Carried until a throw lands clear of the work area (usually at its edge):
         // stacks thrown inside the area were collected again on the next pass.
         if (this.deferredJunkDiscardItem != item) {
            this.deferredJunkDiscardItem = item;
            TerraLevelerDiagnostics.log("MATERIAL", "directed-discard-deferred item=" + materialName(item) + " pending=" + pending
               + " emptySlots=" + emptyMainInventorySlots(inventory) + " urgent=" + urgent);
         }
         return false;
      }
      this.deferredJunkDiscardItem = null;

      // Every queued pickup type is thrown in the same aimed stop: one turn instead of one
      // per type, and the inventory refilled with stone between 0.8.71's separate throws.
      Set<Item> junkTypes = new HashSet<>();
      for (Entry<Item, Integer> entry : this.pendingUnexpectedPickupDiscard.entrySet()) {
         if (entry.getValue() > 0 && !isLooseDiscardProtected(entry.getKey(), TerraLeveler.CONFIG)) {
            junkTypes.add(entry.getKey());
         }
      }
      junkTypes.add(item);
      List<DirectedItemDiscardTransaction.Source> sources = new ArrayList<>();
      for (int slot = 0; slot < slotLimit; slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (slot == inventory.getSelectedSlot() || stack.isEmpty() || !junkTypes.contains(stack.getItem())) continue;
         Slot menuSlot = inventoryMenuSlot(mc, slot);
         if (menuSlot == null) continue;
         sources.add(new DirectedItemDiscardTransaction.Source(menuSlot.index, stack.getCount(), stack.getItem()));
      }

      if (sources.isEmpty()) return false;
      AutoNavigator.release(mc);
      this.directedItemDiscardTransaction = DirectedItemDiscardTransaction.begin(
         item, sources, discardView.yaw(), discardView.pitch()
      );
      TerraLevelerDiagnostics.log(
         "MATERIAL",
         "directed-discard-start item=" + materialName(item)
            + " types=" + junkTypes.size()
            + " stacks=" + sources.size()
            + " count=" + sources.stream().mapToInt(DirectedItemDiscardTransaction.Source::count).sum()
            + " pending=" + pending
            + " current=" + currentCount
            + " emptySlots=" + emptyMainInventorySlots(inventory)
            + " urgent=" + urgent
            + " yaw=" + discardView.yaw()
            + " pitch=" + discardView.pitch()
      );
      return true;
   }

   private void prunePendingUnexpectedPickups(Minecraft mc) {
      long now = mc.level == null ? 0L : mc.level.getGameTime();
      this.pendingUnexpectedPickupDiscard.entrySet().removeIf(entry -> {
         Item item = entry.getKey();
         if (isLooseDiscardProtected(item, TerraLeveler.CONFIG)) {
            clearPendingUnexpectedPickupMetadata(item);
            return true;
         }
         if (inventoryCount(mc, item) > 0) {
            this.pendingUnexpectedPickupObserved.add(item);
            return false;
         }
         long graceUntil = this.pendingUnexpectedPickupGraceUntil.getOrDefault(item, now);
         if (shouldRetainInvisibleUnexpectedPickup(
            this.pendingUnexpectedPickupObserved.contains(item), 0, now, graceUntil
         )) return false;
         clearPendingUnexpectedPickupMetadata(item);
         return true;
      });
   }

   static boolean shouldRetainInvisibleUnexpectedPickup(boolean observedVisible, int inventoryCount,
                                                         long currentTick, long graceUntilTick) {
      return inventoryCount > 0 || !observedVisible && currentTick < graceUntilTick;
   }

   private void removePendingUnexpectedPickup(Item item) {
      this.pendingUnexpectedPickupDiscard.remove(item);
      clearPendingUnexpectedPickupMetadata(item);
   }

   private void movePendingUnexpectedPickupToTail(Item item) {
      Integer pending = this.pendingUnexpectedPickupDiscard.remove(item);
      if (pending != null && pending > 0) this.pendingUnexpectedPickupDiscard.put(item, pending);
   }

   private void clearPendingUnexpectedPickupMetadata(Item item) {
      this.pendingUnexpectedPickupGraceUntil.remove(item);
      this.pendingUnexpectedPickupObserved.remove(item);
   }

   private static boolean inventoryHasDiscardableStack(Inventory inventory, Item item, int slotLimit) {
      for (int slot = 0; slot < slotLimit; slot++) {
         if (slot != inventory.getSelectedSlot() && inventory.getItem(slot).is(item)) return true;
      }
      return false;
   }

   /**
    * Picks a clear throw whose landing neighbourhood lies outside the work area. The
    * old choice aimed away from the area centre, which along a long narrow area
    * mostly landed inside it. Without such a throw null is returned, unless
    * {@code allowInside} (inventory nearly full or work finished) accepts the clear
    * throw landing closest to the outside.
    */
   private DiscardView selectSafeDiscardView(Minecraft mc, boolean allowInside) {
      WorkArea area = this.workArea();
      if (area == null || mc.level == null || mc.player == null) return null;
      // Ties (and throws that must land inside) go away from the next work target, which
      // is where the player walks next; the area centre is only the fallback.
      BlockPos nextWork = this.nextWorkTargetForDiscard();
      double avoidX = nextWork != null ? nextWork.getX() + 0.5 : (area.minX() + area.maxX() + 1.0) * 0.5;
      double avoidZ = nextWork != null ? nextWork.getZ() + 0.5 : (area.minZ() + area.maxZ() + 1.0) * 0.5;
      float baseYaw = discardYawAwayFrom(mc.player.getX(), mc.player.getZ(), avoidX, avoidZ, mc.player.getYRot());
      float bestYaw = 0.0F;
      int bestScore = -1;
      for (int step = 0; step < 16; step++) {
         // 0, +22.5, -22.5, +45, ... so ties keep the direction away from the centre.
         float offset = (step + 1) / 2 * 22.5F * (step % 2 == 0 ? 1.0F : -1.0F);
         float yaw = Mth.wrapDegrees(baseYaw + offset);
         if (!discardTrajectoryClear(mc, yaw, DIRECTED_DISCARD_PITCH)) continue;
         int score = discardLandingOutsideScore(area::containsColumn, mc.player.getX(), mc.player.getZ(), yaw);
         if (score > bestScore) {
            bestScore = score;
            bestYaw = yaw;
         }
      }
      if (bestScore < 0) return null;
      if (bestScore < discardLandingFullScore() && !allowInside) return null;
      return new DiscardView(bestYaw, DIRECTED_DISCARD_PITCH);
   }

   private BlockPos nextWorkTargetForDiscard() {
      if (this.excavationIndex < this.excavationTargets.size()) return this.excavationTargets.get(this.excavationIndex);
      if (this.wallIndex < this.wallTargets.size()) return this.wallTargets.get(this.wallIndex);
      return null;
   }

   static int discardLandingFullScore() {
      int side = 2 * DISCARD_OUTSIDE_MARGIN + 1;
      return side * side;
   }

   /**
    * Number of columns around the estimated landing point (within DISCARD_OUTSIDE_MARGIN)
    * that are outside the work area; the full score means the stack lands beyond the
    * reach of a player walking along the area edge.
    */
   static int discardLandingOutsideScore(java.util.function.BiPredicate<Integer, Integer> containsColumn,
                                         double playerX, double playerZ, float yaw) {
      double radians = Math.toRadians(yaw);
      int x = Mth.floor(playerX - Math.sin(radians) * DISCARD_THROW_DISTANCE);
      int z = Mth.floor(playerZ + Math.cos(radians) * DISCARD_THROW_DISTANCE);
      int outside = 0;
      for (int dx = -DISCARD_OUTSIDE_MARGIN; dx <= DISCARD_OUTSIDE_MARGIN; dx++) {
         for (int dz = -DISCARD_OUTSIDE_MARGIN; dz <= DISCARD_OUTSIDE_MARGIN; dz++) {
            if (!containsColumn.test(x + dx, z + dz)) outside++;
         }
      }
      return outside;
   }

   static float discardYawAwayFrom(double playerX, double playerZ, double avoidX, double avoidZ, float fallbackYaw) {
      double dx = playerX - avoidX;
      double dz = playerZ - avoidZ;
      if (dx * dx + dz * dz < 0.01) return Mth.wrapDegrees(fallbackYaw + 180.0F);
      return Mth.wrapDegrees((float)Math.toDegrees(Math.atan2(-dx, dz)));
   }

   private static boolean discardTrajectoryClear(Minecraft mc, float yaw, float pitch) {
      Vec3 eye = mc.player.getEyePosition();
      double yawRadians = Math.toRadians(yaw);
      double pitchRadians = Math.toRadians(pitch);
      double horizontal = Math.cos(pitchRadians);
      double dx = -Math.sin(yawRadians) * horizontal;
      double dy = -Math.sin(pitchRadians);
      double dz = Math.cos(yawRadians) * horizontal;
      for (double distance = 0.75; distance <= 4.0; distance += 0.5) {
         BlockPos sample = BlockPos.containing(
            eye.x + dx * distance, eye.y + dy * distance, eye.z + dz * distance
         );
         if (!mc.level.hasChunkAt(sample)
            || !mc.level.getBlockState(sample).getCollisionShape(mc.level, sample).isEmpty()) return false;
      }
      return true;
   }

   private static long currentClientGameTime() {
      Minecraft mc = Minecraft.getInstance();
      return mc.level == null ? 0L : mc.level.getGameTime();
   }

   private record DiscardView(float yaw, float pitch) {
   }

   /**
    * Set once the server did not open a material box through QuickShulker (QuickShulker is
    * missing on the server). Until the world changes no further box transaction is
    * started just to time out again; filling digs dirt inside the selection instead.
    */
   private static boolean quickShulkerUnsupported;

   private static void markQuickShulkerUnsupported() {
      if (!quickShulkerUnsupported) {
         TerraLevelerDiagnostics.log("MATERIAL", "quick-shulker-unsupported-on-server further-box-transactions=disabled");
         Minecraft mc = Minecraft.getInstance();
         if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
               "TerraLeveler：服务器没有打开潜影盒（服务器可能没装 QuickShulker），本次连接内不再从潜影盒存取材料；缺土时改为挖选区内高于目标高度的土。"));
         }
      }
      quickShulkerUnsupported = true;
   }

   private static List<String> cachedPickupItemIds;
   private static List<Item> cachedPickupItems = List.of();

   private static List<Item> configuredPickupItems(TerraLevelerConfig config) {
      // Called for every inventory slot on every tick; parse the ids only when they change.
      List<String> source = config == null ? null : config.pickupItems;
      if (source != null && source.equals(cachedPickupItemIds)) return cachedPickupItems;
      List<Item> parsed = parsePickupItems(config);
      if (source != null) {
         cachedPickupItemIds = List.copyOf(source);
         cachedPickupItems = parsed;
      }
      return parsed;
   }

   private static List<Item> parsePickupItems(TerraLevelerConfig config) {
      List<Item> items = new ArrayList<>();
      List<String> ids = config == null ? List.of("minecraft:dirt") : TerraLevelerConfig.sanitizeItemIds(config.pickupItems);
      for (String id : ids) {
         Item configured = item(id);
         if (configured != null && !items.contains(configured)) items.add(configured);
      }
      if (items.isEmpty()) items.add(Blocks.DIRT.asItem());
      return List.copyOf(items);
   }

   private List<Item> activePickupMaterialItems() {
      return this.jobPickupItems.isEmpty() ? configuredPickupItems(TerraLeveler.CONFIG) : this.jobPickupItems;
   }

   private static String pickupMaterialFingerprint(TerraLevelerConfig config) {
      if (config == null) return "minecraft:dirt#64";
      List<String> ids = TerraLevelerConfig.sanitizeItemIds(config.pickupItems);
      return String.join("|", ids) + "#" + Math.max(0, config.pickupItemLimit);
   }

   private static String configuredPickupMaterialNames() {
      List<Item> items = configuredPickupItems(TerraLeveler.CONFIG);
      return items.stream().map(ConstructionController::materialName).sorted().toList().toString();
   }

   static boolean isManagedConstructionTool(Item item) {
      return item != null && ToolBoxTransaction.toolKind(new ItemStack(item)).managed();
   }

   static boolean isRetainedInventoryBaselineItem(Item item) {
      return item != null
         && (isProtectedRetainedInventoryItem(item) || isManagedConstructionMaterial(item));
   }

   private static boolean isManagedConstructionMaterial(Item item) {
      return isConfiguredConstructionMaterial(item);
   }

   private static boolean isConfiguredConstructionMaterial(Item item) {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      return configuredPickupItems(config).contains(item)
         || config != null && item == configuredItem(config.scaffoldBlock);
   }

   private static Item configuredItem(String id) {
      if (id == null || id.isBlank()) return null;
      return item(id);
   }

   public static boolean isManagedPickupMaterial(Item item) {
      return item != null && configuredPickupItems(TerraLeveler.CONFIG).contains(item);
   }

   static boolean isProtectedRetainedInventoryItem(Item item) {
      return InventoryRetentionRules.isProtected(item, TerraLeveler.CONFIG);
   }

   private static boolean isLooseDiscardProtected(Item item, TerraLevelerConfig config) {
      return InventoryRetentionRules.isProtected(item, config)
         || isManagedConstructionTool(item) && !InventoryRetentionRules.isEnabledMetalDiscard(item, config);
   }

   static int unwantedInventoryExcess(int retainedBaseline, int currentCount) {
      return Math.max(0, currentCount - Math.max(0, retainedBaseline));
   }

   static int safePendingDiscardAmount(int retainedBaseline, int currentCount, int pending) {
      return Math.min(Math.max(0, pending), unwantedInventoryExcess(retainedBaseline, currentCount));
   }

   
   static int discardButtonForInventoryExcess(int retainedBaseline, int stackCount, int excess) {
      return retainedBaseline == 0 && excess >= stackCount ? 1 : 0;
   }

   static int discardButtonForInventoryExcess(int retainedBaseline, int currentCount,
                                              int stackCount, int excess) {
      return canDiscardWholeInventoryStack(retainedBaseline, currentCount, stackCount, excess) ? 1 : 0;
   }

   static boolean canDiscardWholeInventoryStack(int retainedBaseline, int currentCount,
                                                int stackCount, int discardAmount) {
      return stackCount > 0
         && discardAmount > 0
         && currentCount - stackCount >= Math.max(0, retainedBaseline);
   }

   static int discardButtonForForcedInventoryPickup(int forcedDiscard, int stackCount) {
      return forcedDiscard > 0 && stackCount > 0 && forcedDiscard >= stackCount ? 1 : 0;
   }

   private static int inventoryCountOutsideSlot(Minecraft mc, Item item, int excludedSlot) {
      if (mc.player == null || item == null) return 0;
      int count = 0;
      Inventory inventory = mc.player.getInventory();
      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         if (slot != excludedSlot) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
         }
      }
      return count;
   }

   private static int findEmptyInventorySlot(Minecraft mc, int excludedSlot) {
      if (mc.player == null) return -1;
      Inventory inventory = mc.player.getInventory();
      for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
         if (slot != excludedSlot && inventory.getItem(slot).isEmpty()) return slot;
      }
      return -1;
   }

   static int protectedBaselineInStack(int retainedBaseline, int stackCount, int countOutsideStack) {
      return Math.min(stackCount, Math.max(0, retainedBaseline - countOutsideStack));
   }

   private boolean maybeBeginNearbyMaterialPickup(Minecraft mc) {
      if (this.dirtPickupCooldownTicks > 0 || mc.player == null || mc.level == null) return false;
      BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (start == null) return false;
      SafeRoutePlanner.RoutePolicy policy = SafeRoutePlanner.RoutePolicy.flatteningFloor();
      Item pickupItem = null;
      ConstructionController.NearbyPickup pickup = null;
      for (Item candidate : this.activePickupMaterialItems()) {
         if (!canAcceptLooseMaterial(mc, candidate, this.nearbyPickupLimit(mc, candidate))) continue;
         pickup = findNearbyPickup(mc, candidate, start, policy);
         if (pickup != null) {
            pickupItem = candidate;
            break;
         }
      }
      if (pickup == null || pickupItem == null) {
         // Nothing reachable: do not repeat the route searches on every idle tick.
         this.dirtPickupCooldownTicks = 10;
         return false;
      }
      ItemEntity target = pickup.entity();
      BlockPos goal = pickup.goal();
      if (start.equals(goal)) {
         AutoNavigator.moveToward(mc, goal, false);
         this.dirtPickupCooldownTicks = 20;
         return true;
      }
      ConstructionController.State next = this.baseTaskState();
      this.cancelOwnedWork(mc, "nearby-material-pickup");
      this.route = pickup.steps();
      this.routeIndex = 0;
      this.routeTaskKey = goal.immutable();
      this.routeReturnState = next;
      this.routePolicy = policy;
      this.routeRequiresTaskValidation = false;
      this.routeIsMaterialPickupDetour = true;
      this.routeNoProgressTicks = 0;
      this.routeCompletionDeferredTicks = 0;
      this.lastRouteDistance = Double.POSITIVE_INFINITY;
      this.state = ConstructionController.State.POSITIONING;
      this.resumeState = next;
      this.dirtPickupCooldownTicks = 40;
      TerraLevelerDiagnostics.log("MATERIAL", "pickup-detour item=" + materialName(pickupItem) + " entity=" + target.getId() + " goal=" + goal.toShortString());
      return true;
   }

   private ConstructionController.NearbyPickup findNearbyPickup(Minecraft mc, Item item, BlockPos start, SafeRoutePlanner.RoutePolicy policy) {
      List<ItemEntity> candidates = mc.level
         .getEntitiesOfClass(
            ItemEntity.class,
            mc.player.getBoundingBox().inflate(5.0, 2.0, 5.0),
            entityx -> entityx.isAlive() && !entityx.getItem().isEmpty() && entityx.getItem().is(item)
         );
      candidates.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
      int searched = 0;

      for (ItemEntity entity : candidates) {
         if (++searched > MAX_NEARBY_PICKUP_ROUTE_SEARCHES) break;
         BlockPos goal = pickupRouteGoal(mc, entity.blockPosition());
         if (goal != null) {
            if (start.equals(goal)) {
               return new ConstructionController.NearbyPickup(item, entity, goal, List.of());
            }

            // Only detours of at most four steps are accepted, so a small search suffices.
            SafeRoutePlanner.PlanResult plan = SafeRoutePlanner.findRouteWithinVisitLimit(
               mc, start, List.of(goal), this.workArea(), policy, NEARBY_PICKUP_ROUTE_VISITS
            );
            if (plan.hasRoute() && !plan.steps().isEmpty() && plan.steps().size() <= 4) {
               return new ConstructionController.NearbyPickup(item, entity, goal, plan.steps());
            }
         }
      }

      return null;
   }

   private static boolean canAcceptLooseMaterial(Minecraft mc, Item item, int keepLimit) {
      if (item == null) return false;
      Inventory inventory = mc.player.getInventory();
      if (isManagedPickupMaterial(item) && inventoryCount(mc, item) >= keepLimit) return false;
      boolean emptySlot = false;
      int[] matchingCounts = new int[Math.min(36, inventory.getContainerSize())];

      for (int slot = 0; slot < matchingCounts.length; slot++) {
         ItemStack stack = inventory.getItem(slot);
         if (stack.isEmpty()) {
            emptySlot = true;
         } else if (stack.is(item)) {
            matchingCounts[slot] = stack.getCount();
         }
      }

      return materialPickupHasCapacity(emptySlot, item.getDefaultMaxStackSize(), matchingCounts);
   }

   private static BlockPos pickupRouteGoal(Minecraft mc, BlockPos itemPosition) {
      for (int dy : new int[]{0, 1, -1}) {
         BlockPos candidate = itemPosition.offset(0, dy, 0);
         if (SafeRoutePlanner.canOccupy(mc, candidate) && SafeRoutePlanner.hasFloor(mc, candidate)) {
            return candidate.immutable();
         }
      }

      return null;
   }

   private void beginRoute(Minecraft mc, BlockPos taskKey, List<BlockPos> goals, ConstructionController.State returnState, TerraLevelerConfig config, String reason) {
      this.beginRoute(mc, taskKey, goals, returnState, config, reason, false);
   }

   private void beginRoute(
      Minecraft mc,
      BlockPos taskKey,
      List<BlockPos> goals,
      ConstructionController.State returnState,
      TerraLevelerConfig config,
      String reason,
      boolean validateTaskAtGoal
   ) {
      this.beginRoute(mc, taskKey, goals, returnState, config, reason, validateTaskAtGoal, Set.of(), 0);
   }

   private void beginRoute(
      Minecraft mc,
      BlockPos taskKey,
      List<BlockPos> goals,
      ConstructionController.State returnState,
      TerraLevelerConfig config,
      String reason,
      boolean validateTaskAtGoal,
      Set<BlockPos> forbiddenScaffoldPlacements,
      int topologyReplanAttempt
   ) {
      if (!this.checkpointFlattenCleanupOutboundPosition(mc, "route-plan", true)
         && this.hasActiveFlattenCleanupExcursion()) {
         this.returnFromFlattenCleanupExcursion(mc, taskKey, "checkpoint-rejected-before-route");
         return;
      }

      this.cancelOwnedWork(mc, "route-plan");
      this.refreshParkedScaffoldAllowance(taskKey, returnState);
      BlockPos start = PlayerPhysicalState.routeStart(mc).orElse(null);
      if (start == null) {
         if (this.stablePlayerStand(mc)) {
            this.pause(mc, "真实玩家站在半砖、台阶低面或其他非整数高度碰撞面上，无法建立安全路线网格起点");
         } else {
            this.beginFallRecovery(mc, returnState, "路线起点没有可确认的真实碰撞支撑");
         }
      } else {
         if (this.activeScaffoldCount() >= this.jobScaffoldBudget && this.activeScaffoldCount() > 0) {
            this.pruneLoadedStaleScaffolds(mc, "route-budget-preflight");
         }

         if (this.beginSkyBucketDescent(mc, taskKey, start, returnState, reason)) {
            return;
         }

         if (this.beginFloatingTargetPillar(mc, config, taskKey, start, returnState)) {
            return;
         }

         boolean cleanupJourney = returnState == ConstructionController.State.CLEANING_SCAFFOLDS
            || returnState == ConstructionController.State.LOADING && this.loadingReturnState == ConstructionController.State.CLEANING_SCAFFOLDS;
         boolean combatJourney = returnState == ConstructionController.State.DEFENDING;
         boolean flattenFinalCleanupJourney = cleanupJourney && this.wallIndex >= this.wallTargets.size();
         boolean lastScaffoldDescent = mayUseLastScaffoldDescent(
            flattenFinalCleanupJourney,
            this.activeScaffoldCount(),
            this.flattenCleanupRouteReturnAnchor != null,
            taskKey,
            start
         );
         SafeRoutePlanner.RoutePolicy initialPolicy = lastScaffoldDescent
            ? SafeRoutePlanner.RoutePolicy.flatteningFloorWithMaximumDrop(
               DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(
                  mc.player.getHealth(), mc.player.getAbsorptionAmount()
               )
            )
            : this.excavationDeckPolicy(mc, taskKey, returnState, start);
         boolean flattenFinalCleanupBridgeAllowed = flattenFinalCleanupJourney
            && RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass);
          Item scaffold = this.preferredScaffoldItem(mc);
         int planningScaffoldBudget = mayBorrowRouteScaffoldBudget(returnState)
            && this.jobScaffoldBudget > 0
            ? this.maximumTemporaryExcavationRouteScaffoldBudget()
            : this.jobScaffoldBudget;
         boolean rollingReserve = this.hasRollingScaffoldCapacity(mc, null, planningScaffoldBudget);
          boolean fallbackEligible = !lastScaffoldDescent
             && RouteExecutionRules.allowsScaffoldFallback(combatJourney, cleanupJourney, flattenFinalCleanupBridgeAllowed)
             && !initialPolicy.allowBridge()
             && (flattenFinalCleanupBridgeAllowed || planningScaffoldBudget > this.activeScaffoldCount() || rollingReserve)
             && validScaffold(scaffold);
         SafeRoutePlanner.RoutePolicy bridgePolicy = !fallbackEligible
            ? null
            : (
               flattenFinalCleanupJourney
                  ? SafeRoutePlanner.RoutePolicy.flatteningFloorWithReversibleBridges()
                  : SafeRoutePlanner.RoutePolicy.flatteningFloorWithBridges()
            );
         NavigationRequest navigationRequest = new NavigationRequest(start, new NavigationGoal(taskKey, goals), this.workArea(), initialPolicy, bridgePolicy);
         if (lastScaffoldDescent) {
            TerraLevelerDiagnostics.log(
               "ROUTE",
               "last-scaffold-nonlethal-descent task="
                  + taskKey.toShortString()
                  + " start="
                  + start.toShortString()
                  + " maximumDrop="
                  + initialPolicy.maximumDrop()
                  + " health="
                  + mc.player.getHealth()
                  + " absorption="
                  + mc.player.getAbsorptionAmount()
            );
         }
         Set<BlockPos> planningForbidden = forbiddenScaffoldPlacements;
         if (!this.rejectedScaffoldPlacements.isEmpty()) {
            planningForbidden = new HashSet<>(forbiddenScaffoldPlacements);
            planningForbidden.addAll(this.rejectedScaffoldPlacements);
         }
         long routeGameTime = mc.level.getGameTime();
         boolean allowExpandedSearch = topologyReplanAttempt == 0
            && routeGameTime - this.lastExpandedRouteSearchTick >= EXPANDED_ROUTE_SEARCH_COOLDOWN_TICKS;
         NavigationPlan navigationPlan = NavigationPlanner.plan(mc, navigationRequest, planningForbidden, allowExpandedSearch);
         if (allowExpandedSearch && !navigationPlan.selected().hasProgressRoute()) {
            this.lastExpandedRouteSearchTick = routeGameTime;
         }
         SafeRoutePlanner.PlanResult natural = navigationPlan.primary();
         SafeRoutePlanner.PlanResult chosen = natural;
         SafeRoutePlanner.RoutePolicy chosenPolicy = initialPolicy;
         SafeRoutePlanner.PlanStatus bridgeAttemptStatus = initialPolicy.allowBridge()
            ? natural.status()
            : navigationPlan.fallbackResult().map(SafeRoutePlanner.PlanResult::status).orElse(null);
         if (navigationPlan.selectedFallback()) {
            SafeRoutePlanner.PlanResult bridgeRoute = navigationPlan.selected();
             int requiredScaffolds = countRequiredTemporaryScaffoldSteps(mc, bridgeRoute.steps());
             scaffold = this.preferredScaffoldItem(mc, requiredScaffolds);
             if (requiredScaffolds == 0) {
                chosenPolicy = navigationPlan.selectedPolicy();
                chosen = bridgeRoute;
             } else if (!this.ensureHotbarScaffold(mc, scaffold)) {
                if (flattenFinalCleanupJourney && this.hasActiveFlattenCleanupExcursion()) {
                   this.returnFromFlattenCleanupExcursion(mc, taskKey, "continuation-scaffold-unavailable");
                   return;
                }
                if (hasPotentialMaterialBox(mc, scaffold)) {
                   this.beginMaterialUnpack(mc, returnState, scaffold, requiredScaffolds);
                   return;
               }

               if (!RouteExecutionRules.mayRecoverWithoutScaffold(true, returnState == ConstructionController.State.BUILDING_WALL)
                  )
                {
                  this.pause(mc, "已找到选区外临时栈道路线，但圆石、泥土和配置脚手均不可用；沙子会坠落并填满矿洞，不能替代悬空栈道");
                  return;
               }

               TerraLevelerDiagnostics.log("FLATTEN", "bridge-skipped-missing-scaffold item=" + materialName(scaffold) + " fallback=escape-or-pillar");
            } else {
               chosenPolicy = navigationPlan.selectedPolicy();
               chosen = bridgeRoute;
            }

            if (chosen == bridgeRoute) {
               TerraLevelerDiagnostics.log(
                  "ROUTE",
                  "fallback-selected task="
                     + posText(taskKey)
                     + " natural="
                     + natural.status()
                     + " bridge="
                     + bridgeRoute.status()
                     + " steps="
                     + bridgeRoute.steps().size()
                     + " routeScaffolds="
                     + requiredScaffolds
                     + " maximumDrop="
                     + navigationPlan.selectedPolicy().maximumDrop()
               );
            }
         }

          if (chosen.status() == SafeRoutePlanner.PlanStatus.START_REQUIRES_RECOVERY) {
             this.beginFallRecovery(mc, returnState, "路线起点不是安全站位");
          } else if (!chosen.hasProgressRoute()) {
             if (returnState == ConstructionController.State.EXCAVATING
                && this.promoteFoliageAccessTarget(mc, taskKey, "route-" + chosen.status())) {
                return;
             }
             if (this.beginVerticalRecoveryFallback(mc, taskKey, start, returnState, config, reason)) {
                 return;
              }
             if (flattenFinalCleanupJourney && this.hasActiveFlattenCleanupExcursion()) {
                TerraLevelerDiagnostics.log(
                   "SCAFFOLD",
                   "flatten-cleanup-excursion-continuation-unreachable task="
                      + taskKey.toShortString()
                      + " routeStatus="
                      + chosen.status()
                      + " bridgeStatus="
                      + bridgeAttemptStatus
                );
                this.handleFinalCleanupTopologyFailure(mc, taskKey, start, "CONTINUATION_" + chosen.status());
                return;
             } else if (flattenFinalCleanupJourney && topologyReplanAttempt > 0) {
                TerraLevelerDiagnostics.log(
                  "SCAFFOLD",
                  "flatten-cleanup-topology-alternatives-exhausted task="
                     + taskKey.toShortString()
                     + " attempts="
                     + topologyReplanAttempt
                     + " forbiddenPlacements="
                     + forbiddenScaffoldPlacements.size()
                     + " routeStatus="
                     + chosen.status()
               );
               this.handleFinalCleanupTopologyFailure(mc, taskKey, start, "NO_ALTERNATIVE_" + chosen.status());
               return;
            }

            if (returnState != ConstructionController.State.BUILDING_WALL
               || !this.beginFlattenEscapeStep(mc, config, taskKey, start)) {
               if (returnState != ConstructionController.State.BUILDING_WALL
                  || !this.beginFlattenPillarRecovery(mc, returnState, start)) {
                  if (returnState != ConstructionController.State.EXCAVATING || this.strandedOnOwnScaffold(mc, start)
                     || !this.beginReachableRouteScaffoldCleanup(mc, taskKey, start)) {
                     this.logRouteFailureContext(mc, taskKey, start, goals, returnState, natural.status(), bridgeAttemptStatus, chosen.status());
                     if (returnState == ConstructionController.State.DEFENDING) {
                        LivingEntity target = this.defenseEntity(mc);
                        if (target != null) {
                           this.abandonUnreachableThreat(mc, config, target, "找不到安全接近路线（" + chosen.status() + "）");
                        } else {
                           this.finishDefense(mc, config, "战斗目标在规划路线时消失");
                        }
                      } else if (returnState == ConstructionController.State.LOADING) {
                         this.markLoadingRelayFailure(mc, taskKey, "中继站位没有安全可达路线");
                      } else if (returnState != ConstructionController.State.EXCAVATING
                         || !this.deferUnreachableExcavationTarget(mc, config, taskKey, chosen.status())
                            && !this.beginHighExcavationPillarAccess(mc, config, taskKey, start, chosen.status())) {
                         int failures = this.recordRouteFailure(taskKey, returnState);
                        this.routeRetryCooldownTicks = failures < 3 ? 10 : 0;
                        if (failures >= 3
                           && returnState == ConstructionController.State.CONTAINING_FLUID
                           && this.rotateOrDeferFlattenFluid(mc, "route-" + chosen.status())) {
                           this.clearRouteFailures();
                           return;
                        }
                        if (failures >= 3
                           && returnState == ConstructionController.State.CLEANING_SCAFFOLDS
                           && !this.hasActiveFlattenCleanupExcursion()
                           && this.parkUnreachableCleanupTarget(mc, taskKey, "route-" + chosen.status())) {
                           return;
                        }
                        if (failures >= 3) {
                           this.pause(
                              mc,
                              returnState == ConstructionController.State.CLEANING_SCAFFOLDS
                                 ? "确定性清场高差阻塞：连续三次找不到到达脚手 " + taskKey.toShortString()
                                    + " 的路线（" + chosen.status() + "；已试过步行、可回收脚手路线和用水桶下到低处）"
                                    + "；起点、目标和脚手账本未变化时不会自动重复"
                                 : "连续三次找不到安全路线（" + chosen.status() + "）；不会直走、抄近路或用沙子填矿洞"
                           );
                        } else {
                           this.state = returnState;
                           this.detail = "安全路线规划失败 " + chosen.status() + "，稍后重新规划（" + failures + "/3）";
                        }
                     }
                  }
               }
            }
         } else if (returnState != ConstructionController.State.EXCAVATING
            || !this.deferLongReachableExcavationRoute(mc, taskKey, start, chosen.steps().size())) {
            this.refreshStrandedEmergencyScaffoldDebt();
            int plannedBridgeSteps = countRequiredTemporaryScaffoldSteps(mc, chosen.steps());
            int remainingScaffoldBudget = this.jobScaffoldBudget - this.activeScaffoldCount();
            RouteExecutionRules.ScaffoldBudgetAssessment scaffoldBudgetAssessment = this.assessScaffoldRouteBudget(mc, chosen.steps());
            boolean rollingSwapAvailable = scaffoldBudgetAssessment.executable();
            int temporaryRouteScaffoldBudget = mayBorrowRouteScaffoldBudget(returnState)
               && plannedBridgeSteps > remainingScaffoldBudget
               && !scaffoldBudgetAssessment.executable()
               ? this.minimumExecutableScaffoldBudget(mc, chosen.steps())
               : 0;
            if (returnState == ConstructionController.State.EXCAVATING
               && plannedBridgeSteps > remainingScaffoldBudget
               && this.activeScaffoldCount() > 0
               && RouteExecutionRules.shouldCleanScaffoldBeforeBridge(rollingSwapAvailable)
               && this.beginReachableRouteScaffoldCleanup(mc, taskKey, start)) {
               TerraLevelerDiagnostics.log(
                  "SCAFFOLD",
                  "preflight-budget-cleanup task="
                     + taskKey.toShortString()
                     + " routeScaffolds="
                     + plannedBridgeSteps
                     + " remaining="
                     + remainingScaffoldBudget
                     + " active="
                     + this.activeScaffoldCount()
                     + "/"
                     + this.jobScaffoldBudget
                  );
              } else if (mayBorrowRouteScaffoldBudget(returnState)
                 && plannedBridgeSteps > remainingScaffoldBudget
                 && !scaffoldBudgetAssessment.executable()
                 && temporaryRouteScaffoldBudget <= 0) {
                 TerraLevelerDiagnostics.log(
                    "SCAFFOLD",
                   "preflight-budget-rejected task="
                      + taskKey.toShortString()
                      + " routeScaffolds="
                      + plannedBridgeSteps
                      + " remaining="
                      + remainingScaffoldBudget
                      + " blockedSupport="
                      + posText(scaffoldBudgetAssessment.blockedSupport())
                      + " blockedPlacement="
                      + posText(scaffoldBudgetAssessment.blockedPlacement())
                      + " blockedStep="
                      + (scaffoldBudgetAssessment.blockedStep() + 1)
                      + "/"
                      + chosen.steps().size()
                      + " peakActive="
                      + scaffoldBudgetAssessment.peakActive()
                      + " active="
                       + this.activeScaffoldCount()
                       + "/"
                       + this.jobScaffoldBudget
                       + " maxTemporaryBudget="
                       + this.maximumTemporaryExcavationRouteScaffoldBudget()
                 );
                if (returnState != ConstructionController.State.EXCAVATING
                   || !this.deferUnreachableExcavationTarget(mc, config, taskKey, SafeRoutePlanner.PlanStatus.NO_ROUTE)) {
                   if (this.rescueStrandedPlayer(mc, config, taskKey, start, returnState, reason)) {
                      return;
                   }
                   int failures = this.recordRouteFailure(taskKey, returnState);
                   this.routeRetryCooldownTicks = failures < MAX_ROUTE_FAILURES ? 10 : 0;
                   if (failures >= MAX_ROUTE_FAILURES) {
                      this.pause(mc, "临时脚手预算不足以安全执行当前路线；不会拆除仍被后续路线依赖的脚下支撑");
                   } else {
                      this.state = returnState;
                      this.resumeState = returnState;
                      this.detail = "临时脚手路线需要同时保留更多支撑，稍后重新规划（" + failures + "/" + MAX_ROUTE_FAILURES + "）";
                   }
                }
             } else if (this.guardRollingSkyBridge(mc, config, taskKey, start, returnState, chosen, plannedBridgeSteps)) {
                return;
             } else if (this.preferPillarForHighTarget(mc, config, taskKey, start, returnState, plannedBridgeSteps)) {
                return;
             } else if (this.guardClimbBudget(mc, config, taskKey, start, returnState, chosen.steps(), plannedBridgeSteps)) {
                return;
             } else {
                 scaffold = this.preferredScaffoldItem(mc, plannedBridgeSteps);
                 int looseScaffolds = inventoryCount(mc, scaffold);
                boolean materialBoxAvailable = hasPotentialMaterialBox(mc, scaffold);
                if (flattenFinalCleanupJourney
                   && !RouteExecutionRules.hasCleanupRouteMaterial(
                      plannedBridgeSteps, looseScaffolds, materialBoxAvailable
                   )) {
                   this.pause(
                      mc,
                      "最终清场往返路线需要 "
                         + plannedBridgeSteps
                         + " 个 "
                         + materialName(scaffold)
                         + "，背包只有 "
                         + looseScaffolds
                         + " 个且没有可用潜影盒；路线尚未接纳"
                   );
                } else if (plannedBridgeSteps > looseScaffolds && materialBoxAvailable) {
                   if (flattenFinalCleanupJourney && this.hasActiveFlattenCleanupExcursion()) {
                      TerraLevelerDiagnostics.log(
                         "MATERIAL",
                         "flatten-cleanup-excursion-resupply-in-place task="
                            + taskKey.toShortString()
                            + " required="
                            + plannedBridgeSteps
                            + " loose="
                            + looseScaffolds
                            + " routeCreated="
                            + this.flattenCleanupRouteScaffolds.size()
                      );
                   }
                   this.beginMaterialUnpack(mc, returnState, scaffold, plannedBridgeSteps - looseScaffolds);
                } else {
                  PathExecutionPlan executionPlan = PathExecutionPlan.admit(navigationRequest.goal(), chosen, chosenPolicy, validateTaskAtGoal);
                  boolean finalCleanupScaffoldJourney = returnState == ConstructionController.State.CLEANING_SCAFFOLDS
                     && this.wallIndex >= this.wallTargets.size()
                     && RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass);
                  boolean startsCleanupTransaction = finalCleanupScaffoldJourney
                     && plannedBridgeSteps > 0
                     && this.flattenCleanupRouteReturnAnchor == null;
                  boolean continuesCleanupTransaction = finalCleanupScaffoldJourney
                     && this.flattenCleanupRouteReturnAnchor != null
                     && this.isInActiveFlattenCleanupCluster(taskKey);
                  if (finalCleanupScaffoldJourney
                     && this.flattenCleanupRouteReturnAnchor != null
                     && !continuesCleanupTransaction) {
                     TerraLevelerDiagnostics.log(
                        "SCAFFOLD",
                        "flatten-cleanup-route-return-before-next-task previous="
                            + posText(this.flattenCleanupRouteTask)
                            + " next="
                            + taskKey.toShortString()
                            + " reason=excursion-boundary"
                      );
                     this.flattenCleanupRouteReturnPending = true;
                     this.beginFlattenCleanupRouteReturn(mc);
                     return;
                  }

                  if (startsCleanupTransaction || continuesCleanupTransaction) {
                     BlockPos topologyAnchor = continuesCleanupTransaction ? this.flattenCleanupRouteReturnAnchor : start;
                     List<SafeRoutePlanner.RouteStep> committed = continuesCleanupTransaction
                        ? List.copyOf(this.flattenCleanupOutboundRoute)
                        : List.of();
                     SafeRoutePlanner.RoutePolicy returnPolicy = new SafeRoutePlanner.RoutePolicy(
                        false, chosenPolicy.interiorPolicy(), chosenPolicy.minimumFeetY(), 1
                     );
                     RouteTopologySimulator.Result topology = RouteTopologySimulator.validate(
                        SafeRoutePlanner.routeEnvironment(mc),
                        this.workArea(),
                        topologyAnchor,
                        committed,
                        executionPlan,
                        returnPolicy,
                        true
                     );
                     if (!topology.valid()) {
                        int nextAttempt = topologyReplanAttempt + 1;
                        TerraLevelerDiagnostics.log(
                           "SCAFFOLD",
                           "flatten-cleanup-topology-candidate-rejected status="
                              + topology.status()
                              + " conflict="
                              + posText(topology.conflict())
                              + " conflictCell="
                              + topology.conflictCell()
                              + " stepStatus="
                              + topology.stepStatus()
                              + " task="
                              + taskKey.toShortString()
                              + " candidate="
                              + nextAttempt
                              + " replans="
                              + topologyReplanAttempt
                              + "/"
                              + MAX_FINAL_CLEANUP_TOPOLOGY_CONFLICTS
                        );
                         if ((topology.status() == RouteTopologySimulator.Status.PLACEMENT_OCCUPIES_RETURN_NODE
                               || topology.status() == RouteTopologySimulator.Status.PLACEMENT_CHANGES_RETURN_PATH)
                            && topology.conflicts().stream()
                               .anyMatch(conflict -> mayScheduleFlattenCleanupTopologyReplan(forbiddenScaffoldPlacements, conflict))) {
                            Set<BlockPos> nextForbidden = new HashSet<>(forbiddenScaffoldPlacements);
                            for (BlockPos conflict : topology.conflicts()) {
                               if (nextForbidden.size() >= MAX_FINAL_CLEANUP_TOPOLOGY_CONFLICTS) {
                                  break;
                               }
                               nextForbidden.add(conflict.immutable());
                            }
                            if (nextForbidden.size() > forbiddenScaffoldPlacements.size()) {
                               this.pendingFlattenCleanupReplan = new PendingFlattenCleanupReplan(
                                  taskKey.immutable(),
                                  List.copyOf(goals),
                                  returnState,
                                  reason,
                                  validateTaskAtGoal,
                                  Set.copyOf(nextForbidden),
                                  nextAttempt,
                                  start.immutable(),
                                  this.activeScaffoldCount()
                               );
                               this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
                               this.resumeState = this.state;
                               this.routeRetryCooldownTicks = 1;
                               this.detail = "最终清场候选与返程拓扑冲突；已保存约束，下一 tick 再计算一个候选（"
                                  + nextForbidden.size()
                                  + "/"
                                  + MAX_FINAL_CLEANUP_TOPOLOGY_CONFLICTS
                                  + "）";
                               return;
                            }
                         }

                        this.handleFinalCleanupTopologyFailure(mc, taskKey, start, topology.status().name());
                        return;
                     }

                      if (topologyReplanAttempt > 0) {
                        TerraLevelerDiagnostics.log(
                           "SCAFFOLD",
                           "flatten-cleanup-topology-alternative-selected task="
                              + taskKey.toShortString()
                              + " attempts="
                              + topologyReplanAttempt
                              + " forbiddenPlacements="
                              + forbiddenScaffoldPlacements.size()
                              + " steps="
                              + executionPlan.steps().size()
                        );
                      }

                      int transactionScaffolds = continuesCleanupTransaction ? this.flattenCleanupRouteScaffolds.size() : 0;
                      int deployedDebt = Math.max(0, this.activeScaffoldCount() - transactionScaffolds);
                      int routeReserve = transactionScaffolds + plannedBridgeSteps;
                      int candidatePeak = RouteExecutionRules.cleanupReservedPeak(deployedDebt, routeReserve);
                      if (candidatePeak < 0) {
                         this.pause(mc, "最终清场路线脚手预留数据无效；路线尚未接纳");
                         return;
                      }
                      this.flattenCleanupReservedScaffoldPeak = startsCleanupTransaction
                         ? candidatePeak
                         : Math.max(this.flattenCleanupReservedScaffoldPeak, candidatePeak);
                      TerraLevelerDiagnostics.log(
                         "SCAFFOLD",
                         "flatten-cleanup-route-reserved task="
                            + taskKey.toShortString()
                            + " deployedDebt="
                            + deployedDebt
                            + " routeReserve="
                            + routeReserve
                            + " reservedPeak="
                            + this.flattenCleanupReservedScaffoldPeak
                            + " normalBudget="
                            + this.jobScaffoldBudget
                            + " looseMaterial="
                            + looseScaffolds
                      );
                   }

                  if (continuesCleanupTransaction) {
                     TerraLevelerDiagnostics.log(
                        "SCAFFOLD",
                        "flatten-cleanup-excursion-route-extended initialTask="
                           + this.flattenCleanupRouteTask.toShortString()
                           + " next="
                           + taskKey.toShortString()
                           + " committedSteps="
                           + this.flattenCleanupOutboundRoute.size()
                           + " candidateSteps="
                           + executionPlan.steps().size()
                           + " plannedScaffolds="
                           + plannedBridgeSteps
                     );
                  }

                  if (startsCleanupTransaction) {
                     this.flattenCleanupRouteScaffolds.clear();
                     this.flattenCleanupExcursionDeferredTargets.clear();
                     this.flattenCleanupRouteRetireTarget = null;
                     this.flattenCleanupRouteReturnAnchor = start.immutable();
                     this.flattenCleanupRouteTask = taskKey.immutable();
                     this.flattenCleanupRouteReturnPending = false;
                     this.flattenCleanupOutboundRoute.clear();
                     TerraLevelerDiagnostics.log(
                        "SCAFFOLD",
                        "flatten-cleanup-route-outbound-start anchor="
                           + start.toShortString()
                           + " task="
                           + taskKey.toShortString()
                           + " plannedScaffolds="
                           + plannedBridgeSteps
                     );
                  }

                  this.routeReservedScaffoldBudget = temporaryRouteScaffoldBudget;
                  if (temporaryRouteScaffoldBudget > 0) {
                     RouteExecutionRules.ScaffoldBudgetAssessment reservedAssessment = this.assessScaffoldRouteBudget(
                        mc, chosen.steps(), temporaryRouteScaffoldBudget
                     );
                     TerraLevelerDiagnostics.log(
                        "SCAFFOLD",
                        "excavation-route-budget-reserved task="
                           + taskKey.toShortString()
                           + " normalBudget="
                           + this.jobScaffoldBudget
                           + " routeBudget="
                           + temporaryRouteScaffoldBudget
                           + " peakActive="
                           + reservedAssessment.peakActive()
                           + " active="
                           + this.activeScaffoldCount()
                           + " routeScaffolds="
                           + plannedBridgeSteps
                     );
                  }
                  this.route = executionPlan.steps();
                  this.routeIndex = 0;
                  this.routeTaskKey = executionPlan.goal().taskKey();
                  this.routeReturnState = returnState;
                  this.routePolicy = executionPlan.policy();
                  this.routeRequiresTaskValidation = executionPlan.requiresTaskValidation();
                  this.routeNeedsContinuation = executionPlan.requiresContinuation();
                  this.routeNoProgressTicks = 0;
                  this.routeCompletionDeferredTicks = 0;
                  this.lastRouteDistance = Double.POSITIVE_INFINITY;
                  this.state = ConstructionController.State.POSITIONING;
                  this.resumeState = returnState;
                  this.detail = reason + "，路线 " + this.route.size() + " 步";
                  TerraLevelerDiagnostics.log(
                     "ROUTE",
                     "planned task="
                        + taskKey.toShortString()
                        + " status="
                        + chosen.status()
                        + " steps="
                        + this.route.size()
                        + " bridge="
                        + chosenPolicy.allowBridge()
                        + " maximumDrop="
                        + chosenPolicy.maximumDrop()
                        + " continuation="
                        + this.routeNeedsContinuation
                        + " routeScaffolds="
                        + plannedBridgeSteps
                        + " activeScaffolds="
                        + this.activeScaffoldCount()
                        + "/"
                        + this.jobScaffoldBudget
                  );
                  if (this.route.isEmpty()) {
                     if (this.routeRequiresTaskValidation && !this.routeTaskNoLongerNeedsTravel(mc, config)) {
                        this.rejectUnusableRouteGoal(mc, "空路线目标不能执行当前任务");
                     } else {
                        this.finishRoute(mc);
                     }
                  }
               }
            }
         }
      }
   }

   private void logRouteFailureContext(
      Minecraft mc,
      BlockPos taskKey,
      BlockPos start,
      List<BlockPos> goals,
      ConstructionController.State returnState,
      SafeRoutePlanner.PlanStatus naturalStatus,
      SafeRoutePlanner.PlanStatus bridgeStatus,
      SafeRoutePlanner.PlanStatus chosenStatus
   ) {
      int occupiable = 0;
      int supported = 0;
      int scaffoldLane = 0;
      int minGoalY = Integer.MAX_VALUE;
      int maxGoalY = Integer.MIN_VALUE;

      for (BlockPos goal : goals) {
         if (SafeRoutePlanner.canOccupy(mc, goal)) {
            occupiable++;
         }

         if (SafeRoutePlanner.hasFloor(mc, goal)) {
            supported++;
         }

         if (SafeRoutePlanner.isScaffoldLane(this.workArea(), goal.getX(), goal.getZ())) {
            scaffoldLane++;
         }

         minGoalY = Math.min(minGoalY, goal.getY());
         maxGoalY = Math.max(maxGoalY, goal.getY());
      }

      String goalY = goals.isEmpty() ? "none" : minGoalY + ".." + maxGoalY;
      TerraLevelerDiagnostics.log(
         "ROUTE",
         "not-found task="
            + posText(taskKey)
            + " state="
            + returnState
            + " start="
            + start.toShortString()
            + " deltaY="
            + (taskKey == null ? "unknown" : taskKey.getY() - start.getY())
            + " goals="
            + goals.size()
            + " goalY="
            + goalY
            + " occupiable="
            + occupiable
            + " supported="
            + supported
            + " scaffoldLane="
            + scaffoldLane
            + " natural="
            + naturalStatus
            + " bridge="
            + bridgeStatus
            + " chosen="
            + chosenStatus
            + " scaffolds="
            + this.activeScaffoldCount()
            + "/"
            + this.jobScaffoldBudget
      );
   }

   private SafeRoutePlanner.RoutePolicy excavationDeckPolicy(Minecraft mc, BlockPos taskKey, ConstructionController.State returnState, BlockPos start) {
      return SafeRoutePlanner.RoutePolicy.flatteningFloor();
   }

   static boolean mayUseLastScaffoldDescent(
      boolean flattenFinalCleanupJourney,
      int activeScaffolds,
      boolean hasReturnTransaction,
      BlockPos task,
      BlockPos start
   ) {
      return flattenFinalCleanupJourney
         && activeScaffolds == 1
         && !hasReturnTransaction
         && task != null
         && start != null
         && task.getY() < start.getY();
   }

   private boolean beginVerticalRecoveryFallback(
      Minecraft mc,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState,
      TerraLevelerConfig config,
      String reason
   ) {
      if (this.beginWaterBucketFallFallback(mc, taskKey, start, returnState, reason)) {
         TerraLevelerDiagnostics.log(
            "RECOVERY", "vertical-recovery-selected action=WATER_BUCKET task=" + posText(taskKey) + " start=" + posText(start)
         );
         return true;
      }

      if (this.beginDirectSupportDropFallback(mc, returnState, config, reason)) {
         TerraLevelerDiagnostics.log(
            "RECOVERY", "vertical-recovery-selected action=BREAK_SUPPORT task=" + posText(taskKey) + " start=" + posText(start)
         );
         return true;
      }

      if (this.beginStrandedDescent(mc, taskKey, start, returnState)) {
         TerraLevelerDiagnostics.log(
            "RECOVERY", "vertical-recovery-selected action=WALK_DOWN task=" + posText(taskKey) + " start=" + posText(start)
         );
         return true;
      }

      TerraLevelerDiagnostics.log(
         "RECOVERY",
         "vertical-recovery-unavailable task="
            + posText(taskKey)
            + " start="
            + posText(start)
            + " waterBuckets="
            + inventoryCount(mc, Items.WATER_BUCKET)
            + " supports="
            + PlayerPhysicalState.supportingBlocks(mc)
      );
      return false;
   }

   private boolean beginWaterBucketFallFallback(
      Minecraft mc,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState,
      String reason
   ) {
      boolean flattenFinalCleanup = returnState == ConstructionController.State.CLEANING_SCAFFOLDS
         && this.wallIndex >= this.wallTargets.size();
      boolean activeCleanupTransaction = this.hasFlattenCleanupTransaction();
      if (!VerticalRecoveryRules.mayAttemptWaterBucketFall(
         taskKey != null,
         start != null,
         mayUseWaterBucketFall(returnState, activeCleanupTransaction, flattenFinalCleanup),
         mc.level.dimension() == Level.NETHER,
         inventoryCount(mc, Items.WATER_BUCKET)
      )) {
         return false;
      }

      // 0.8.92 (user decision): no health-based height limit any more. Until 0.8.91 a failed water
      // placement had to be survivable (16 blocks at full health), so a player stranded 37 blocks up
      // planned a 304-step, 102-scaffold detour down instead of landing in water.
      int bucketFallLimit = WaterBucketFallPlanner.MAX_BUCKET_FALL;
      SafeRoutePlanner.RouteEnvironment environment = SafeRoutePlanner.routeEnvironment(mc);
      WaterBucketFallPlanner.Result fall = WaterBucketFallPlanner.findLanding(
         environment, start, taskKey, bucketFallLimit
      );
      List<SafeRoutePlanner.RouteStep> approachSteps = List.of();
      if (fall == null) {
         List<WaterBucketFallPlanner.Result> candidates = WaterBucketFallPlanner.findNearbyLandings(
            environment,
            start,
            taskKey,
            bucketFallLimit,
            WaterBucketFallPlanner.MAX_APPROACH_RADIUS
         );
         if (candidates.isEmpty()) return false;
         List<BlockPos> launchGoals = candidates.stream().map(WaterBucketFallPlanner.Result::origin).distinct().toList();
         SafeRoutePlanner.RoutePolicy approachPolicy = SafeRoutePlanner.RoutePolicy.flatteningFloor();
         SafeRoutePlanner.PlanResult approach = SafeRoutePlanner.findRoute(mc, start, launchGoals, this.workArea(), approachPolicy);
         if (!approach.hasRoute()) {
            TerraLevelerDiagnostics.log(
               "ROUTE",
               "water-bucket-approach-unreachable task="
                  + taskKey.toShortString()
                  + " start="
                  + start.toShortString()
                  + " candidates="
                  + candidates.size()
                  + " status="
                  + approach.status()
            );
            return false;
         }
         BlockPos selectedLaunch = approach.steps().isEmpty() ? start : approach.steps().getLast().position();
         fall = candidates.stream().filter(candidate -> candidate.origin().equals(selectedLaunch)).findFirst().orElse(null);
         if (fall == null) return false;
         approachSteps = approach.steps();
      }

      SafeRoutePlanner.RoutePolicy policy = SafeRoutePlanner.RoutePolicy.flatteningFloorWithMaximumDrop(bucketFallLimit);
      this.actions.cancel(mc, "water-bucket-fall-fallback");
      AutoNavigator.release(mc);
      List<SafeRoutePlanner.RouteStep> combinedRoute = new ArrayList<>(approachSteps);
      combinedRoute.add(new SafeRoutePlanner.RouteStep(
         fall.origin(), fall.landingFeet(), SafeRoutePlanner.StepType.WATER_BUCKET_FALL, null
      ));
      this.route = List.copyOf(combinedRoute);
      this.routeIndex = 0;
      this.routeTaskKey = taskKey.immutable();
      this.routeReturnState = returnState;
      this.routePolicy = policy;
      this.routeRequiresTaskValidation = false;
      this.routeNeedsContinuation = true;
      this.routeNoProgressTicks = 0;
      this.routeCompletionDeferredTicks = 0;
      this.lastRouteDistance = Double.POSITIVE_INFINITY;
      this.waterBucketFallLanding = fall.landingFeet().immutable();
      this.waterBucketFallTicks = 0;
      this.waterBucketFallAimTicks = 0;
      this.waterBucketFallPickupAttempts = 0;
      this.waterBucketFallPlacementRequested = false;
      this.waterBucketFallWaterConfirmed = false;
      this.state = ConstructionController.State.POSITIONING;
      this.resumeState = returnState;
      this.clearRouteFailures();
      this.detail = approachSteps.isEmpty()
         ? reason + "；使用水桶下降到 " + fall.landingFeet().toShortString()
         : reason + "；先走到安全崖边 " + fall.origin().toShortString() + "，再使用水桶下降";
      TerraLevelerDiagnostics.log(
         "ROUTE",
         "water-bucket-fall-selected task="
            + taskKey.toShortString()
            + " planStart="
            + start.toShortString()
            + " launch="
            + fall.origin().toShortString()
            + " landing="
            + fall.landingFeet().toShortString()
            + " dropDistance="
            + fall.dropDistance()
            + " maximum="
            + bucketFallLimit
            + " approachSteps="
            + approachSteps.size()
            + " cleanup="
            + flattenFinalCleanup
            + " continuation=true"
      );
      return true;
   }

   private boolean beginDirectSupportDropFallback(
      Minecraft mc, ConstructionController.State returnState, TerraLevelerConfig config, String reason
   ) {
      if (returnState != ConstructionController.State.EXCAVATING || !this.stablePlayerStand(mc)) {
         return false;
      }

      List<BlockPos> supports = PlayerPhysicalState.supportingBlocks(mc);
      if (supports.size() != 1) {
         return false;
      }

      BlockPos support = supports.getFirst().immutable();
      boolean trackedScaffold = this.scaffoldPositions.contains(support) && this.isTrackedScaffoldBlock(mc, support);
      boolean plannedExcavation = this.excavationTargets.contains(support)
         && ExcavationPlanner.operationFor(this.classifyExcavation(mc, support)) == ExcavationPlanner.Operation.BREAK;
      if (!mayBreakSupportForVerticalRecovery(
         returnState, supports.size(), trackedScaffold, plannedExcavation, this.safeReach(mc, support)
      )) {
         return false;
      }

      if (plannedExcavation && config.flattenProtectBlockEntities && mc.level.getBlockEntity(support) != null) {
         TerraLevelerDiagnostics.log("RECOVERY", "support-drop-rejected support=" + support.toShortString() + " reason=protected-block-entity");
         return false;
      }

      DestructiveSupportDropPlanner.Result landing = this.findSafeSupportDrop(mc, support);
      if (!landing.allowed()) {
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "support-drop-rejected support="
               + support.toShortString()
               + " status="
               + landing.status()
               + " conflict="
               + posText(landing.conflict())
         );
         return false;
      }

      this.cancelOwnedWork(mc, "vertical-recovery-support-drop");
      this.breakPurpose = ConstructionController.BreakPurpose.VERTICAL_RECOVERY_SUPPORT;
      this.breakTarget = support;
      this.verticalRecoverySupportDrop = landing;
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = ConstructionController.State.EXCAVATING;
      this.clearRouteFailures();
      this.detail = reason
         + "；准备拆除脚下计划内方块并自然下降到 "
         + landing.landing().toShortString()
         + "，落差 "
         + landing.dropDistance();
      TerraLevelerDiagnostics.log(
         "RECOVERY",
         "support-drop-authorized support="
            + support.toShortString()
            + " landing="
            + landing.landing().toShortString()
            + " dropDistance="
            + landing.dropDistance()
            + " landingType="
            + landing.status()
            + " trackedScaffold="
            + trackedScaffold
            + " plannedExcavation="
            + plannedExcavation
      );
      return true;
   }

   private void tickVerticalRecoverySupportBreak(Minecraft mc, TerraLevelerConfig config) {
      BlockPos support = this.breakTarget;
      if (support == null || this.verticalRecoverySupportDrop == null) {
         this.pause(mc, "垂直脱困拆脚事务缺少支撑方块或预检落点");
         return;
      }

      if (!this.stablePlayerStand(mc) || !this.isBlockSupportingPlayer(mc, support)) {
         this.beginFallRecovery(mc, ConstructionController.State.EXCAVATING, "垂直脱困期间支撑已经消失");
         return;
      }

      DestructiveSupportDropPlanner.Result revalidated = this.findSafeSupportDrop(mc, support);
      if (!revalidated.allowed()) {
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "support-drop-revalidation-rejected support="
               + support.toShortString()
               + " status="
               + revalidated.status()
               + " conflict="
               + posText(revalidated.conflict())
         );
         this.pause(mc, "脚下方块破坏前落点或血量发生变化，取消垂直脱困");
         return;
      }

      this.verticalRecoverySupportDrop = revalidated;
      if (this.handleBlockingAnimal(mc, config, support, "vertical-support-drop")) {
         return;
      }

      ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, support, config);
      if (status == null || status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "拆除脚下方块并自然下降到 "
            + revalidated.landing().toShortString()
            + "，落差 "
            + revalidated.dropDistance();
      } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
         this.pause(mc, "拆除脚下方块时失去正常触及距离 " + support.toShortString());
      } else if (status == ServerActionExecutor.Status.FAILED) {
         this.pause(mc, this.actionFailure("垂直脱困拆脚失败"));
      } else if (status == ServerActionExecutor.Status.SUCCESS || status == ServerActionExecutor.Status.RECLASSIFY) {
         boolean trackedScaffold = this.scaffoldPositions.contains(support);
         DestructiveSupportDropPlanner.Result completedDrop = this.verticalRecoverySupportDrop;
         this.clearBreak();
         if (trackedScaffold) {
            this.forgetScaffold(support);
         }
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "support-drop-confirmed support="
               + support.toShortString()
               + " expectedLanding="
               + completedDrop.landing().toShortString()
               + " dropDistance="
               + completedDrop.dropDistance()
         );
         this.beginFallRecovery(mc, ConstructionController.State.EXCAVATING, "服务器已确认脚下方块移除");
      } else {
         this.pause(mc, "垂直脱困拆脚返回异常状态 " + status);
      }
   }

   private DestructiveSupportDropPlanner.Result findSafeSupportDrop(Minecraft mc, BlockPos support) {
      return DestructiveSupportDropPlanner.findLanding(
         this.supportDropEnvironment(mc),
         support,
         DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(mc.player.getHealth(), mc.player.getAbsorptionAmount())
      );
   }

   private DestructiveSupportDropPlanner.Environment supportDropEnvironment(Minecraft mc) {
      return new DestructiveSupportDropPlanner.Environment() {
         @Override
         public DestructiveSupportDropPlanner.CellKind cell(BlockPos position) {
            if (!mc.level.hasChunkAt(position)) {
               return DestructiveSupportDropPlanner.CellKind.UNLOADED;
            }

            BlockState state = mc.level.getBlockState(position);
            if (!state.getFluidState().isEmpty()) {
               if (!state.getFluidState().is(FluidTags.WATER)) {
                  return DestructiveSupportDropPlanner.CellKind.HAZARDOUS_FLUID;
               }

               return state.getCollisionShape(mc.level, position).isEmpty()
                  ? DestructiveSupportDropPlanner.CellKind.WATER
                  : DestructiveSupportDropPlanner.CellKind.SOLID;
            }

            if (HazardousBlocks.isHazard(state)) {
               return DestructiveSupportDropPlanner.CellKind.HAZARDOUS_BLOCK;
            }

            return state.getCollisionShape(mc.level, position).isEmpty()
               ? DestructiveSupportDropPlanner.CellKind.OPEN
               : DestructiveSupportDropPlanner.CellKind.SOLID;
         }

         @Override
         public int minimumY() {
            return mc.level.getMinY();
         }
      };
   }

   static boolean mayBreakSupportForVerticalRecovery(
      ConstructionController.State returnState,
      int supportingBlockCount,
      boolean trackedScaffold,
      boolean plannedExcavation,
      boolean safelyReachable
   ) {
      return VerticalRecoveryRules.mayBreakUniqueSupport(
         returnState == ConstructionController.State.EXCAVATING,
         supportingBlockCount,
         trackedScaffold,
         plannedExcavation,
         safelyReachable
      );
   }

   static boolean mayUseWaterBucketFall(
      ConstructionController.State returnState, boolean activeCleanupTransaction, boolean flattenFinalCleanup
   ) {
      if (activeCleanupTransaction || returnState == null) return false;
      return returnState == ConstructionController.State.BUILDING_WALL
         || returnState == ConstructionController.State.EXCAVATING
         || returnState == ConstructionController.State.CLEANING_SCAFFOLDS && flattenFinalCleanup;
   }

   /**
    * Counts the ticks the current flatten excavation target stays current. Walking, pillars,
    * scaffold relief and water-bucket descents all count; fluid draining, chunk loading and the
    * later phases do not.
    */
   private void trackStuckExcavationTarget() {
      if (this.excavationIndex >= this.excavationTargets.size()) {
         this.stuckWatchTarget = null;
         this.stuckWatchTicks = 0;
         return;
      }
      BlockPos current = this.excavationTargets.get(this.excavationIndex);
      if (!current.equals(this.stuckWatchTarget)) {
         this.stuckWatchTarget = current;
         this.stuckWatchTicks = 0;
      } else if (this.state != ConstructionController.State.CONTAINING_FLUID
         && this.state != ConstructionController.State.LOADING
         && this.state != ConstructionController.State.BUILDING_WALL
         && this.state != ConstructionController.State.CLEANING_SCAFFOLDS) {
         this.stuckWatchTicks++;
      }
   }

   /**
    * One target stayed current for {@link #STUCK_EXCAVATION_TARGET_TICKS} (0.8.98). The 0.8.97 test
    * spent the last 19 minutes on one block 38 above the ground: tear down scaffolds, climb the
    * hill, bridge toward it, drop with the water bucket, again. The first time the target goes to
    * the end of the queue so the rest is dug first; the second time the job pauses instead.
    */
   private boolean deferStuckExcavationTarget(Minecraft mc, BlockPos target) {
      int minutes = this.stuckWatchTicks / 1200;
      this.stuckWatchTarget = null;
      this.stuckWatchTicks = 0;
      boolean othersLeft = this.excavationIndex + 1 < this.excavationTargets.size();
      if (othersLeft && this.stuckDeferredTargets.add(target.immutable())) {
         this.setExcavationTargets(deferZoneTargets(this.excavationTargets, this.excavationIndex, target::equals));
         this.clearRouteFailures();
         TerraLevelerDiagnostics.log("EXCAVATION", "stuck-target-deferred target=" + target.toShortString() + " minutes=" + minutes
            + " remaining=" + (this.excavationTargets.size() - this.excavationIndex));
         return true;
      }
      TerraLevelerDiagnostics.log("EXCAVATION", "stuck-target-paused target=" + target.toShortString() + " minutes=" + minutes
         + " deferredBefore=" + this.stuckDeferredTargets.contains(target));
      this.pause(
         mc,
         "挖掘目标 " + target.toShortString() + " 连续 " + minutes + " 分钟没有挖到（反复绕路、垫柱或拆脚手），"
            + (othersLeft ? "放到最后重试过一次仍不行" : "它是本区域剩下的最后一个目标")
            + "；已停止，请手动处理这个方块后按" + KeyHintText.hint(KeyHintText.PAUSE_RESUME) + "继续",
         ConstructionController.PauseKind.MANUAL_RESUME
      );
      return false;
   }

   private boolean deferUnreachableExcavationTarget(Minecraft mc, TerraLevelerConfig config, BlockPos failedTarget, SafeRoutePlanner.PlanStatus failureStatus) {
      if (this.excavationIndex < this.excavationTargets.size() && this.excavationTargets.get(this.excavationIndex).equals(failedTarget)) {
         BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(null);
         if (start == null) {
            return false;
         }

         if (!this.deferredRouteTargets.add(failedTarget.immutable())) {
            TerraLevelerDiagnostics.log(
               "EXCAVATION",
               "defer-retry-skipped failed=" + failedTarget.toShortString() + " status=" + failureStatus + " reason=already-searched-without-progress"
            );
            return false;
         }

         ConstructionController.RouteSearchBudget budget = new ConstructionController.RouteSearchBudget(2);
         ConstructionController.DeferredReplacement replacement = this.findDeferredExcavationReplacement(mc, failedTarget, start, true, 18, budget);
         if (replacement == null) {
            replacement = this.findDeferredExcavationReplacement(mc, failedTarget, start, false, 18, budget);
         }

         if (replacement == null) {
            TerraLevelerDiagnostics.log(
               "EXCAVATION",
               "defer-exhausted failed="
                  + failedTarget.toShortString()
                  + " status="
                  + failureStatus
                  + " layerY="
                  + failedTarget.getY()
                  + " player="
                  + start.toShortString()
                  + " routeSearches="
                  + budget.used()
                  + "/2 pending="
                  + (this.excavationTargets.size() - this.excavationIndex)
            );
            return false;
         } else {
            Collections.swap(this.excavationTargets, this.excavationIndex, replacement.index());
            this.clearRouteFailures();
            this.state = ConstructionController.State.EXCAVATING;
            this.routeRetryCooldownTicks = 2;
            this.detail = "当前底层方块暂不可达，先处理可达方块，稍后回扫遗漏";
            TerraLevelerDiagnostics.log(
               "EXCAVATION",
               "defer-unreachable failed="
                  + failedTarget.toShortString()
                  + " status="
                  + failureStatus
                  + " replacement="
                  + replacement.pos().toShortString()
                  + " replacementOriginalIndex="
                  + replacement.index()
                  + " routeSteps="
                  + replacement.steps()
                  + " routeSearches="
                  + budget.used()
                  + "/2 layerY="
                  + failedTarget.getY()
                  + " pending="
                  + (this.excavationTargets.size() - this.excavationIndex)
            );
            return true;
         }
      } else {
         return false;
      }
   }

   private boolean deferLongReachableExcavationRoute(Minecraft mc, BlockPos currentTarget, BlockPos start, int currentSteps) {
      if (currentSteps >= 32 && this.excavationIndex < this.excavationTargets.size() && this.excavationTargets.get(this.excavationIndex).equals(currentTarget)) {
         int maxPreferredSteps = Math.min(18, Math.max(4, currentSteps / 3));
         ConstructionController.RouteSearchBudget budget = new ConstructionController.RouteSearchBudget(2);
         ConstructionController.DeferredReplacement replacement = this.findDeferredExcavationReplacement(
            mc, currentTarget, start, true, maxPreferredSteps, budget
         );
         if (replacement == null) {
            return false;
         }

         this.deferredRouteTargets.add(currentTarget.immutable());
         Collections.swap(this.excavationTargets, this.excavationIndex, replacement.index());
         this.clearRouteFailures();
         this.state = ConstructionController.State.EXCAVATING;
         this.routeRetryCooldownTicks = 1;
         this.detail = "远处边界目标路线过长，先处理当前附近可达方块，避免跨沟来回跑";
         TerraLevelerDiagnostics.log(
            "EXCAVATION",
            "defer-long-route current="
               + currentTarget.toShortString()
               + " currentSteps="
               + currentSteps
               + " replacement="
               + replacement.pos().toShortString()
               + " replacementOriginalIndex="
               + replacement.index()
               + " replacementSteps="
               + replacement.steps()
               + " maxPreferredSteps="
               + maxPreferredSteps
               + " routeSearches="
               + budget.used()
               + "/2 pending="
               + (this.excavationTargets.size() - this.excavationIndex)
         );
         return true;
      } else {
         return false;
      }
   }

   private ConstructionController.DeferredReplacement findDeferredExcavationReplacement(
      Minecraft mc, BlockPos failedTarget, BlockPos start, boolean sameLayerOnly, int maxSteps, ConstructionController.RouteSearchBudget budget
   ) {
      Set<BlockPos> excluded = new HashSet<>(this.deferredUnsupportedFluidTargets);
      excluded.addAll(this.deferredRouteTargets);
      if (this.flattenPlan != null
         && this.flattenPlan.isTreeTarget(failedTarget)) {
         for (int index = this.excavationIndex + 1; index < this.excavationTargets.size(); index++) {
            BlockPos candidate = this.excavationTargets.get(index);
            if (!this.flattenPlan.isSameTree(failedTarget, candidate)) excluded.add(candidate);
         }
      }
      List<ExcavationRouteDeferralRules.Candidate> candidates = ExcavationRouteDeferralRules.shortlist(
         this.excavationTargets, this.excavationIndex, failedTarget, start, excluded, sameLayerOnly, 16
      );
      ConstructionController.DeferredReplacement best = null;
      int bestSteps = Integer.MAX_VALUE;

      for (ExcavationRouteDeferralRules.Candidate candidate : candidates) {
         int steps = this.estimateDeferredReplacementSteps(mc, candidate.position(), start, budget);
         if (ExcavationRouteDeferralRules.acceptsRoute(steps, maxSteps) && steps < bestSteps) {
            bestSteps = steps;
            best = new ConstructionController.DeferredReplacement(candidate.index(), candidate.position(), steps);
            if (bestSteps <= 1) {
               break;
            }
         }
      }

      return best;
   }

   private int estimateDeferredReplacementSteps(Minecraft mc, BlockPos candidate, BlockPos start, ConstructionController.RouteSearchBudget budget) {
      if (ExcavationPlanner.operationFor(this.classifyExcavation(mc, candidate)) != ExcavationPlanner.Operation.BREAK) {
         return -1;
      }

      if (this.canPerformFromCurrentPlayer(mc, candidate, candidate, ConstructionController.WorkAction.BREAK)) {
         return 0;
      }

      List<BlockPos> poses = this.workPoses(mc, candidate, candidate, ConstructionController.WorkAction.BREAK);
      if (poses.isEmpty()) {
         return -1;
      }

      if (!budget.tryUse()) {
         return -1;
      }

      SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRouteWithinVisitLimit(
         mc, start, poses, this.workArea(), SafeRoutePlanner.RoutePolicy.natural(), 2000
      );
      return result.hasRoute() ? result.steps().size() : -1;
   }

   /**
    * Before climbing a scaffold stair back up to unexcavated high ground, keep enough budget
    * for the way on or back: first pillar-clean nearby high scaffolds while still standing on
    * the ground, else work on something reachable without the climb. Only when neither is
    * possible is the climb taken (a stranded player is then rescued by rescueStrandedPlayer).
    */
   private boolean guardClimbBudget(
      Minecraft mc,
      TerraLevelerConfig config,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState,
      List<SafeRoutePlanner.RouteStep> steps,
      int plannedScaffolds
   ) {
      if (returnState != ConstructionController.State.EXCAVATING
         || this.strandedEmergencyScaffoldDebt > 0) {
         return false;
      }
      int rise = ClimbBudgetRules.routeRise(start.getY(), steps);
      int active = this.activeScaffoldCount();
      if (!ClimbBudgetRules.needsReliefBeforeClimb(rise, active, plannedScaffolds, this.jobScaffoldBudget)) {
         return false;
      }
      if (!taskKey.equals(this.climbReliefTask) || this.climbReliefActive != active) {
         this.climbReliefTask = taskKey.immutable();
         this.climbReliefActive = active;
         if (this.beginBudgetReliefPillar(mc, start, ClimbBudgetRules.RELIEF_RADIUS)) {
            TerraLevelerDiagnostics.log("SCAFFOLD", "climb-guard-relief task=" + taskKey.toShortString() + " start=" + start.toShortString()
               + " rise=" + rise + " routeScaffolds=" + plannedScaffolds + " active=" + active + "/" + this.jobScaffoldBudget);
            return true;
         }
      }
      if (this.deferUnreachableExcavationTarget(mc, config, taskKey, SafeRoutePlanner.PlanStatus.NO_ROUTE)) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "climb-guard-deferred task=" + taskKey.toShortString() + " rise=" + rise
            + " routeScaffolds=" + plannedScaffolds + " active=" + active + "/" + this.jobScaffoldBudget);
         return true;
      }
      TerraLevelerDiagnostics.log("SCAFFOLD", "climb-guard-allowed task=" + taskKey.toShortString() + " rise=" + rise
         + " routeScaffolds=" + plannedScaffolds + " active=" + active + "/" + this.jobScaffoldBudget + " reason=no-relief-or-alternative");
      return false;
   }

   /**
    * A target high above the player is reached with a temporary pillar (taken down again
    * afterwards) before a scaffold stair is built. In the 0.8.85 test the second island region
    * started with an old scaffold bridge 51 blocks up: the stair used 49 scaffolds, its route
    * search gave up at Y=87 and the player stood 24 blocks above the ground with no way on.
    */
   private boolean preferPillarForHighTarget(
      Minecraft mc,
      TerraLevelerConfig config,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState,
      int plannedScaffolds
   ) {
      if (returnState != ConstructionController.State.EXCAVATING) {
         return false;
      }
      int rise = taskKey.getY() - start.getY();
      if (!ClimbBudgetRules.preferPillarAccess(rise, plannedScaffolds)) {
         return false;
      }
      if (this.tryPillarFirst(mc, config, taskKey, start, rise, "stair scaffolds=" + plannedScaffolds)) {
         return true;
      }
      if (taskKey.equals(this.strandedDescentTask)) {
         // The same stair would strand the player again.
         if (this.deferUnreachableExcavationTarget(mc, config, taskKey, SafeRoutePlanner.PlanStatus.NO_ROUTE)) {
            TerraLevelerDiagnostics.log("SCAFFOLD", "climb-after-descent-deferred task=" + taskKey.toShortString() + " rise=" + rise
               + " stairScaffolds=" + plannedScaffolds);
            return true;
         }
         this.pause(mc, "高处目标 " + taskKey.toShortString() + " 搭台阶上去曾困在半空，从地面垫临时柱也没能开始（见日志 pillar-rejected / pillar-base-unreachable）；不会再次搭台阶爬上去");
         return true;
      }
      return false;
   }

   /**
    * One pillar try for a high excavation target. A pillar about as tall as the rise must fit the
    * budget, otherwise the pillar path would pause. Fetching pillar material from a shulker box
    * returns here (0.8.87 only got 2 dirt from the box's first stack and then never tried the
    * pillar again), so only a refusal ends the retries.
    */
   private boolean tryPillarFirst(Minecraft mc, TerraLevelerConfig config, BlockPos taskKey, BlockPos start, int rise, String trigger) {
      if (!taskKey.equals(this.pillarFirstTask)) {
         this.pillarFirstTask = taskKey.immutable();
         this.pillarFirstAttempts = 0;
      }
      if (this.pillarFirstAttempts >= ClimbBudgetRules.PILLAR_FIRST_ATTEMPTS
         || this.activeScaffoldCount() + rise > this.temporaryPillarBudget()) {
         return false;
      }
      this.pillarFirstAttempts++;
      if (this.beginHighExcavationPillarAccess(mc, config, taskKey, start, SafeRoutePlanner.PlanStatus.NO_ROUTE)) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-first task=" + taskKey.toShortString() + " start=" + start.toShortString()
            + " rise=" + rise + " trigger=" + trigger + " attempt=" + this.pillarFirstAttempts
            + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget);
         return true;
      }
      this.pillarFirstAttempts = ClimbBudgetRules.PILLAR_FIRST_ATTEMPTS;
      return false;
   }

   /**
    * A target with a deep drop of open air beside it, high above the player, is reached with a
    * pillar straight away, without the full route search first. In the 0.8.88 test each of 51
    * pillars began with a natural and a bridge search toward a target 40-50 blocks up
    * (0.15-0.25 s in one tick, sometimes an expanded search of 0.5-0.9 s), and the player saw a
    * frame drop every time one pillar was taken down and the next one began.
    */
   private boolean beginFloatingTargetPillar(
      Minecraft mc, TerraLevelerConfig config, BlockPos taskKey, BlockPos start, ConstructionController.State returnState
   ) {
      if (returnState != ConstructionController.State.EXCAVATING || taskKey == null
         || this.finalScaffoldPillarTarget != null || this.hasFlattenCleanupTransaction()) {
         return false;
      }
      int rise = taskKey.getY() - start.getY();
      if (rise < ClimbBudgetRules.PILLAR_FIRST_RISE
         || taskKey.equals(this.pillarFirstTask) && this.pillarFirstAttempts >= ClimbBudgetRules.PILLAR_FIRST_ATTEMPTS
         || this.activeScaffoldCount() + rise > this.temporaryPillarBudget()) {
         return false;
      }
      FinalScaffoldPillarPlanner.Result geometry = FinalScaffoldPillarPlanner.planAccess(
         taskKey, start, Integer.MAX_VALUE, MAX_FINAL_SCAFFOLD_PILLAR_HEIGHT, mc.level.getMinY(),
         position -> this.scaffoldPillarCell(mc, position)
      );
      if (!ClimbBudgetRules.floatingTarget(geometry.ready(), geometry.minimumRequiredBlocks())) {
         return false;
      }
      return this.tryPillarFirst(mc, config, taskKey, start, rise, "floating pillarBlocks=" + geometry.minimumRequiredBlocks());
   }

   /** A pillar is always taken down again, so it may go over the budget by its height (like cleanup pillars). */
   private int temporaryPillarBudget() {
      return this.jobScaffoldBudget + MAX_FINAL_SCAFFOLD_PILLAR_HEIGHT;
   }

   /**
    * With the scaffold budget used up a bridge route runs in rolling mode: each new scaffold retires
    * the one behind it. High over low ground that leaves the player on a single block above a
    * deadly drop with no way back. In the 0.8.89 test a whole-island job sent the player from a
    * mountain top at Y=109 toward an old bridge remnant 115 blocks east; at -3563,96,-10012 it
    * stood on one block 34 above the ground. Such a target is reached with a pillar or waits.
    */
   private boolean guardRollingSkyBridge(
      Minecraft mc,
      TerraLevelerConfig config,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState,
      SafeRoutePlanner.PlanResult route,
      int plannedScaffolds
   ) {
      if (returnState != ConstructionController.State.EXCAVATING || plannedScaffolds <= 0) {
         return false;
      }
      int headroom = this.maximumTemporaryExcavationRouteScaffoldBudget() - this.activeScaffoldCount();
      if (plannedScaffolds <= headroom) {
         return false;
      }
      List<SafeRoutePlanner.RouteStep> steps = route.steps();
      SafeRoutePlanner.RouteEnvironment environment = SafeRoutePlanner.routeEnvironment(mc);
      int drop = ClimbBudgetRules.maximumBridgeDrop(steps, environment);
      int survivable = DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(mc.player.getHealth(), mc.player.getAbsorptionAmount());
      if (!ClimbBudgetRules.unsafeRollingBridge(plannedScaffolds, headroom, drop, survivable)) {
         return false;
      }
      // 0.8.91: a complete bridge that ends on natural ground does not leave the player in the air
      // (0.8.90 refused the only way off a tower 48 blocks up: 99 steps to the mountain at -3602,109).
      if (ClimbBudgetRules.completeToNaturalGround(route.status() == SafeRoutePlanner.PlanStatus.FOUND, steps, environment,
         this.scaffoldPositions::contains)) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "sky-bridge-allowed-complete task=" + taskKey.toShortString() + " start=" + start.toShortString()
            + " steps=" + steps.size() + " routeScaffolds=" + plannedScaffolds + " headroom=" + headroom + " drop=" + drop
            + " arrival=" + steps.getLast().position().toShortString());
         return false;
      }
      int rise = taskKey.getY() - start.getY();
      if (this.tryPillarFirst(mc, config, taskKey, start, rise, "sky-bridge drop=" + drop)) {
         return true;
      }
      if (this.deferUnreachableExcavationTarget(mc, config, taskKey, SafeRoutePlanner.PlanStatus.NO_ROUTE)) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "sky-bridge-deferred task=" + taskKey.toShortString() + " start=" + start.toShortString()
            + " routeScaffolds=" + plannedScaffolds + " headroom=" + headroom + " drop=" + drop + " survivable=" + survivable
            + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget);
         return true;
      }
      if (this.beginWaterBucketFallFallback(mc, taskKey, start, returnState, "高空滚动桥走过去回不来")) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "sky-bridge-water-descent task=" + taskKey.toShortString() + " start=" + start.toShortString()
            + " routeScaffolds=" + plannedScaffolds + " drop=" + drop);
         return true;
      }
      TerraLevelerDiagnostics.log("SCAFFOLD", "sky-bridge-refused task=" + taskKey.toShortString() + " start=" + start.toShortString()
         + " routeScaffolds=" + plannedScaffolds + " headroom=" + headroom + " drop=" + drop);
      this.pause(mc, "去 " + taskKey.toShortString() + " 要在离地 " + drop + " 格的高空用滚动脚手搭桥（脚手额度已满，身后的脚手会被拆掉），走过去就回不来；附近也没有别的可做目标，先暂停");
      return true;
   }

   /**
    * Standing on an own scaffold with open air far below it (in the sky, not on a mountain) and a
    * target below or far away: land with the water bucket first instead of searching a way down
    * (0.8.91 test: from Y=99, 37 above the ground, a 304-step route with 102 scaffolds and three
    * one-second searches was planned toward a target at Y=73).
    */
   private boolean beginSkyBucketDescent(
      Minecraft mc, BlockPos taskKey, BlockPos start, ConstructionController.State returnState, String reason
   ) {
      if (taskKey == null || taskKey.equals(this.skyBucketTask)
         || returnState != ConstructionController.State.EXCAVATING && returnState != ConstructionController.State.BUILDING_WALL
         || this.hasFlattenCleanupTransaction()) {
         return false;
      }
      BlockPos support = this.ownedSupportingScaffold(mc, null);
      if (support == null) {
         return false;
      }
      int dropBelow = ClimbBudgetRules.openDropBelow(support, SafeRoutePlanner.routeEnvironment(mc));
      if (!ClimbBudgetRules.skyBucketFirst(start, taskKey, dropBelow)) {
         return false;
      }
      this.skyBucketTask = taskKey.immutable();
      if (this.beginWaterBucketFallFallback(mc, taskKey, start, returnState, reason)) {
         TerraLevelerDiagnostics.log("RECOVERY", "sky-bucket-descent task=" + taskKey.toShortString() + " start=" + start.toShortString()
            + " dropBelowSupport=" + dropBelow);
         return true;
      }
      return false;
   }

   /** Standing high above the finished ground on an own scaffold (see {@link ClimbBudgetRules#isStranded}). */
   private boolean strandedOnOwnScaffold(Minecraft mc, BlockPos start) {
      return this.flattenPlan != null && start != null
         && ClimbBudgetRules.isStranded(start.getY(), this.flattenPlan.targetGroundY(), this.ownedSupportingScaffold(mc, null) != null);
   }

   /**
    * Stuck high on an own scaffold stair with no route on: walk back down over the blocks that
    * still stand (normally the stair itself) to the finished ground. Used when the water bucket
    * (tried first, without a height limit since 0.8.92) finds no landing or is missing.
    */
   private boolean beginStrandedDescent(
      Minecraft mc,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState
   ) {
      if (this.flattenPlan == null || taskKey == null || start == null
         || returnState != ConstructionController.State.EXCAVATING || taskKey.equals(this.strandedDescentTask)) {
         return false;
      }
      if (!this.strandedOnOwnScaffold(mc, start)) {
         return false;
      }
      if (start.equals(this.strandedDescentFailedStart) && this.activeScaffoldCount() == this.strandedDescentFailedCount) {
         return false;
      }
      SafeRoutePlanner.RouteEnvironment environment = SafeRoutePlanner.routeEnvironment(mc);
      List<BlockPos> goals = ClimbBudgetRules.descentGoals(start, this.flattenPlan.targetGroundY(), feet ->
         environment.cell(feet) == SafeRoutePlanner.CellKind.OPEN
            && environment.cell(feet.above()) == SafeRoutePlanner.CellKind.OPEN
            && environment.cell(feet.below()) == SafeRoutePlanner.CellKind.SOLID);
      SafeRoutePlanner.RoutePolicy policy = ClimbBudgetRules.descentPolicy(false);
      SafeRoutePlanner.PlanResult plan = goals.isEmpty()
         ? null
         : SafeRoutePlanner.findRoute(mc, start, goals, this.workArea(), policy);
      int rebuiltSteps = 0;
      if (plan != null && (!plan.hasRoute() || plan.steps().isEmpty())) {
         // A step of the stair may be gone (0.8.85/0.8.86 took scaffolds next to the player down): rebuild it on the way.
         SafeRoutePlanner.RoutePolicy bridgePolicy = ClimbBudgetRules.descentPolicy(true);
         SafeRoutePlanner.PlanResult bridge = SafeRoutePlanner.findRouteWithinVisitLimit(
            mc, start, goals, this.workArea(), bridgePolicy, ClimbBudgetRules.DESCENT_BRIDGE_VISITS
         );
         if (bridge.hasRoute() && !bridge.steps().isEmpty()) {
            int required = countRequiredTemporaryScaffoldSteps(mc, bridge.steps());
            if (required == 0 || this.ensureHotbarScaffold(mc, this.preferredScaffoldItem(mc, required))) {
               plan = bridge;
               policy = bridgePolicy;
               rebuiltSteps = required;
            }
         }
      }
      if (plan == null || !plan.hasRoute() || plan.steps().isEmpty()) {
         this.strandedDescentFailedStart = start.immutable();
         this.strandedDescentFailedCount = this.activeScaffoldCount();
         TerraLevelerDiagnostics.log("RECOVERY", "stranded-descent-unavailable task=" + taskKey.toShortString() + " start=" + start.toShortString()
            + " groundGoals=" + goals.size() + " status=" + (plan == null ? "NO_GROUND" : plan.status()));
         return false;
      }

      this.cancelOwnedWork(mc, "stranded-descent");
      this.route = plan.steps();
      this.routeIndex = 0;
      this.routeTaskKey = this.route.getLast().position().immutable();
      this.routeReturnState = returnState;
      this.routePolicy = policy;
      this.routeRequiresTaskValidation = false;
      this.routeNeedsContinuation = false;
      this.routeNoProgressTicks = 0;
      this.routeCompletionDeferredTicks = 0;
      this.lastRouteDistance = Double.POSITIVE_INFINITY;
      this.clearRouteFailures();
      this.routeRetryCooldownTicks = 0;
      this.state = ConstructionController.State.POSITIONING;
      this.resumeState = returnState;
      this.strandedDescentTask = taskKey.immutable();
      // From the ground a pillar may reach what the stair could not.
      this.pillarFirstTask = null;
      this.detail = "困在高处自有脚手上且没有路线；沿已有方块走回地面 " + this.routeTaskKey.toShortString();
      TerraLevelerDiagnostics.log("RECOVERY", "stranded-descent task=" + taskKey.toShortString() + " start=" + start.toShortString()
         + " ground=" + this.routeTaskKey.toShortString() + " steps=" + this.route.size() + " rebuiltSteps=" + rebuiltSteps
         + " groundGoals=" + goals.size());
      return true;
   }

   /**
    * The player stands high on an own scaffold and no route fits the scaffold budget. Try to get
    * down first (water bucket, breaking the support), then allow one extra temporary allowance so
    * the player can leave instead of pausing in mid-air.
    */
   private boolean rescueStrandedPlayer(
      Minecraft mc,
      TerraLevelerConfig config,
      BlockPos taskKey,
      BlockPos start,
      ConstructionController.State returnState,
      String reason
   ) {
      if (this.flattenPlan == null || start == null
         || returnState != ConstructionController.State.EXCAVATING) {
         return false;
      }
      BlockPos support = this.ownedSupportingScaffold(mc, null);
      if (!ClimbBudgetRules.isStranded(start.getY(), this.flattenPlan.targetGroundY(), support != null)) {
         return false;
      }
      if (this.beginVerticalRecoveryFallback(mc, taskKey, start, returnState, config, reason)) {
         TerraLevelerDiagnostics.log("RECOVERY", "stranded-rescue action=vertical-recovery task=" + taskKey.toShortString()
            + " start=" + start.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget);
         return true;
      }
      // 0.8.89: the allowance was cleared again in the very next planning pass (active 230/230 is
      // "within budget"), granted again, cleared again... 1020 times in 2.7 minutes, a full bridge
      // search each time and no pause: the game ran at about one frame per second.
      if (this.strandedEmergencyScaffoldDebt > 0 || start.equals(this.strandedEmergencyStart)) {
         return false;
      }
      this.strandedEmergencyStart = start.immutable();
      this.strandedEmergencyScaffoldDebt = ClimbBudgetRules.EMERGENCY_SCAFFOLD_DEBT;
      this.deferredRouteTargets.remove(taskKey);
      this.clearRouteFailures();
      this.state = returnState;
      this.resumeState = returnState;
      this.routeRetryCooldownTicks = 1;
      this.detail = "困在高处自有脚手上且脚手额度已满；临时追加 " + ClimbBudgetRules.EMERGENCY_SCAFFOLD_DEBT + " 个额度以离开";
      TerraLevelerDiagnostics.log("RECOVERY", "stranded-emergency-budget task=" + taskKey.toShortString() + " start=" + start.toShortString()
         + " support=" + support.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
         + " maxTemporaryBudget=" + this.maximumTemporaryExcavationRouteScaffoldBudget());
      return true;
   }

   /** The emergency allowance ends once the scaffold count is back within the normal budget. */
   private void refreshStrandedEmergencyScaffoldDebt() {
      if (this.strandedEmergencyScaffoldDebt > 0 && this.activeScaffoldCount() <= this.jobScaffoldBudget) {
         this.strandedEmergencyScaffoldDebt = 0;
         TerraLevelerDiagnostics.log("RECOVERY", "stranded-emergency-budget-cleared active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget);
      }
   }

   private boolean beginReachableRouteScaffoldCleanup(Minecraft mc, BlockPos taskTarget, BlockPos start) {
      BlockPos obstacle = ScaffoldObstacleRules.selectReachable(
            this.scaffoldPositions,
            taskTarget,
            start,
            this.workArea(),
            pos -> this.isTrackedScaffoldBlock(mc, pos),
            pos -> this.safeReach(mc, pos) && !this.isBlockSupportingPlayer(mc, pos)
         )
         .orElse(null);
      if (obstacle != null) {
         this.cancelOwnedWork(mc, "route-scaffold-obstacle");
         this.breakPurpose = ConstructionController.BreakPurpose.ROUTE_SCAFFOLD_CLEANUP;
         this.breakTarget = obstacle.immutable();
         this.state = ConstructionController.State.EXCAVATING;
         this.resumeState = this.state;
         this.clearRouteFailures();
         this.routeRetryCooldownTicks = 0;
         this.detail = "安全路线被自有临时脚手阻挡，先按从上到下顺序拆除 " + obstacle.toShortString();
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "route-obstacle-cleanup task="
               + taskTarget.toShortString()
               + " start="
               + start.toShortString()
               + " obstacle="
               + obstacle.toShortString()
               + " active="
               + this.scaffoldPositions.size()
         );
         return true;
      } else {
         return this.activeScaffoldCount() >= this.jobScaffoldBudget && this.beginBudgetReliefScaffoldCleanup(mc, taskTarget, start);
      }
   }

   private boolean beginBudgetReliefScaffoldCleanup(Minecraft mc, BlockPos taskTarget, BlockPos start) {
      long now = mc.level.getGameTime();
      if (ScaffoldBudgetRules.reliefScanCoolingDown(now, this.budgetReliefExhaustedTick, this.activeScaffoldCount(),
         this.budgetReliefExhaustedCount, start, this.budgetReliefExhaustedStart)) {
         return false;
      }
      if (this.jobScaffoldBudget > 0 && !this.scaffoldPositions.isEmpty()) {
         if (this.budgetReliefRejectedAtScaffoldCount != this.activeScaffoldCount()) {
            // A scaffold was really added or removed: earlier rejections may be stale.
            this.clearBudgetReliefRejections();
         }
         List<BlockPos> candidates = ScaffoldBudgetRules.nearestReliefCandidates(ScaffoldObstacleRules.budgetReliefCandidates(
            this.scaffoldPositions,
            start,
            this.workArea(),
            pos -> mc.level.hasChunkAt(pos) && this.isTrackedScaffoldBlock(mc, pos)
               && !this.budgetReliefRejectedTargets.contains(pos),
            pos -> this.isBlockSupportingPlayer(mc, pos)
         ));
         if (candidates.isEmpty()) {
            this.clearBudgetReliefSearch();
            return false;
         }

         int count = candidates.size();
         int candidateSignature = candidates.hashCode();
         if (!taskTarget.equals(this.budgetReliefSearchTaskKey) || !start.equals(this.budgetReliefSearchStart)
            || this.budgetReliefSearchCandidateSignature != candidateSignature
            || this.budgetReliefSearchCursor < 0 || this.budgetReliefSearchCursor > count) {
            this.budgetReliefSearchTaskKey = taskTarget.immutable();
            this.budgetReliefSearchStart = start.immutable();
            this.budgetReliefSearchCursor = 0;
            this.budgetReliefSearchCandidateSignature = candidateSignature;
         } else if (this.budgetReliefSearchCursor == count) {
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "budget-relief-search-exhausted-cached task=" + taskTarget.toShortString() + " candidates=" + count
            );
            return this.beginBudgetReliefPillarOrCoolDown(mc, start, now);
         }

         int first = this.budgetReliefSearchCursor;
         int attempts = Math.min(MAX_BUDGET_RELIEF_ROUTE_SEARCHES, count - first);
         SafeRoutePlanner.RoutePolicy cleanupPolicy = SafeRoutePlanner.RoutePolicy.flatteningFloor();

         for (int offset = 0; offset < attempts; offset++) {
            int index = (first + offset) % count;
            BlockPos candidate = candidates.get(index);
            List<BlockPos> poses = this.workPoses(mc, candidate, candidate, ConstructionController.WorkAction.BREAK);
            if (!poses.isEmpty()) {
               SafeRoutePlanner.PlanResult plan = SafeRoutePlanner.findRouteWithinVisitLimit(
                  mc, start, poses, this.workArea(), cleanupPolicy, BUDGET_RELIEF_ROUTE_VISITS
               );
               if (plan.hasRoute()) {
                  this.cancelOwnedWork(mc, "scaffold-budget-relief-route");
                  this.budgetReliefScaffoldTarget = candidate.immutable();
                  this.clearBudgetReliefSearch();
                  this.route = plan.steps();
                  this.routeIndex = 0;
                  this.routeTaskKey = candidate.immutable();
                  this.routeReturnState = ConstructionController.State.EXCAVATING;
                  this.routePolicy = cleanupPolicy;
                  this.routeRequiresTaskValidation = false;
                  this.routeNoProgressTicks = 0;
                  this.routeCompletionDeferredTicks = 0;
                  this.lastRouteDistance = Double.POSITIVE_INFINITY;
                  this.clearRouteFailures();
                  this.routeRetryCooldownTicks = 0;
                  this.state = this.route.isEmpty() ? ConstructionController.State.EXCAVATING : ConstructionController.State.POSITIONING;
                  this.resumeState = ConstructionController.State.EXCAVATING;
                  this.detail = "脚手预算已满；沿无桥安全路线回收自有脚手 " + candidate.toShortString();
                  TerraLevelerDiagnostics.log(
                     "SCAFFOLD",
                     "budget-relief-route task="
                        + taskTarget.toShortString()
                        + " start="
                        + start.toShortString()
                        + " target="
                        + candidate.toShortString()
                        + " steps="
                        + this.route.size()
                        + " candidates="
                        + count
                        + " searched="
                        + (first + offset + 1)
                        + "/"
                        + count
                        + " active="
                        + this.activeScaffoldCount()
                        + "/"
                        + this.jobScaffoldBudget
                  );
                  return true;
               }
            }
         }

         int next = first + attempts;
         if (next < count) {
            this.budgetReliefSearchCursor = next;
            this.state = ConstructionController.State.EXCAVATING;
            this.resumeState = ConstructionController.State.EXCAVATING;
            this.routeRetryCooldownTicks = ScaffoldBudgetRules.RELIEF_SCAN_STEP_TICKS;
            this.detail = "脚手预算已满；继续检查其余自有脚手的天然安全回收路线（" + next + "/" + count + "）";
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "budget-relief-search-continued task="
                  + taskTarget.toShortString()
                  + " candidates="
                  + count
                  + " searched="
                  + next
                  + " remaining="
                  + (count - next)
            );
            return true;
         }

         this.budgetReliefSearchCursor = count;
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "budget-relief-no-natural-route task="
               + taskTarget.toShortString()
               + " candidates="
               + count
               + " searched="
               + count
         );
         return this.beginBudgetReliefPillarOrCoolDown(mc, start, now);
      } else {
         this.clearBudgetReliefSearch();
         return false;
      }
   }

   /** Last resort of a relief scan; when even a pillar is impossible, the scan rests for a while. */
   private boolean beginBudgetReliefPillarOrCoolDown(Minecraft mc, BlockPos start, long now) {
      if (this.beginBudgetReliefPillar(mc, start)) {
         return true;
      }
      this.budgetReliefExhaustedTick = now;
      this.budgetReliefExhaustedCount = this.activeScaffoldCount();
      this.budgetReliefExhaustedStart = start.immutable();
      this.clearBudgetReliefSearch();
      TerraLevelerDiagnostics.log("SCAFFOLD", "budget-relief-cooldown start=" + start.toShortString() + " active=" + this.activeScaffoldCount()
         + "/" + this.jobScaffoldBudget + " ticks=" + ScaffoldBudgetRules.RELIEF_SCAN_COOLDOWN_TICKS);
      return false;
   }

   /**
    * With the scaffold budget full and no owned scaffold reachable on foot, the nearest
    * high scaffolds are taken down with a temporary pillar (the whole reachable cluster),
    * then excavation resumes. 0.8.73 instead kept working in rolling-replacement mode for
    * the last 14 minutes of its test and left the player on a single floating block.
    */
   private boolean beginBudgetReliefPillar(Minecraft mc, BlockPos start) {
      return this.beginBudgetReliefPillar(mc, start, Integer.MAX_VALUE);
   }

   private boolean beginBudgetReliefPillar(Minecraft mc, BlockPos start, int maximumDistance) {
      if (start == null || this.finalScaffoldPillarTarget != null
         || this.flattenFluidTransaction != null || !this.stablePlayerStand(mc)) {
         return false;
      }
      List<BlockPos> candidates = this.scaffoldPositions.stream()
         .filter(pos -> !this.clusterPillarRejected.contains(pos))
         .filter(pos -> pos.getY() >= start.getY() + 4)
         .filter(pos -> Math.abs(pos.getX() - start.getX()) + Math.abs(pos.getZ() - start.getZ()) <= maximumDistance)
         .filter(pos -> ObsoleteScaffoldRules.isTopOfTrackedColumn(this.scaffoldPositions, pos))
         .filter(pos -> mc.level.hasChunkAt(pos) && this.isTrackedScaffoldBlock(mc, pos) && !this.isBlockSupportingPlayer(mc, pos))
         .sorted(Comparator.comparingInt((BlockPos pos) -> Math.abs(pos.getX() - start.getX()) + Math.abs(pos.getZ() - start.getZ())))
         .limit(BUDGET_RELIEF_PILLAR_CANDIDATES)
         .toList();
      for (BlockPos candidate : candidates) {
         if (this.beginScaffoldPillarTransaction(mc, candidate, start, "budget-relief", false, true, ConstructionController.State.EXCAVATING)) {
            TerraLevelerDiagnostics.log("SCAFFOLD", "budget-relief-pillar target=" + candidate.toShortString() + " start=" + start.toShortString()
               + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget);
            return true;
         }
         this.clusterPillarRejected.add(candidate.immutable());
      }
      return false;
   }

   private void clearBudgetReliefSearch() {
      this.budgetReliefSearchCursor = 0;
      this.budgetReliefSearchTaskKey = null;
      this.budgetReliefSearchStart = null;
      this.budgetReliefSearchCandidateSignature = 0;
   }

   private void rememberBudgetReliefRejection(BlockPos target) {
      if (this.budgetReliefRejectedAtScaffoldCount != this.activeScaffoldCount()) {
         this.budgetReliefRejectedTargets.clear();
      }
      this.budgetReliefRejectedTargets.add(target.immutable());
      this.budgetReliefRejectedAtScaffoldCount = this.activeScaffoldCount();
   }

   private void clearBudgetReliefRejections() {
      this.budgetReliefRejectedTargets.clear();
      this.budgetReliefRejectedAtScaffoldCount = -1;
   }

   private int activeScaffoldCount() {
      return this.scaffoldPositions.size();
   }

   private BlockPos ownedSupportingScaffold(Minecraft mc, BlockPos excluded) {
      return this.scaffoldPositions
         .stream()
         .filter(pos -> excluded == null || !excluded.equals(pos))
         .filter(pos -> this.isTrackedScaffoldBlock(mc, pos))
         .filter(pos -> this.isBlockSupportingPlayer(mc, pos))
         .min(Comparator.comparingDouble(pos -> mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)))
         .<BlockPos>map(BlockPos::immutable)
         .orElse(null);
   }

   private boolean hasRollingScaffoldCapacity(Minecraft mc, BlockPos excluded) {
      return this.hasRollingScaffoldCapacity(mc, excluded, this.jobScaffoldBudget);
   }

   private boolean hasRollingScaffoldCapacity(Minecraft mc, BlockPos excluded, int scaffoldBudget) {
      BlockPos ownedSupport = this.ownedSupportingScaffold(mc, excluded);
      boolean retirementPending = this.rollingScaffoldReplacementTarget != null;
      return RouteExecutionRules.mayUseRollingScaffoldReserve(this.activeScaffoldCount(), scaffoldBudget, ownedSupport != null, retirementPending)
         || RouteExecutionRules.mayBootstrapRollingScaffoldOverflow(
            this.activeScaffoldCount(),
            scaffoldBudget,
            ownedSupport == null && this.stablePlayerStand(mc),
            this.rollingScaffoldOverflowTarget != null,
            retirementPending
         )
         || RouteExecutionRules.mayAdvanceRollingScaffoldOverflow(
            this.activeScaffoldCount(),
            scaffoldBudget,
            ownedSupport != null && ownedSupport.equals(this.rollingScaffoldOverflowTarget),
            this.rollingScaffoldOverflowTarget != null,
            retirementPending
         );
   }

   private void tickRollingScaffoldRetirement(Minecraft mc, TerraLevelerConfig config) {
      BlockPos oldSupport = this.rollingScaffoldRetireTarget;
      BlockPos replacement = this.rollingScaffoldReplacementTarget;
      if (replacement == null) {
         this.clearRollingScaffoldSwap();
      } else if (oldSupport == null) {
         if (!this.isTrackedScaffoldBlock(mc, replacement)) {
            this.pause(mc, "滚动脚手的天然支撑启动锚点未通过服务器世界状态确认 " + replacement.toShortString());
         } else {
            this.rollingScaffoldOverflowTarget = replacement.immutable();
            this.rollingScaffoldOverflowWasPlayerSupport = false;
            this.clearRollingScaffoldSwap();
            this.clearRouteFailures();
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "rolling-overflow-established target=" + replacement.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
            );
         }
      } else if (!this.isTrackedScaffoldBlock(mc, oldSupport)) {
         this.actions.cancel(mc, "rolling-retire-confirmed-by-world");
         this.forgetScaffold(oldSupport);
         this.advanceRollingScaffoldOverflow(oldSupport, replacement);
         this.clearRollingScaffoldSwap();
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "rolling-retire-already-gone target=" + oldSupport.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
         );
      } else if (!this.isTrackedScaffoldBlock(mc, replacement)) {
         this.pause(mc, "滚动脚手的新支撑未通过服务器世界状态确认 " + replacement.toShortString());
      } else {
         boolean oldSupportStillCarriesPlayer = this.isBlockSupportingPlayer(mc, oldSupport);
         boolean replacementCarriesPlayer = this.isBlockSupportingPlayer(mc, replacement);
         if (!RouteExecutionRules.mayRetireRollingScaffold(this.stablePlayerStand(mc), oldSupportStillCarriesPlayer, replacementCarriesPlayer)) {
            BlockPos replacementFeet = replacement.above();
            BlockPos origin = PlayerPhysicalState.routeAnchor(mc).orElse(replacementFeet);
            if (replacementFeet.getY() > mc.player.getY() + 0.5) {
               AutoNavigator.climbToward(mc, origin, replacementFeet);
            } else {
               AutoNavigator.moveToward(mc, replacementFeet, false);
            }

            this.detail = "新脚手已确认；先离开待退役旧支撑 " + oldSupport.toShortString();
         } else if (!this.safeReach(mc, oldSupport)) {
            this.pause(mc, "滚动脚手旧支撑离开玩家正常触及距离，无法安全完成预算交换 " + oldSupport.toShortString());
         } else if (!ServerActionExecutor.hasSafeEffectiveTool(mc, mc.level.getBlockState(oldSupport), config)) {
            if (this.beginToolBoxSwap(mc, this.state, this.breakPurpose, oldSupport, config)) return;
            this.pause(mc, "滚动脚手旧支撑清理缺少高于保留耐久的有效工具 " + oldSupport.toShortString());
         } else {
            AutoNavigator.release(mc);
            if (!this.handleBlockingAnimal(mc, config, oldSupport, "rolling-scaffold-retire")) {
               ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, oldSupport, config);
               if (status != null) {
                  if (status == ServerActionExecutor.Status.RUNNING) {
                     this.detail = "拆除已离开的旧支撑并归还脚手预算 " + oldSupport.toShortString();
                  } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                     this.pause(mc, "滚动脚手旧支撑清理时失去正常触及距离 " + oldSupport.toShortString());
                  } else if (status == ServerActionExecutor.Status.FAILED) {
                     this.pause(mc, this.actionFailure("滚动脚手旧支撑清理失败"));
                  } else if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
                     this.pause(mc, "滚动脚手旧支撑清理返回异常状态 " + status);
                  } else {
                     this.forgetScaffold(oldSupport);
                     this.advanceRollingScaffoldOverflow(oldSupport, replacement);
                     this.clearRollingScaffoldSwap();
                     this.clearRouteFailures();
                     this.startActionCooldown(config);
                     TerraLevelerDiagnostics.log(
                        "SCAFFOLD",
                        "rolling-retire-confirmed target="
                           + oldSupport.toShortString()
                           + " active="
                           + this.activeScaffoldCount()
                           + "/"
                           + this.jobScaffoldBudget
                     );
                  }
               }
            }
         }
      }
   }

   private boolean tickFlattenCleanupRouteScaffoldRetirement(Minecraft mc, TerraLevelerConfig config) {
      if (!this.routeIsFlattenCleanupReturn && this.flattenCleanupRouteRetireTarget == null) {
         return false;
      }

      this.flattenCleanupRouteScaffolds.removeIf(position -> {
         if (this.isTrackedScaffoldBlock(mc, position)) {
            return false;
         }

         this.forgetScaffold(position);
         return true;
      });
      if (this.routeIsFlattenCleanupReturn && this.flattenCleanupRouteRetireTarget == null) {
         Set<BlockPos> futureSupports = this.routeSupports();

         for (BlockPos candidate : this.flattenCleanupRouteScaffolds) {
            if (RouteExecutionRules.mayRetireFlattenCleanupRouteScaffold(
               this.flattenFinalCleanupPass,
               this.stablePlayerStand(mc),
               this.isTrackedScaffoldBlock(mc, candidate),
               this.isBlockSupportingPlayer(mc, candidate),
               futureSupports.contains(candidate),
               this.safeReach(mc, candidate)
            )) {
               this.flattenCleanupRouteRetireTarget = candidate.immutable();
               break;
            }
         }
      }

      BlockPos target = this.flattenCleanupRouteRetireTarget;
      if (target == null) {
         if (this.routeIsFlattenCleanupReturn && this.routeIndex >= this.route.size()) {
            if (this.flattenCleanupRouteScaffolds.isEmpty()) {
               this.completeFlattenCleanupRouteReturn(mc);
            } else {
               this.pause(mc, "已返回首轮清场路线起点，但仍有 " + this.flattenCleanupRouteScaffolds.size() + " 个路线脚手不在安全触及范围；保留精确账本并停止");
            }

            return true;
         } else {
            return false;
         }
      } else {
         if (!this.isTrackedScaffoldBlock(mc, target)) {
            this.actions.cancel(mc, "flatten-cleanup-route-tail-already-gone");
            this.forgetScaffold(target);
            this.flattenCleanupRouteScaffolds.remove(target);
            this.flattenCleanupRouteRetireTarget = null;
            return true;
         }

         if (!this.isBlockSupportingPlayer(mc, target) && !this.routeSupports().contains(target) && this.stablePlayerStand(mc) && this.safeReach(mc, target)) {
            AutoNavigator.release(mc);
            if (this.waitForActionCooldown(mc) && this.actions.isIdle()) {
               return true;
            } else if (this.handleBlockingAnimal(mc, config, target, "flatten-cleanup-route-tail")) {
               return true;
            } else {
               ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, target, config);
               if (status == null) {
                  return true;
               } else if (status == ServerActionExecutor.Status.RUNNING) {
                  this.detail = "沿首轮清场路线回收身后临时脚手 " + target.toShortString();
                  return true;
               } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                  this.actions.cancel(mc, "flatten-cleanup-route-tail-reposition");
                  this.flattenCleanupRouteRetireTarget = null;
                  TerraLevelerDiagnostics.log("SCAFFOLD", "flatten-cleanup-route-tail-deferred target=" + target.toShortString() + " reason=reposition");
                  return true;
               } else if (status == ServerActionExecutor.Status.FAILED) {
                  this.pause(mc, this.actionFailure("首轮清场路线临时脚手回收失败"));
                  return true;
               } else if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
                  this.pause(mc, "首轮清场路线临时脚手回收返回异常状态 " + status);
                  return true;
               } else {
                  this.actions.cancel(mc, "flatten-cleanup-route-tail-confirmed");
                  this.forgetScaffold(target);
                  this.flattenCleanupRouteScaffolds.remove(target);
                  this.flattenCleanupRouteRetireTarget = null;
                  this.startActionCooldown(config);
                  TerraLevelerDiagnostics.log(
                     "SCAFFOLD",
                     "flatten-cleanup-route-tail-retired target="
                        + target.toShortString()
                        + " remainingRouteSupports="
                        + this.flattenCleanupRouteScaffolds.size()
                        + " active="
                        + this.activeScaffoldCount()
                        + "/"
                        + this.jobScaffoldBudget
                  );
                  return true;
               }
            }
         } else {
            this.actions.cancel(mc, "flatten-cleanup-route-tail-no-longer-safe");
            this.flattenCleanupRouteRetireTarget = null;
            return false;
         }
      }
   }

   private void rebaseFlattenCleanupRouteAnchorAtFirstScaffold(BlockPos confirmed) {
      if (this.flattenCleanupRouteReturnAnchor == null || this.routeIndex >= this.route.size()) {
         return;
      }

      SafeRoutePlanner.RouteStep step = this.route.get(this.routeIndex);
      BlockPos rebasedAnchor = FlattenCleanupExcursionRules.firstScaffoldAnchor(
         this.flattenCleanupRouteScaffolds.size(), step, confirmed
      ).orElse(null);
      if (rebasedAnchor == null) {
         return;
      }

      BlockPos previousAnchor = this.flattenCleanupRouteReturnAnchor;
      int discardedSteps = this.flattenCleanupOutboundRoute.size();
      this.flattenCleanupRouteReturnAnchor = rebasedAnchor;
      this.flattenCleanupOutboundRoute.clear();
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-route-anchor-rebased old="
            + previousAnchor.toShortString()
            + " new="
            + this.flattenCleanupRouteReturnAnchor.toShortString()
            + " firstScaffold="
            + confirmed.toShortString()
            + " discardedNaturalSteps="
            + discardedSteps
      );
   }

   private boolean recordFlattenCleanupOutboundStep(Minecraft mc, SafeRoutePlanner.RouteStep step) {
      if (this.flattenCleanupRouteReturnAnchor == null
         || this.routeIsFlattenCleanupReturn
         || this.routeReturnState != ConstructionController.State.CLEANING_SCAFFOLDS) {
         return true;
      }

      BlockPos expectedOrigin = this.flattenCleanupOutboundRoute.isEmpty()
         ? this.flattenCleanupRouteReturnAnchor
         : this.flattenCleanupOutboundRoute.getLast().position();
      if (step.origin() == null || !expectedOrigin.equals(step.origin())) {
         this.pause(
            mc,
            "最终清场去程记录不连续；已保留全部自有脚手账本，拒绝猜测返程。预期 "
               + expectedOrigin.toShortString()
               + "，实际 "
               + posText(step.origin())
         );
         return false;
      }

      this.flattenCleanupOutboundRoute.add(
         new SafeRoutePlanner.RouteStep(step.origin().immutable(), step.position().immutable(), step.type(), step.interiorApproachGoal())
      );
      return true;
   }

   private boolean checkpointFlattenCleanupOutboundPosition(Minecraft mc, String reason, boolean allowAdjacentReconciliation) {
      if (!this.hasActiveFlattenCleanupExcursion()) {
         return true;
      }

      BlockPos committedEnd = this.flattenCleanupOutboundRoute.isEmpty()
         ? this.flattenCleanupRouteReturnAnchor
         : this.flattenCleanupOutboundRoute.getLast().position();
      BlockPos current = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (current == null) {
         this.logFlattenCleanupCheckpointRejected(reason, "NO_STABLE_ANCHOR", committedEnd, null);
         return false;
      }
      if (current.equals(committedEnd)) {
         return true;
      }

      SafeRoutePlanner.RouteStep candidate = null;
      String mode = "pending";
      if (this.routeIndex < this.route.size()) {
         SafeRoutePlanner.RouteStep pending = this.route.get(this.routeIndex);
         if (FlattenCleanupCheckpointRules.matchesPendingStep(committedEnd, current, pending)) {
            candidate = pending;
         }
      }

      if (candidate == null && allowAdjacentReconciliation) {
         candidate = FlattenCleanupCheckpointRules.adjacentStep(committedEnd, current).orElse(null);
         mode = "adjacent-reconciliation";
      }
      if (candidate == null) {
         this.logFlattenCleanupCheckpointRejected(reason, "NOT_PENDING_OR_ADJACENT", committedEnd, current);
         return false;
      }

      SafeRoutePlanner.RoutePolicy validationPolicy = mode.equals("pending")
         ? this.routePolicy
         : SafeRoutePlanner.RoutePolicy.flatteningFloor();
      SafeRoutePlanner.StepValidation validation = SafeRoutePlanner.validateStep(
         mc, candidate, this.workArea(), validationPolicy
      );
      if (!validation.canMoveNow()) {
         this.logFlattenCleanupCheckpointRejected(reason, validation.status().name(), committedEnd, current);
         return false;
      }
      if (!this.recordFlattenCleanupOutboundStep(mc, candidate)) {
         return false;
      }

      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-excursion-checkpoint-recorded reason="
            + reason
            + " mode="
            + mode
            + " origin="
            + committedEnd.toShortString()
            + " current="
            + current.toShortString()
            + " type="
            + candidate.type()
            + " committedSteps="
            + this.flattenCleanupOutboundRoute.size()
      );
      return true;
   }

   private void logFlattenCleanupCheckpointRejected(String reason, String status, BlockPos committedEnd, BlockPos current) {
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-excursion-checkpoint-rejected reason="
            + reason
            + " status="
            + status
            + " committedEnd="
            + posText(committedEnd)
            + " current="
            + posText(current)
      );
   }

   private void beginFlattenCleanupRouteReturn(Minecraft mc) {
      boolean checkpointAccepted = this.checkpointFlattenCleanupOutboundPosition(mc, "route-return", true);
      if (FlattenCleanupCheckpointRules.shouldAbandonRecordedRoute(
         this.hasActiveFlattenCleanupExcursion(), checkpointAccepted
      )) {
         this.abortInvalidFlattenCleanupReturn(mc, "route-return-checkpoint-disconnected");
         return;
      }
      BlockPos anchor = this.flattenCleanupRouteReturnAnchor;
      BlockPos goal = anchor != null && this.flattenCleanupRouteTask != null && this.flattenCleanupRouteTask.equals(anchor.below())
         ? anchor.below()
         : anchor;
      if (anchor != null && goal != null && !this.flattenCleanupRouteScaffolds.isEmpty()) {
         BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(null);
         if (start == null) {
            this.pause(mc, "首轮清场路线准备撤回时没有稳定路线锚点；保留全部路线脚手");
         } else if (SafeRoutePlanner.canOccupy(mc, goal) && SafeRoutePlanner.hasFloor(mc, goal)) {
            SafeRoutePlanner.RoutePolicy returnPolicy = SafeRoutePlanner.RoutePolicy.flatteningFloor();
             List<SafeRoutePlanner.RouteStep> reversed = CleanupRouteReversal.reverse(this.flattenCleanupOutboundRoute, start, anchor, goal);
            if (FlattenCleanupCheckpointRules.shouldAbandonExactReverse(start.equals(goal), reversed.isEmpty())) {
               this.abortInvalidFlattenCleanupReturn(
                  mc, "exact-reverse-empty start=" + start.toShortString() + " goal=" + goal.toShortString()
               );
            } else {
               this.cancelOwnedWork(mc, "flatten-cleanup-route-return");
               this.route = reversed;
               this.routeIndex = 0;
               this.routeTaskKey = goal.immutable();
               this.routeReturnState = ConstructionController.State.CLEANING_SCAFFOLDS;
               this.routePolicy = returnPolicy;
               this.routeRequiresTaskValidation = false;
               this.routeNeedsContinuation = false;
               this.routeNoProgressTicks = 0;
               this.routeCompletionDeferredTicks = 0;
               this.lastRouteDistance = Double.POSITIVE_INFINITY;
               this.flattenCleanupRouteReturnPending = false;
               this.routeIsFlattenCleanupReturn = true;
               this.state = ConstructionController.State.POSITIONING;
               this.resumeState = ConstructionController.State.CLEANING_SCAFFOLDS;
               this.detail = "沿首轮既有清场路线撤回安全起点并回收脚手";
               TerraLevelerDiagnostics.log(
                  "SCAFFOLD",
                  "flatten-cleanup-route-return-start from="
                     + start.toShortString()
                     + " to="
                     + goal.toShortString()
                     + " steps="
                     + this.route.size()
                      + " routeCreated="
                      + this.flattenCleanupRouteScaffolds.size()
                      + " mode=exact-reverse bridgeAllowed=false"
               );
            }
         } else {
            this.abortInvalidFlattenCleanupReturn(mc, "return-anchor-changed goal=" + goal.toShortString());
         }
      } else {
         this.flattenCleanupRouteReturnAnchor = null;
         this.flattenCleanupRouteTask = null;
         this.flattenCleanupRouteReturnPending = false;
         this.routeIsFlattenCleanupReturn = false;
         this.flattenCleanupOutboundRoute.clear();
         this.flattenCleanupExcursionDeferredTargets.clear();
         this.flattenCleanupReservedScaffoldPeak = 0;
      }
   }

   private void abortInvalidFlattenCleanupReturn(Minecraft mc, String reason) {
      BlockPos origin = PlayerPhysicalState.routeAnchor(mc).orElse(mc.player.blockPosition());
      int routeCreated = this.flattenCleanupRouteScaffolds.size();
      this.actions.cancel(mc, "flatten-cleanup-return-aborted");
      AutoNavigator.release(mc);
      this.clearRoute();
      FlattenScaffoldCleanupScheduler.Result schedule = FlattenScaffoldCleanupScheduler.schedule(
         List.copyOf(this.scaffoldPositions), origin
      );
      this.scaffoldCleanupTargets = schedule.targets();
      this.scaffoldCleanupClusters = schedule.clusterIds();
      this.scaffoldCleanupIndex = 0;
      this.flattenCleanupRouteScaffolds.clear();
      this.flattenCleanupRouteRetireTarget = null;
      this.flattenCleanupRouteReturnAnchor = null;
      this.flattenCleanupRouteTask = null;
      this.flattenCleanupRouteReturnPending = false;
      this.routeIsFlattenCleanupReturn = false;
      this.flattenCleanupOutboundRoute.clear();
      this.flattenCleanupExcursionDeferredTargets.clear();
      this.flattenCleanupReservedScaffoldPeak = 0;
      this.routeReservedScaffoldBudget = 0;
      this.resetFlattenCleanupTopologyPlanning();
      this.clearRouteFailures();
      this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
      this.resumeState = this.state;
      this.detail = "旧清场返程已终止；保留脚手账本并从真实位置重新寻路";
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "flatten-cleanup-route-return-aborted reason="
            + reason
            + " origin="
            + origin.toShortString()
            + " routeCreated="
            + routeCreated
            + " preserved="
            + schedule.targets().size()
            + " clusters="
            + schedule.clusterCount()
      );
   }

   private void completeFlattenCleanupRouteReturn(Minecraft mc) {
      BlockPos anchor = this.flattenCleanupRouteReturnAnchor;
      AutoNavigator.release(mc);
      this.clearRoute();
      this.flattenCleanupRouteReturnAnchor = null;
      this.flattenCleanupRouteTask = null;
      this.flattenCleanupRouteRetireTarget = null;
      this.flattenCleanupRouteReturnPending = false;
      this.routeIsFlattenCleanupReturn = false;
      this.flattenCleanupOutboundRoute.clear();
      this.flattenCleanupExcursionDeferredTargets.clear();
      this.flattenCleanupReservedScaffoldPeak = 0;
      this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
      this.resumeState = this.state;
      this.clearRouteFailures();
      this.detail = "当前目标簇清场路线已撤回并回收，继续选择下一目标簇或目标";
      TerraLevelerDiagnostics.log(
         "SCAFFOLD", "flatten-cleanup-route-return-complete anchor=" + posText(anchor) + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
      );
   }

   private void clearRollingScaffoldSwap() {
      this.rollingScaffoldRetireTarget = null;
      this.rollingScaffoldReplacementTarget = null;
      this.rollingScaffoldReplacementConfirmed = false;
      this.rollingScaffoldDetachedConfirmationTicks = 0;
   }

   private boolean tickDetachedRollingScaffoldPlacement(Minecraft mc) {
      BlockPos replacement = this.rollingScaffoldReplacementTarget;
      if (replacement == null || this.rollingScaffoldReplacementConfirmed || this.bridgePlacementTarget != null) {
         return false;
      }

      Block expected = this.submittedScaffoldBlocks.get(replacement);
      if (expected == null) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "rolling-detached-aborted target=" + replacement.toShortString() + " reason=no-submitted-placement");
         this.clearRollingScaffoldSwap();
         return false;
      }

      boolean loaded = mc.level.hasChunkAt(replacement);
      boolean observedExpected = loaded && mc.level.getBlockState(replacement).getBlock() == expected;
      boolean predictionPending = observedExpected && ServerActionExecutor.hasPendingBlockPrediction(mc, replacement);
      this.rollingScaffoldDetachedConfirmationTicks++;
      if (observedExpected
         && (!predictionPending || this.rollingScaffoldDetachedConfirmationTicks >= MAX_DETACHED_ROLLING_PREDICTION_TICKS)) {
         this.confirmSubmittedScaffoldPlacement(mc, replacement);
         this.rollingScaffoldReplacementConfirmed = true;
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "rolling-detached-confirmed target=" + replacement.toShortString()
               + " waited=" + this.rollingScaffoldDetachedConfirmationTicks
               + " conservative=" + predictionPending
               + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
         );
         return true;
      }

      if (this.rollingScaffoldDetachedConfirmationTicks < MAX_DETACHED_ROLLING_CONFIRMATION_TICKS
         || observedExpected && this.rollingScaffoldDetachedConfirmationTicks < MAX_DETACHED_ROLLING_PREDICTION_TICKS) {
         AutoNavigator.release(mc);
         this.detail = "等待被打断的滚动脚手服务器确认 " + replacement.toShortString()
            + "（" + this.rollingScaffoldDetachedConfirmationTicks + "/" + MAX_DETACHED_ROLLING_CONFIRMATION_TICKS + "）";
         return true;
      }

      this.submittedScaffoldBlocks.remove(replacement);
      this.persistOwnedScaffolds();
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "rolling-detached-expired target=" + replacement.toShortString()
            + " loaded=" + loaded + " observed=" + (loaded ? mc.level.getBlockState(replacement).getBlock() : "unloaded")
      );
      this.clearRollingScaffoldSwap();
      this.routeRetryCooldownTicks = 1;
      return true;
   }

   private void advanceRollingScaffoldOverflow(BlockPos retired, BlockPos replacement) {
      if (retired.equals(this.rollingScaffoldOverflowTarget)) {
         this.rollingScaffoldOverflowTarget = replacement.immutable();
         this.rollingScaffoldOverflowWasPlayerSupport = true;
      }
   }

   private void tickRollingScaffoldOverflowRelease(Minecraft mc, TerraLevelerConfig config) {
      BlockPos overflow = this.rollingScaffoldOverflowTarget;
      if (overflow != null) {
         if (this.activeScaffoldCount() <= this.jobScaffoldBudget) {
            this.rollingScaffoldOverflowTarget = null;
            this.rollingScaffoldOverflowWasPlayerSupport = false;
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "rolling-overflow-normalized target=" + overflow.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
            );
         } else if (!this.isTrackedScaffoldBlock(mc, overflow)) {
            this.actions.cancel(mc, "rolling-overflow-confirmed-by-world");
            this.forgetScaffold(overflow);
            this.rollingScaffoldOverflowTarget = null;
            this.rollingScaffoldOverflowWasPlayerSupport = false;
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "rolling-overflow-already-gone target=" + overflow.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
            );
         } else if (this.batchBreaks.pendingCount() > 0) {
            AutoNavigator.release(mc);
            this.detail = "等待批量破坏确认后回收滚动余量脚手 " + overflow.toShortString();
         } else if (!this.safeReach(mc, overflow)) {
            this.rollingScaffoldOverflowTarget = null;
            this.rollingScaffoldOverflowWasPlayerSupport = false;
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "rolling-overflow-deferred-to-ledger target="
                  + overflow.toShortString()
                  + " reason=out-of-reach active="
                  + this.activeScaffoldCount()
                  + "/"
                  + this.jobScaffoldBudget
            );
            this.detail = "远端滚动脚手已保留在自有脚手账本，稍后按安全路线回收 " + overflow.toShortString();
         } else if (!ServerActionExecutor.hasSafeEffectiveTool(mc, mc.level.getBlockState(overflow), config)) {
            if (this.beginToolBoxSwap(mc, this.state, this.breakPurpose, overflow, config)) return;
            this.pause(mc, "滚动余量脚手回收缺少高于保留耐久的有效工具 " + overflow.toShortString());
         } else {
            AutoNavigator.release(mc);
            if (!this.handleBlockingAnimal(mc, config, overflow, "rolling-overflow-release")) {
               ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, overflow, config);
               if (status != null) {
                  if (status == ServerActionExecutor.Status.RUNNING) {
                     this.detail = "落回天然支撑后回收滚动余量脚手 " + overflow.toShortString();
                  } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                     this.pause(mc, "滚动余量脚手回收时失去正常触及距离 " + overflow.toShortString());
                  } else if (status == ServerActionExecutor.Status.FAILED) {
                     this.pause(mc, this.actionFailure("滚动余量脚手回收失败"));
                  } else if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
                     this.pause(mc, "滚动余量脚手回收返回异常状态 " + status);
                  } else {
                     this.forgetScaffold(overflow);
                     this.rollingScaffoldOverflowTarget = null;
                     this.rollingScaffoldOverflowWasPlayerSupport = false;
                     this.clearRouteFailures();
                     this.startActionCooldown(config);
                     TerraLevelerDiagnostics.log(
                        "SCAFFOLD",
                        "rolling-overflow-released target=" + overflow.toShortString() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
                     );
                  }
               }
            }
         }
      }
   }

   private int pruneLoadedStaleScaffolds(Minecraft mc, String reason) {
      if (mc.level != null && !this.scaffoldPositions.isEmpty()) {
         List<BlockPos> stale = this.scaffoldPositions
            .stream()
            .filter(mc.level::hasChunkAt)
            .filter(posx -> !this.submittedScaffoldBlocks.containsKey(posx))
            .filter(posx -> !this.isTrackedScaffoldBlock(mc, posx))
            .<BlockPos>map(BlockPos::immutable)
            .toList();
         if (stale.isEmpty()) {
            return 0;
         }

         for (BlockPos pos : stale) {
            this.scaffoldPositions.remove(pos);
            this.scaffoldBlocks.remove(pos);
         }

         this.persistOwnedScaffolds();
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "loaded-stale-pruned reason=" + reason + " removed=" + stale.size() + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
         );
         return stale.size();
      } else {
         return 0;
      }
   }

   private boolean forgetScaffold(BlockPos pos) {
      if (pos == null) {
         return false;
      }

      boolean submittedRemoved = this.submittedScaffoldBlocks.remove(pos) != null;
      this.scaffoldBlocks.remove(pos);
      boolean removed = this.scaffoldPositions.remove(pos);
      if (removed) this.scaffoldRemovalSerial++;
      if (removed) {
         // The inherited allowance stays fixed for the job: taking an inherited scaffold down frees its place.
         this.inheritedScaffolds.remove(pos);
      }
      if (removed || submittedRemoved) {
         this.pendingFlattenCleanupReplan = null;
         this.persistOwnedScaffolds();
      }

      return removed || submittedRemoved;
   }

   private ServerActionExecutor.Status tickBreakWithTrackedScaffoldToolGuard(Minecraft mc, BlockPos target, TerraLevelerConfig config) {
      ConstructionController.State requestedState = this.state;
      ConstructionController.BreakPurpose requestedPurpose = this.breakPurpose;
      boolean allowToolBoxRequest = config.useToolBoxForTools && !TerraLevelerConfig.sanitizeToolBoxNames(config.toolBoxNames).isEmpty();
      boolean recorded = target != null && this.scaffoldPositions.contains(target);
      boolean worldConfirmed = recorded && this.isTrackedScaffoldBlock(mc, target);
      BlockState targetState = mc.level.getBlockState(target);
      boolean requiresManagedTool = ServerActionExecutor.requiresManagedTool(targetState);
      ServerActionExecutor.ToolPreparation toolPreparation = requiresManagedTool
         || requiresSafeEffectiveToolForTrackedScaffold(recorded, worldConfirmed)
         ? ServerActionExecutor.prepareSafeEffectiveTool(mc, mc.level.getBlockState(target), config)
         : ServerActionExecutor.ToolPreparation.READY;
      if (toolPreparation == ServerActionExecutor.ToolPreparation.PENDING) {
         AutoNavigator.release(mc);
         this.detail = "等待服务器确认满背包工具原位交换 " + target.toShortString();
         return ServerActionExecutor.Status.RUNNING;
      }
      if (toolPreparation == ServerActionExecutor.ToolPreparation.UNAVAILABLE && requiresManagedTool) {
         if (allowToolBoxRequest && this.beginToolBoxSwap(mc, requestedState, requestedPurpose, target, config)) {
            return null;
         }
         if (allowToolBoxRequest) return null;
         this.pause(mc, "当前目标需要镐子或铲子，但背包中没有高于耐久保护值的有效工具，且自动工具潜影盒已关闭；不会徒手破坏 "
            + target.toShortString());
         return null;
      } else if (toolPreparation == ServerActionExecutor.ToolPreparation.UNAVAILABLE) {
         TerraLevelerDiagnostics.log(
            "TOOL",
            "tracked-scaffold-cleanup-blocked target="
               + target.toShortString()
               + " purpose="
               + this.breakPurpose
               + " reason=no-safe-effective-tool-above-reserve"
         );
         this.pause(mc, "自有临时脚手清理缺少高于保留耐久的有效工具 " + target.toShortString());
         return null;
      }

      ServerActionExecutor.Status status = this.actions.tickBreak(mc, target, config, true, allowToolBoxRequest);
      if (status == ServerActionExecutor.Status.TOOL_BOX_REQUIRED) {
         if (this.beginToolBoxSwap(mc, requestedState, requestedPurpose, target, config)) return null;
         return ServerActionExecutor.Status.RUNNING;
      }
      if (!ownsBreakResult(requestedState, requestedPurpose, target, this.state, this.breakPurpose, this.breakTarget)) {
         this.actions.cancel(mc, "break-owner-changed-during-tick");
         AutoNavigator.release(mc);
         TerraLevelerDiagnostics.log(
            "ACTION",
            "break-result-discarded requestedPurpose="
               + requestedPurpose
               + " requestedState="
               + requestedState
               + " requestedTarget="
               + posText(target)
               + " currentPurpose="
               + this.breakPurpose
               + " currentState="
               + this.state
               + " currentTarget="
               + posText(this.breakTarget)
               + " status="
               + status
         );
         return null;
      }
      return status;
   }

   private boolean beginToolBoxSwap(
      Minecraft mc,
      ConstructionController.State returnState,
      ConstructionController.BreakPurpose returnPurpose,
      BlockPos target,
      TerraLevelerConfig config
   ) {
      if (target == null || !config.useToolBoxForTools || TerraLevelerConfig.sanitizeToolBoxNames(config.toolBoxNames).isEmpty()) return false;
      ToolBoxTransaction.BeginResult result = this.toolBoxTransaction.begin(
         mc, mc.level.getBlockState(target), config.durabilityReserve, config.toolBoxNames
      );
      if (result != ToolBoxTransaction.BeginResult.STARTED) {
         boolean inventoryCleanupSubmitted = result == ToolBoxTransaction.BeginResult.NEEDS_HOTBAR_SPACE
            && this.maybeStoreInventoryOverflow(mc);
         if (shouldRetryToolBoxAfterInventoryCleanup(
            result, inventoryCleanupSubmitted, this.inventoryDiscardSettleTicks
         )) {
            this.actions.cancel(mc, "tool-box-hotbar-cleanup");
            AutoNavigator.release(mc);
            this.toolBoxTransaction.reset();
            this.detail = "工具盒取工具前已清理施工杂物，等待服务器确认后重试";
            TerraLevelerDiagnostics.log("TOOLBOX", "hotbar-space-cleanup target=" + target.toShortString());
            return true;
         }
         String failure = this.toolBoxTransaction.failure();
         this.toolBoxTransaction.reset();
         this.actions.cancel(mc, "tool-box-begin-failed");
         AutoNavigator.release(mc);
         this.pause(mc, "配置的工具潜影盒无法提供安全工具（" + failure + "）；不会徒手破坏目标 " + target.toShortString());
         return true;
      }

      this.actions.cancel(mc, "tool-box-start");
      AutoNavigator.release(mc);
      this.toolBoxReturnState = returnState;
      this.toolBoxReturnPurpose = returnPurpose;
      this.toolBoxTarget = target.immutable();
      this.state = ConstructionController.State.SWAPPING_TOOL;
      this.detail = this.toolBoxTransaction.detail();
      return true;
   }

   static boolean shouldRetryToolBoxAfterInventoryCleanup(
      ToolBoxTransaction.BeginResult result, boolean inventoryCleanupSubmitted, int inventoryCleanupSettleTicks
   ) {
      return result == ToolBoxTransaction.BeginResult.NEEDS_HOTBAR_SPACE
         && (inventoryCleanupSubmitted || inventoryCleanupSettleTicks > 0);
   }

   private void tickToolBoxSwap(Minecraft mc) {
      AutoNavigator.release(mc);
      ToolBoxTransaction.Result result = this.toolBoxTransaction.tick(mc);
      this.detail = this.toolBoxTransaction.detail();
      if (result == ToolBoxTransaction.Result.RUNNING) return;

      ConstructionController.State returnState = this.toolBoxReturnState;
      ConstructionController.BreakPurpose returnPurpose = this.toolBoxReturnPurpose;
      BlockPos returnTarget = this.toolBoxTarget;
      if (result == ToolBoxTransaction.Result.SUCCESS) {
         this.detail = "工具盒换装完成，重新处理原施工目标";
      } else {
         String failure = this.toolBoxTransaction.failure();
         this.toolBoxTransaction.reset();
         this.actions.cancel(mc, "tool-box-failed");
         this.state = returnState;
         this.breakPurpose = returnPurpose;
         this.breakTarget = returnTarget;
         this.pause(mc, "工具潜影盒事务未能安全完成（" + failure + "）；不会徒手破坏目标 " + posText(returnTarget));
         this.toolBoxReturnState = ConstructionController.State.EXCAVATING;
         this.toolBoxReturnPurpose = ConstructionController.BreakPurpose.NONE;
         this.toolBoxTarget = null;
         return;
      }
      this.toolBoxTransaction.reset();
      this.toolBoxReturnState = ConstructionController.State.EXCAVATING;
      this.toolBoxReturnPurpose = ConstructionController.BreakPurpose.NONE;
      this.toolBoxTarget = null;
      this.actions.cancel(mc, "tool-box-finished");
      this.state = returnState;
      this.breakPurpose = returnPurpose;
      this.breakTarget = returnTarget;
   }

   static boolean ownsBreakResult(
      ConstructionController.State requestedState,
      ConstructionController.BreakPurpose requestedPurpose,
      BlockPos requestedTarget,
      ConstructionController.State currentState,
      ConstructionController.BreakPurpose currentPurpose,
      BlockPos currentTarget
   ) {
      if (requestedState != currentState || requestedPurpose != currentPurpose) {
         return false;
      }
      return requestedPurpose == ConstructionController.BreakPurpose.NONE
         || requestedTarget != null && requestedTarget.equals(currentTarget);
   }

   static boolean requiresSafeEffectiveToolForTrackedScaffold(boolean recorded, boolean worldConfirmed) {
      return recorded && worldConfirmed;
   }

   private static String flattenScaffoldJobKey(TerraLevelerConfig config, FlattenPlan plan) {
      return String.join(
         "|",
         "FLATTEN",
         stored(config.lastFlattenSelectionScope),
         stored(config.lastFlattenSelectionDimension),
         Integer.toString(plan.minX()),
         Integer.toString(plan.minZ()),
         Integer.toString(plan.maxX()),
         Integer.toString(plan.maxZ()),
         Integer.toString(plan.targetGroundY()),
         Long.toUnsignedString(plan.geometryFingerprint())
      );
   }

   private static String stored(String value) {
      return value == null ? "unknown" : value;
   }

   private void restoreOwnedScaffolds(TerraLevelerConfig config, String jobKey) {
      this.scaffoldJobKey = jobKey;
      ScaffoldLedgerStore store = TerraLeveler.SCAFFOLD_LEDGERS;
      Minecraft mc = Minecraft.getInstance();
      // Records whose block is visibly gone (mined by hand, decayed) are dropped first.
      int pruned = store.pruneSameScope(jobKey, (pos, blockId) -> {
         if (mc.level == null || !mc.level.hasChunkAt(pos)) return false;
         Identifier id = Identifier.tryParse(blockId);
         Block expected = id == null ? null : (Block)BuiltInRegistries.BLOCK.getValue(id);
         return expected == null || !scaffoldBlockMatches(expected, mc.level.getBlockState(pos).getBlock());
      });
      // A restarted or re-selected job used to start a new ledger and leave the old
      // attempt's scaffolds in the world forever; take over those inside this area.
      WorkArea area = this.workArea();
      int adopted = area == null ? 0 : store.adoptOrphans(jobKey, pos -> area.permitsScaffoldAt(pos.getX(), pos.getZ()));
      if (pruned > 0 || adopted > 0) {
         store.saveAsync();
         TerraLevelerDiagnostics.log("SCAFFOLD", "ledger-housekeeping job=" + jobKey + " prunedStale=" + pruned
            + " adoptedFromOldJobs=" + adopted + " jobs=" + store.jobCount() + " entries=" + store.entryCount());
      }
      Map<String, String> ledger = store.ledger(jobKey);
      if (!ledger.isEmpty()) {
         int restored = 0;

         for (Entry<String, String> entry : ledger.entrySet()) {
            BlockPos pos = SelectionPersistence.decode(entry.getKey());
            Identifier id = Identifier.tryParse(entry.getValue());
            if (pos != null && id != null) {
               Block block = (Block)BuiltInRegistries.BLOCK.getValue(id);
               if (block != null && block != Blocks.AIR) {
                  if (this.scaffoldPositions.add(pos.immutable())) {
                     restored++;
                  }

                  this.scaffoldBlocks.put(pos.immutable(), block);
               }
            }
         }

         TerraLevelerDiagnostics.log("SCAFFOLD", "ledger-restored job=" + jobKey + " restored=" + restored + " stored=" + ledger.size());
      }

      this.inheritedScaffolds.clear();
      this.configuredScaffoldBudget = this.jobScaffoldBudget;
      if (this.jobScaffoldBudget > 0 && !this.scaffoldPositions.isEmpty()) {
         this.inheritedScaffolds.addAll(this.scaffoldPositions);
         this.jobScaffoldBudget = ScaffoldBudgetRules.withInheritedAllowance(this.configuredScaffoldBudget, this.inheritedScaffolds.size());
         TerraLevelerDiagnostics.log("SCAFFOLD", "inherited-scaffold-allowance inherited=" + this.inheritedScaffolds.size()
            + " configuredBudget=" + this.configuredScaffoldBudget + " effectiveBudget=" + this.jobScaffoldBudget);
      }
      this.baseScaffoldBudget = this.jobScaffoldBudget;
      this.parkedScaffoldAllowance = 0;
      this.lowestFlattenWorkY = Integer.MAX_VALUE;
   }

   /**
    * Own scaffolds left well above the lowest level already worked on (terrace bands go down six
    * blocks at a time) no longer carry the work. The final cleanup takes them down with pillars
    * from the flat ground, so they do not use up the budget for new placements. In the 0.8.92
    * test region -56,-158 (a mountain, top Y=103) had 129/128 scaffolds after 21 minutes, 86 of
    * them at Y=86..104 while the work was at Y=70..79; none could be reached for relief and a
    * 27-scaffold route paused the job ("临时脚手预算不足以安全执行当前路线").
    */
   private void refreshParkedScaffoldAllowance(BlockPos taskKey, ConstructionController.State returnState) {
      if (this.baseScaffoldBudget <= 0) {
         return;
      }
      if (returnState == ConstructionController.State.EXCAVATING && taskKey != null) {
         this.lowestFlattenWorkY = Math.min(this.lowestFlattenWorkY, taskKey.getY());
      }
      if (this.lowestFlattenWorkY == Integer.MAX_VALUE) {
         return;
      }
      int parked = ScaffoldBudgetRules.parkedScaffolds(this.scaffoldPositions, this.inheritedScaffolds, this.lowestFlattenWorkY);
      int budget = ScaffoldBudgetRules.withInheritedAllowance(this.baseScaffoldBudget, parked);
      if (parked != this.parkedScaffoldAllowance) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "parked-scaffold-allowance parked=" + parked + " lowestWorkY=" + this.lowestFlattenWorkY
            + " baseBudget=" + this.baseScaffoldBudget + " effectiveBudget=" + budget + " active=" + this.activeScaffoldCount());
      }
      this.parkedScaffoldAllowance = parked;
      this.jobScaffoldBudget = budget;
   }

   /** A dirt scaffold that grass has spread onto is still the same owned scaffold. */
   static boolean scaffoldBlockMatches(Block expected, Block actual) {
      return actual == expected || expected == Blocks.DIRT && actual == Blocks.GRASS_BLOCK;
   }

   private void rememberSubmittedScaffold(BlockPos pos, Block block) {
      if (pos != null && block != null && !this.scaffoldPositions.contains(pos)) {
         Block previous = this.submittedScaffoldBlocks.put(pos.immutable(), block);
         if (previous != block) {
            this.persistOwnedScaffolds();
            TerraLevelerDiagnostics.log("SCAFFOLD", "ledger-submitted block=" + pos.toShortString() + " material=" + block);
         }
      }
   }

   private void persistOwnedScaffolds() {
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      if (config != null && this.scaffoldJobKey != null) {
         Map<String, String> ledger = new LinkedHashMap<>();

         for (Entry<BlockPos, Block> entry : this.scaffoldBlocks.entrySet()) {
            if (this.scaffoldPositions.contains(entry.getKey())) {
               ledger.put(SelectionPersistence.encode(entry.getKey()), BuiltInRegistries.BLOCK.getKey(entry.getValue()).toString());
            }
         }

         for (Entry<BlockPos, Block> entry : this.submittedScaffoldBlocks.entrySet()) {
            ledger.put(SelectionPersistence.encode(entry.getKey()), BuiltInRegistries.BLOCK.getKey(entry.getValue()).toString());
         }

         TerraLeveler.SCAFFOLD_LEDGERS.replace(this.scaffoldJobKey, ledger);

         if (!this.scaffoldLedgerDirty) this.scaffoldLedgerSaveTicks = 20;
         this.scaffoldLedgerDirty = true;
      }
   }

   private void tickOwnedScaffoldLedgerSave() {
      if (!this.scaffoldLedgerDirty) return;
      if (this.scaffoldLedgerSaveTicks > 0) this.scaffoldLedgerSaveTicks--;
      if (this.scaffoldLedgerSaveTicks <= 0) this.flushOwnedScaffoldLedger();
   }

   private void flushOwnedScaffoldLedger() {
      if (!this.scaffoldLedgerDirty) return;
      this.scaffoldLedgerDirty = false;
      this.scaffoldLedgerSaveTicks = 0;
      // Only the ledger file is written, atomically and off the client thread.
      TerraLeveler.SCAFFOLD_LEDGERS.saveAsync();
   }

   private static int countRequiredTemporaryScaffoldSteps(Minecraft mc, List<SafeRoutePlanner.RouteStep> steps) {
      int count = 0;

      for (SafeRoutePlanner.RouteStep step : steps) {
         if ((step.type() == SafeRoutePlanner.StepType.BRIDGE || step.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP)
            && !SafeRoutePlanner.hasFloor(mc, step.position())) {
            count++;
         }
      }

      return count;
   }

   private RouteExecutionRules.ScaffoldBudgetAssessment assessScaffoldRouteBudget(
      Minecraft mc, List<SafeRoutePlanner.RouteStep> steps
   ) {
      return this.assessScaffoldRouteBudget(mc, steps, this.jobScaffoldBudget);
   }

   private RouteExecutionRules.ScaffoldBudgetAssessment assessScaffoldRouteBudget(
      Minecraft mc, List<SafeRoutePlanner.RouteStep> steps, int scaffoldBudget
   ) {
      Set<BlockPos> requiredPlacements = requiredTemporaryScaffoldPlacements(mc, steps);
      Set<BlockPos> trackedScaffolds = new LinkedHashSet<>();
      for (BlockPos scaffold : this.scaffoldPositions) {
         if (this.isTrackedScaffoldBlock(mc, scaffold)) {
            trackedScaffolds.add(scaffold.immutable());
         }
      }

      return RouteExecutionRules.assessScaffoldRouteBudget(
         steps,
         requiredPlacements,
         trackedScaffolds,
         this.activeScaffoldCount(),
         scaffoldBudget,
         this.rollingScaffoldOverflowTarget
      );
   }

   private int minimumExecutableScaffoldBudget(Minecraft mc, List<SafeRoutePlanner.RouteStep> steps) {
      if (this.jobScaffoldBudget <= 0) {
         return -1;
      }

      Set<BlockPos> requiredPlacements = requiredTemporaryScaffoldPlacements(mc, steps);
      Set<BlockPos> trackedScaffolds = new LinkedHashSet<>();
      for (BlockPos scaffold : this.scaffoldPositions) {
         if (this.isTrackedScaffoldBlock(mc, scaffold)) {
            trackedScaffolds.add(scaffold.immutable());
         }
      }

      int minimumBudget = Math.max(this.jobScaffoldBudget, this.activeScaffoldCount());
      return RouteExecutionRules.minimumExecutableScaffoldBudget(
         steps,
         requiredPlacements,
         trackedScaffolds,
         this.activeScaffoldCount(),
         minimumBudget,
         this.maximumTemporaryExcavationRouteScaffoldBudget(),
         this.rollingScaffoldOverflowTarget
      );
   }

   /**
    * Route kinds that may borrow the bounded temporary scaffold budget. Fluid work joined
    * excavation in 0.8.74: with the budget full, a lava pocket opened mid-mountain could
    * not be reached over a bridge and the job paused until it gave up (0.8.73 test run).
    */
   static boolean mayBorrowRouteScaffoldBudget(ConstructionController.State returnState) {
      return returnState == ConstructionController.State.EXCAVATING || returnState == ConstructionController.State.CONTAINING_FLUID;
   }

   private int maximumTemporaryExcavationRouteScaffoldBudget() {
      long maximum = (long)this.jobScaffoldBudget + MAX_TEMPORARY_EXCAVATION_ROUTE_SCAFFOLD_DEBT + this.strandedEmergencyScaffoldDebt;
      return maximum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)maximum;
   }

   private static Set<BlockPos> requiredTemporaryScaffoldPlacements(
      Minecraft mc, List<SafeRoutePlanner.RouteStep> steps
   ) {
      Set<BlockPos> requiredPlacements = new LinkedHashSet<>();
      for (SafeRoutePlanner.RouteStep step : steps) {
         if ((step.type() == SafeRoutePlanner.StepType.BRIDGE || step.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP)
            && !SafeRoutePlanner.hasFloor(mc, step.position())) {
            requiredPlacements.add(step.position().below().immutable());
         }
      }
      return requiredPlacements;
   }

   private boolean routeHasPendingScaffoldSteps() {
      for (int index = Math.max(0, this.routeIndex); index < this.route.size(); index++) {
         SafeRoutePlanner.StepType type = this.route.get(index).type();
         if (type == SafeRoutePlanner.StepType.BRIDGE || type == SafeRoutePlanner.StepType.SCAFFOLD_UP) {
            return true;
         }
      }

      return false;
   }

   private void tickPositioning(Minecraft mc, TerraLevelerConfig config) {
      if (this.routeIndex >= this.route.size()) {
         if (this.routeNeedsContinuation && !this.routeTaskNoLongerNeedsTravel(mc, config)) {
            this.continuePartialRoute(mc, config);
         } else {
            if (this.routeRequiresTaskValidation && !this.routeTaskNoLongerNeedsTravel(mc, config)) {
               this.rejectUnusableRouteGoal(mc, "路线结束后仍不能执行当前任务");
            } else {
               this.finishRoute(mc);
            }
         }
      } else if (this.bridgePlacementTarget != null) {
         this.tickPendingBridgePlacement(mc, config);
      } else {
         boolean combatReturnRoute = this.returningFromCombat && this.combatReturnAnchor != null && this.combatReturnAnchor.equals(this.routeTaskKey);
         boolean batchCruise = !this.routeIsMaterialPickupDetour
            && !combatReturnRoute
            && this.routeReturnState == ConstructionController.State.EXCAVATING
            && batchMiningEnabled(config);
         SafeRoutePlanner.RouteStep step = this.route.get(this.routeIndex);
         BlockPos waypoint = step.position();
         Optional<BlockPos> anchor = PlayerPhysicalState.routeAnchor(mc);
         boolean pendingScaffoldSteps = this.routeHasPendingScaffoldSteps();
         boolean taskReadyOrResolved = !this.routeIsMaterialPickupDetour
            && !this.routeIsFlattenCleanupReturn
            && this.routeTaskNoLongerNeedsTravel(mc, config, true);
         boolean taskResolved = pendingScaffoldSteps
            && !this.routeIsMaterialPickupDetour
            && !this.routeIsFlattenCleanupReturn
            && this.routeTaskNoLongerNeedsTravel(mc, config, false);
         boolean taskNoLongerNeedsTravel = RouteExecutionRules.shouldFinishRouteEarly(taskResolved, taskReadyOrResolved, pendingScaffoldSteps);
         RouteExecutionRules.CompletionDecision completionDecision = RouteExecutionRules.completionDecision(
            taskNoLongerNeedsTravel, anchor.isPresent(), batchCruise
         );
         if (completionDecision == RouteExecutionRules.CompletionDecision.FINISH) {
            this.finishRoute(mc);
         } else {
            if (completionDecision == RouteExecutionRules.CompletionDecision.DEFER_UNTIL_STABLE) {
               if (this.routeCompletionDeferredTicks == 0 || this.routeCompletionDeferredTicks % 10 == 0) {
                  TerraLevelerDiagnostics.log(
                     "ROUTE",
                     String.format(
                        "completion-deferred step=%s origin=%s target=%s player=%.3f,%.3f,%.3f velocity=%.3f,%.3f,%.3f ticks=%d",
                        step.type(),
                        posText(step.origin()),
                        waypoint.toShortString(),
                        mc.player.getX(),
                        mc.player.getY(),
                        mc.player.getZ(),
                        mc.player.getDeltaMovement().x,
                        mc.player.getDeltaMovement().y,
                        mc.player.getDeltaMovement().z,
                        this.routeCompletionDeferredTicks
                     )
                  );
               }

               this.routeCompletionDeferredTicks++;
            } else {
               this.routeCompletionDeferredTicks = 0;
            }

            // On decaying leaves there is no anchor; use the same cell the route was planned from.
            BlockPos playerCell = anchor.or(() -> PlayerPhysicalState.routeStart(mc)).orElse(mc.player.blockPosition());
            boolean waterBucketStep = step.type() == SafeRoutePlanner.StepType.WATER_BUCKET_FALL;
            boolean routeStepTransactionComplete = WaterBucketRecoveryRules.mayAdvanceRouteStep(
               waterBucketStep, this.waterBucketFallLanding != null
            );
            if (routeStepTransactionComplete && anchor.filter(waypoint::equals).isPresent()) {
               if (SafeRoutePlanner.canOccupy(mc, waypoint) && SafeRoutePlanner.hasFloor(mc, waypoint)) {
                  double horizontal = horizontalDistance(mc, waypoint);
                  double vertical = Math.abs(waypoint.getY() - mc.player.getY());
                  if (AutoNavigator.reachedWaypoint(horizontal, vertical)) {
                     if (!this.recordFlattenCleanupOutboundStep(mc, step)) {
                        return;
                     }

                     this.routeIndex++;
                     this.routeNoProgressTicks = 0;
                     this.lastRouteDistance = Double.POSITIVE_INFINITY;
                     if (this.routeIndex >= this.route.size()) {
                        if (this.routeNeedsContinuation && !this.routeTaskNoLongerNeedsTravel(mc, config)) {
                           this.continuePartialRoute(mc, config);
                           return;
                        }

                        if (this.routeRequiresTaskValidation && !this.routeTaskNoLongerNeedsTravel(mc, config)) {
                           this.rejectUnusableRouteGoal(mc, "最终站位不能执行当前任务");
                        } else {
                           this.finishRoute(mc);
                        }
                     }
                  } else if (!this.handleBlockingAnimal(mc, config, waypoint, "route")) {
                     if (this.recordRouteProgress(mc, horizontal + vertical * 0.35)) {
                        if (batchCruise && this.stablePlayerStand(mc)) {
                           this.tickBatchMiningBubble(mc, config, true, Integer.MIN_VALUE);
                        }

                        if (batchCruise) {
                           AutoNavigator.moveTowardPreservingView(mc, waypoint, false);
                        } else {
                           AutoNavigator.moveToward(mc, waypoint, false);
                        }

                        this.detail = "在安全路线节点内居中 " + waypoint.toShortString();
                     }
                  }
               } else {
                  this.invalidateRoute(mc, "到达前地面或空间已经变化");
               }
            } else if (!playerOnStepCorridor(playerCell, step)) {
               if (!this.stablePlayerStand(mc)) {
                  this.beginFallRecovery(mc, this.routeReturnState, "真实玩家偏离路线并离开稳定地面");
               } else {
                  TerraLevelerDiagnostics.log(
                     "ROUTE",
                     String.format(
                        "off-corridor step=%d/%d type=%s origin=%s position=%s playerCell=%s anchor=%s blockPos=%s player=%.3f,%.3f,%.3f support=%s",
                        this.routeIndex,
                        this.route.size(),
                        step.type(),
                        posText(step.origin()),
                        waypoint.toShortString(),
                        playerCell.toShortString(),
                        posText(anchor.orElse(null)),
                        mc.player.blockPosition().toShortString(),
                        mc.player.getX(),
                        mc.player.getY(),
                        mc.player.getZ(),
                        PlayerPhysicalState.supportingSurfaces(mc)
                     )
                  );
                  this.invalidateRoute(mc, "真实玩家被击退或偏离了记录的路线边");
               }
            } else if (step.type() == SafeRoutePlanner.StepType.WATER_BUCKET_FALL) {
               this.tickWaterBucketFall(mc, step);
            } else {
               SafeRoutePlanner.StepValidation validation = SafeRoutePlanner.validateStep(mc, step, this.workArea(), this.routePolicy);
               if (validation.requiresBridgePlacement()) {
                  BlockPos bridgeBlock = waypoint.below();
                  Item scaffold = this.preferredScaffoldItem(mc);
                  if (!validScaffold(scaffold)) {
                     this.pause(mc, "施工收集物 ID 中没有可用的完整非重力脚手方块");
                  } else {
                     if (this.activeScaffoldCount() >= this.jobScaffoldBudget && this.activeScaffoldCount() > 0) {
                        this.pruneLoadedStaleScaffolds(mc, "bridge-budget-preflight");
                     }

                     int scaffoldLimit = this.currentScaffoldLimit();
                     if (mayBorrowRouteScaffoldBudget(this.routeReturnState)
                        && !RouteExecutionRules.mayPlaceScaffold(this.activeScaffoldCount(), scaffoldLimit)
                        && !this.hasRollingScaffoldCapacity(mc, bridgeBlock, scaffoldLimit)) {
                        int remainingPlacements = countRequiredTemporaryScaffoldSteps(
                           mc, this.route.subList(this.routeIndex, this.route.size())
                        );
                        int reservedLimit = RouteExecutionRules.boundedTemporaryRouteLimit(
                           this.activeScaffoldCount(),
                           remainingPlacements,
                           scaffoldLimit,
                           this.maximumTemporaryExcavationRouteScaffoldBudget()
                        );
                        if (reservedLimit > scaffoldLimit) {
                           this.routeReservedScaffoldBudget = reservedLimit;
                           scaffoldLimit = reservedLimit;
                           TerraLevelerDiagnostics.log(
                              "SCAFFOLD",
                              "runtime-route-budget-reserved task=" + posText(this.routeTaskKey)
                                 + " routeBudget=" + reservedLimit
                                 + " active=" + this.activeScaffoldCount()
                                 + " remainingPlacements=" + remainingPlacements
                           );
                        }
                     }
                     if (!RouteExecutionRules.mayPlaceScaffold(this.activeScaffoldCount(), scaffoldLimit)) {
                        BlockPos oldSupport = this.ownedSupportingScaffold(mc, bridgeBlock);
                        boolean retirementPending = this.rollingScaffoldReplacementTarget != null;
                         boolean regularRolling = RouteExecutionRules.mayUseRollingScaffoldReserve(
                            this.activeScaffoldCount(), scaffoldLimit, oldSupport != null, retirementPending
                         );
                         boolean bootstrapOverflow = RouteExecutionRules.mayBootstrapRollingScaffoldOverflow(
                            this.activeScaffoldCount(),
                            scaffoldLimit,
                           oldSupport == null && this.stablePlayerStand(mc),
                           this.rollingScaffoldOverflowTarget != null,
                           retirementPending
                        );
                         boolean advanceOverflow = RouteExecutionRules.mayAdvanceRollingScaffoldOverflow(
                            this.activeScaffoldCount(),
                            scaffoldLimit,
                           oldSupport != null && oldSupport.equals(this.rollingScaffoldOverflowTarget),
                           this.rollingScaffoldOverflowTarget != null,
                           retirementPending
                        );
                         boolean committedCleanupRoundTrip = this.routeReturnState == ConstructionController.State.CLEANING_SCAFFOLDS
                            && this.hasFlattenCleanupTransaction()
                            && !this.routeIsFlattenCleanupReturn;
                         boolean rollingSwapAvailable = RouteExecutionRules.allowsRollingRetirement(committedCleanupRoundTrip)
                            && (regularRolling || bootstrapOverflow || advanceOverflow);
                        BlockPos cleanupTaskKey = this.routeTaskKey;
                        boolean futureDependsOnOldSupport = oldSupport != null
                           && RouteExecutionRules.futureRequiredPlacementDependsOnSupport(
                              this.route,
                              this.routeIndex,
                              oldSupport,
                              requiredTemporaryScaffoldPlacements(mc, this.route)
                           );
                        if (futureDependsOnOldSupport
                           && this.routeReturnState == ConstructionController.State.EXCAVATING
                           && cleanupTaskKey != null
                           && this.beginReachableRouteScaffoldCleanup(mc, cleanupTaskKey, playerCell)) {
                           TerraLevelerDiagnostics.log(
                              "SCAFFOLD",
                              "rolling-retire-dependency-cleanup task="
                                 + cleanupTaskKey.toShortString()
                                 + " protected="
                                 + oldSupport.toShortString()
                                 + " waypoint="
                                 + waypoint.toShortString()
                                 + " active="
                                 + this.activeScaffoldCount()
                                 + "/"
                                 + this.jobScaffoldBudget
                           );
                           return;
                        }

                        if (futureDependsOnOldSupport) {
                           TerraLevelerDiagnostics.log(
                              "SCAFFOLD",
                              "rolling-retire-dependency-blocked task="
                                 + posText(cleanupTaskKey)
                                 + " protected="
                                 + oldSupport.toShortString()
                                 + " waypoint="
                                 + waypoint.toShortString()
                                 + " active="
                                 + this.activeScaffoldCount()
                                 + "/"
                                 + this.jobScaffoldBudget
                           );
                           this.invalidateRoute(
                              mc,
                              "滚动脚手旧支撑仍被后续路线依赖；保留支撑并拒绝当前路线 " + oldSupport.toShortString()
                           );
                           return;
                        }

                        if (RouteExecutionRules.shouldCleanScaffoldBeforeBridge(rollingSwapAvailable)
                           && this.routeReturnState == ConstructionController.State.EXCAVATING
                           && cleanupTaskKey != null
                           && this.beginReachableRouteScaffoldCleanup(mc, cleanupTaskKey, playerCell)) {
                           TerraLevelerDiagnostics.log(
                              "SCAFFOLD",
                              "budget-cleanup-before-bridge task="
                                 + cleanupTaskKey.toShortString()
                                 + " waypoint="
                                 + waypoint.toShortString()
                                 + " active="
                                 + this.activeScaffoldCount()
                                 + "/"
                                 + this.jobScaffoldBudget
                           );
                           return;
                        }

                        if (!rollingSwapAvailable) {
                           this.pause(mc, "临时脚手达到当前活动上限 " + scaffoldLimit + "，且没有可安全滚动替换的自有承重脚手；不会改用沙子填洞");
                           return;
                        }

                        BlockState retirementState = oldSupport != null
                           ? mc.level.getBlockState(oldSupport)
                           : ((BlockItem)scaffold).getBlock().defaultBlockState();
                         if (oldSupport != null
                            && !ServerActionExecutor.hasSafeEffectiveTool(mc, retirementState, config)) {
                            if (this.beginToolBoxSwap(
                               mc,
                               ConstructionController.State.POSITIONING,
                               ConstructionController.BreakPurpose.NONE,
                               oldSupport,
                               config
                            )) {
                               TerraLevelerDiagnostics.log(
                                  "TOOLBOX",
                                  "rolling-retirement-tool-request target=" + oldSupport.toShortString()
                               );
                               return;
                            }
                           this.pause(mc, "滚动脚手启动缺少高于保留耐久的有效工具；不会借出临时预算");
                           return;
                        }

                        this.rollingScaffoldRetireTarget = oldSupport == null ? null : oldSupport.immutable();
                        this.rollingScaffoldReplacementTarget = bridgeBlock.immutable();
                        this.rollingScaffoldReplacementConfirmed = false;
                        this.rollingScaffoldDetachedConfirmationTicks = 0;
                        TerraLevelerDiagnostics.log(
                           "SCAFFOLD",
                           (
                                 bootstrapOverflow
                                    ? "rolling-overflow-planned old=natural-support replacement="
                                    : "rolling-reserve-planned old=" + oldSupport.toShortString() + " replacement="
                              )
                              + bridgeBlock.toShortString()
                              + " active="
                              + this.activeScaffoldCount()
                              + "/"
                              + this.jobScaffoldBudget
                        );
                     }

                     this.bridgePlacementTarget = bridgeBlock.immutable();
                     this.bridgePlacementItem = scaffold;
                     this.bridgeRecenterTicks = 0;
                     TerraLevelerDiagnostics.log(
                        "ROUTE",
                        "scaffold-place-request type="
                           + step.type()
                           + " origin="
                           + posText(step.origin())
                           + " waypoint="
                           + waypoint.toShortString()
                           + " block="
                           + this.bridgePlacementTarget.toShortString()
                           + " step="
                           + (this.routeIndex + 1)
                           + "/"
                           + this.route.size()
                     );
                     this.tickPendingBridgePlacement(mc, config);
                  }
               } else if (!validation.canMoveNow()) {
                  BlockPos scaffold = waypoint.below();
                  TerraLevelerDiagnostics.log(
                     "ROUTE",
                     "step-invalid status="
                        + validation.status()
                        + " type="
                        + step.type()
                        + " origin="
                        + posText(step.origin())
                        + " waypoint="
                        + waypoint.toShortString()
                        + " scaffold="
                        + scaffold.toShortString()
                        + " scaffoldState="
                        + mc.level.getBlockState(scaffold)
                        + " belowState="
                        + mc.level.getBlockState(scaffold.below())
                        + " supportNeighbors="
                        + routePlacementSupportSnapshot(mc, scaffold)
                        + " protectedDependencies="
                        + RouteExecutionRules.protectedMiningSupports(this.route, this.routeIndex).size()
                        + " batchPending="
                        + this.batchBreaks.pendingCount()
                  );
                  this.invalidateRoute(mc, "路线步骤复验失败：" + validation.status());
               } else {
                  double horizontal = horizontalDistance(mc, waypoint);
                  double vertical = Math.abs(waypoint.getY() - mc.player.getY());
                  if (!this.handleBlockingAnimal(mc, config, waypoint, "route")) {
                     if (this.recordRouteProgress(mc, horizontal + vertical * 0.35)) {
                        if (batchCruise && this.stablePlayerStand(mc)) {
                           this.tickBatchMiningBubble(mc, config, true, Integer.MIN_VALUE);
                        }

                        if (step.type() != SafeRoutePlanner.StepType.STEP_UP && step.type() != SafeRoutePlanner.StepType.SCAFFOLD_UP) {
                           if (step.type() == SafeRoutePlanner.StepType.STEP_DOWN) {
                              if (batchCruise) {
                                 AutoNavigator.descendTowardPreservingView(mc, step.origin(), waypoint);
                              } else {
                                 AutoNavigator.descendToward(mc, step.origin(), waypoint);
                              }
                           } else if (step.type() == SafeRoutePlanner.StepType.WALK) {
                              if (batchCruise) {
                                 AutoNavigator.sprintTowardPreservingView(mc, waypoint);
                              } else {
                                 AutoNavigator.sprintToward(mc, waypoint);
                              }
                           } else if (batchCruise) {
                              AutoNavigator.moveTowardPreservingView(mc, waypoint, false);
                           } else {
                              AutoNavigator.moveToward(mc, waypoint, false);
                           }
                        } else if (batchCruise) {
                           AutoNavigator.climbTowardPreservingView(mc, step.origin(), waypoint);
                        } else {
                           AutoNavigator.climbToward(mc, step.origin(), waypoint);
                        }

                        this.detail = "沿逐刻复验的安全路线前往 " + waypoint.toShortString() + "（" + (this.routeIndex + 1) + "/" + this.route.size() + "）";
                     }
                  }
               }
            }
         }
      }
   }

   private void tickPendingBridgePlacement(Minecraft mc, TerraLevelerConfig config) {
      Item scaffold = this.bridgePlacementItem == null ? this.preferredScaffoldItem(mc) : this.bridgePlacementItem;
      this.bridgePlacementItem = scaffold;
      if (!validScaffold(scaffold)) {
         this.pause(mc, "施工收集物 ID 中没有可用的完整非重力脚手方块");
      } else if (this.clearReplaceableScaffoldTarget(mc, config)) {
         return;
      } else if (shouldAcquireBridgeMaterial(this.actions.isIdle()) && !this.ensureHotbarScaffold(mc, scaffold)) {
         if (hasPotentialMaterialBox(mc, scaffold)) {
            if (this.routeReturnState == ConstructionController.State.CLEANING_SCAFFOLDS && this.hasActiveFlattenCleanupExcursion()) {
               TerraLevelerDiagnostics.log(
                  "MATERIAL",
                  "flatten-cleanup-excursion-depleted-resupply-in-place task="
                     + posText(this.routeTaskKey)
                     + " routeCreated="
                     + this.flattenCleanupRouteScaffolds.size()
               );
            }
            ConstructionController.State next = normalizeTaskState(this.routeReturnState);
            int desired = Math.max(1, Math.min(64, this.currentScaffoldLimit() - this.activeScaffoldCount()));
            this.beginMaterialUnpack(mc, next, scaffold, desired);
         } else {
            this.pause(mc, "背包和可取潜影盒中都没有圆石或泥土；沙子会坠落并填满矿洞，不能替代悬空施工栈道");
         }
      } else if (!this.waitForActionCooldown(mc) || !this.actions.isIdle()) {
         ServerActionExecutor.Status status = this.actions
            .tickPlace(mc, this.bridgePlacementTarget, scaffold, null, ServerActionExecutor.PlacementExpectation.SOLID_BLOCK);
         if (this.actions.hasSentPlacementRequest() && scaffold instanceof BlockItem blockItem) {
            this.rememberSubmittedScaffold(this.bridgePlacementTarget, blockItem.getBlock());
         }

         if (status == ServerActionExecutor.Status.RUNNING) {
            this.detail = "搭一格宽边界脚手并等待服务器稳定确认 " + this.bridgePlacementTarget.toShortString();
         } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
            SafeRoutePlanner.RouteStep step = this.routeIndex < this.route.size() ? this.route.get(this.routeIndex) : null;
            BlockPos origin = step == null ? null : step.origin();
            // Decaying leaves are a valid route start (0.9.20); recentring stays on the same leaf.
            boolean safeOrigin = origin != null
               && (SafeRoutePlanner.canOccupy(mc, origin) && SafeRoutePlanner.hasFloor(mc, origin)
                  || SafeRoutePlanner.standableOrigin(SafeRoutePlanner.routeEnvironment(mc), origin));
            if (!RouteExecutionRules.canRecenterForScaffold(step != null, safeOrigin, this.bridgeRecenterTicks, 40)) {
               this.clearRollingScaffoldSwap();
               this.invalidateRoute(mc, "真实玩家挡住临时脚手目标：" + this.actions.blockage());
            } else {
               this.bridgeRecenterTicks++;
               if (this.bridgeRecenterTicks == 1 || this.bridgeRecenterTicks % 10 == 0) {
                  TerraLevelerDiagnostics.log(
                     "ROUTE",
                     String.format(
                        "scaffold-recenter origin=%s block=%s player=%.3f,%.3f,%.3f ticks=%d/%d",
                        origin.toShortString(),
                        this.bridgePlacementTarget.toShortString(),
                        mc.player.getX(),
                        mc.player.getY(),
                        mc.player.getZ(),
                        this.bridgeRecenterTicks,
                        40
                     )
                  );
               }

               AutoNavigator.moveToward(mc, origin, false);
               this.detail = "真实玩家贴近脚手目标，先在安全路线起点内回中 " + origin.toShortString() + "（" + this.bridgeRecenterTicks + "/40）";
            }
         } else if (status == ServerActionExecutor.Status.FAILED) {
            BlockPos failedTarget = this.bridgePlacementTarget;
            String failure = this.actions.failure();
            this.clearRollingScaffoldSwap();
            boolean nothingPlaced = failedTarget != null
               && mc.level.hasChunkAt(failedTarget)
               && mc.level.getBlockState(failedTarget).getCollisionShape(mc.level, failedTarget).isEmpty();
            if (mayReplanAfterRejectedScaffold(nothingPlaced, ++this.consecutiveScaffoldPlacementFailures)) {
               // 0.8.71 paused 30 seconds when the client refused a placement three times
               // (Fail result, nothing sent to the world). Plan around that cell instead.
               this.forgetScaffold(failedTarget);
               this.rejectedScaffoldPlacements.add(failedTarget.immutable());
               this.bridgePlacementTarget = null;
               this.bridgePlacementItem = null;
               this.bridgeRecenterTicks = 0;
               TerraLevelerDiagnostics.log("ROUTE", "scaffold-placement-rejected-replan block=" + failedTarget.toShortString()
                  + " failures=" + this.consecutiveScaffoldPlacementFailures + "/" + MAX_REJECTED_SCAFFOLD_PLACEMENTS
                  + " forbidden=" + this.rejectedScaffoldPlacements.size() + " reason=" + failure);
               this.invalidateRoute(mc, "临时脚手放置未被接受，避开该格重新规划路线：" + failure);
            } else {
               this.pause(mc, this.actionFailure("临时脚手放置失败"));
            }
         } else if (status != ServerActionExecutor.Status.SUCCESS) {
            this.clearRollingScaffoldSwap();
            this.pause(mc, "临时脚手放置返回异常状态 " + status);
         } else {
            BlockPos confirmed = this.bridgePlacementTarget;
            this.bridgePlacementTarget = null;
            this.bridgePlacementItem = null;
            this.bridgeRecenterTicks = 0;
            this.consecutiveScaffoldPlacementFailures = 0;
            Block confirmedBlock = this.confirmSubmittedScaffoldPlacement(mc, confirmed);
            if (this.routeReturnState == ConstructionController.State.CLEANING_SCAFFOLDS
               && this.wallIndex >= this.wallTargets.size()
               && !this.routeIsFlattenCleanupReturn
               && RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass)) {
               if (this.flattenCleanupRouteScaffolds.isEmpty()) {
                  this.rebaseFlattenCleanupRouteAnchorAtFirstScaffold(confirmed);
               }
               this.flattenCleanupRouteScaffolds.add(confirmed.immutable());
               TerraLevelerDiagnostics.log(
                  "SCAFFOLD",
                  "flatten-cleanup-route-tail-tracked target=" + confirmed.toShortString() + " routeCreated=" + this.flattenCleanupRouteScaffolds.size()
               );
            }

            if (confirmed.equals(this.rollingScaffoldReplacementTarget)) {
               this.rollingScaffoldReplacementConfirmed = true;
               TerraLevelerDiagnostics.log(
                  "SCAFFOLD",
                  "rolling-replacement-confirmed old="
                     + (this.rollingScaffoldRetireTarget == null ? "natural-support" : this.rollingScaffoldRetireTarget.toShortString())
                     + " replacement="
                     + confirmed.toShortString()
                     + " active="
                     + this.activeScaffoldCount()
                     + "/"
                     + this.jobScaffoldBudget
               );
            }

            TerraLevelerDiagnostics.log(
               "ROUTE",
               "scaffold-confirmed block="
                  + confirmed.toShortString()
                  + " material="
                  + confirmedBlock
                  + " placed="
                  + this.scaffoldsPlaced
                  + " active="
                  + this.activeScaffoldCount()
                  + "/"
                  + this.currentScaffoldLimit()
            );
            this.startActionCooldown(config);
         }
      }
   }

   /** A refused scaffold placement that left the cell empty is planned around, up to the failure limit. */
   static boolean mayReplanAfterRejectedScaffold(boolean nothingPlaced, int consecutiveFailures) {
      return nothingPlaced && consecutiveFailures < MAX_REJECTED_SCAFFOLD_PLACEMENTS;
   }

   private Block confirmSubmittedScaffoldPlacement(Minecraft mc, BlockPos confirmed) {
      Block confirmedBlock = mc.level.getBlockState(confirmed).getBlock();
      this.submittedScaffoldBlocks.remove(confirmed);
      if (this.scaffoldPositions.add(confirmed.immutable())) {
         this.scaffoldsPlaced++;
      }
      this.scaffoldBlocks.put(confirmed.immutable(), confirmedBlock);
      this.persistOwnedScaffolds();
      return confirmedBlock;
   }

   private boolean clearReplaceableScaffoldTarget(Minecraft mc, TerraLevelerConfig config) {
      BlockState targetState = mc.level.getBlockState(this.bridgePlacementTarget);
      boolean targetNeedsClear = ScaffoldPlacementRules.shouldClearTarget(
         targetState.isAir(), targetState.getFluidState().isEmpty(), targetState.getCollisionShape(mc.level, this.bridgePlacementTarget).isEmpty()
      );
      if (!ScaffoldPlacementRules.shouldContinueClearance(targetNeedsClear, this.actions.isBreaking(this.bridgePlacementTarget))) {
         return false;
      }

      AutoNavigator.release(mc);
      ServerActionExecutor.Status status = this.actions.tickBreak(mc, this.bridgePlacementTarget, config, false);
      if (status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "先清除脚手目标格内的可替换植物 " + this.bridgePlacementTarget.toShortString();
      } else if (status == ServerActionExecutor.Status.SUCCESS) {
         TerraLevelerDiagnostics.log(
            "ROUTE",
            "scaffold-target-cleared target=" + this.bridgePlacementTarget.toShortString() + " block=" + targetState.getBlock()
         );
      } else {
         this.clearRollingScaffoldSwap();
         this.invalidateRoute(mc, "脚手目标格清障失败，结束当前路线并重新规划：" + this.actions.blockage());
      }
      return true;
   }

   private void tickWaterBucketFall(Minecraft mc, SafeRoutePlanner.RouteStep step) {
      BlockPos landing = step.position();
      BlockPos floor = landing.below();
      if (this.waterBucketFallLanding == null || !this.waterBucketFallLanding.equals(landing)) {
         this.pause(mc, "水桶下降事务与当前路线落点不一致");
         return;
      }
      if (++this.waterBucketFallTicks > 160) {
         this.pause(mc, "水桶下降在 8 秒内没有完成放水、落地和收水确认");
         return;
      }
      if (!mc.level.hasChunkAt(landing)
         || !mc.level.hasChunkAt(floor)
         || mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) {
         this.pause(mc, "水桶下降落点地面已变化或区块未加载 " + landing.toShortString());
         return;
      }

      boolean waterPresent = mc.level.getFluidState(landing).is(FluidTags.WATER);
      if (waterPresent && !this.waterBucketFallWaterConfirmed) {
         this.waterBucketFallWaterConfirmed = true;
         this.waterBucketFallAimTicks = 0;
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "water-bucket-fall-water-confirmed landing=" + landing.toShortString()
               + " playerY=" + mc.player.getY()
         );
      }

      double dryLandingDx = mc.player.getX() - (landing.getX() + .5);
      double dryLandingDz = mc.player.getZ() - (landing.getZ() + .5);
      double landingHorizontalDistanceSquared = dryLandingDx * dryLandingDx + dryLandingDz * dryLandingDz;
      if (!this.waterBucketFallWaterConfirmed
         && !waterPresent
         && WaterBucketRecoveryRules.mayPickupWater(
            mc.player.onGround(), mc.player.getY(), landing.getY(), landingHorizontalDistanceSquared
         )) {
         boolean placementAttempted = this.waterBucketFallPlacementRequested;
         this.completeWaterBucketFallTransaction();
         this.detail = "水桶下降已干落地，继续从实际落点规划";
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "water-bucket-fall-dry-landing landing="
               + landing.toShortString()
               + " player="
               + mc.player.blockPosition().toShortString()
               + " placementAttempted="
               + placementAttempted
         );
         return;
      }

      if (!this.waterBucketFallWaterConfirmed) {
         boolean waterBucketAvailable = inventoryCount(mc, Items.WATER_BUCKET) > 0;
         if (!waterBucketAvailable && !this.waterBucketFallPlacementRequested) {
            this.pause(mc, "高处下降需要一个可换入快捷栏的水桶");
            return;
         }
         int slot = -1;
         if (waterBucketAvailable) {
            if (!this.ensureHotbarMaterial(mc, Items.WATER_BUCKET, 1)) {
               this.pause(mc, "高处下降需要一个可换入快捷栏的水桶");
               return;
            }
            slot = hotbarSlot(mc, Items.WATER_BUCKET);
            if (slot < 0 && !this.waterBucketFallPlacementRequested) {
               this.pause(mc, "水桶已请求换入快捷栏，但客户端库存尚未确认");
               return;
            }
            if (slot >= 0) ServerActionExecutor.selectAndSyncToolSlot(mc, slot);
         }
         AutoNavigator.descendToward(mc, step.origin(), landing);
         AutoNavigator.lookAt(mc, floor);
         this.waterBucketFallAimTicks++;
         double reachSquared = mc.player.getEyePosition().distanceToSqr(
            floor.getX() + .5, floor.getY() + 1.0, floor.getZ() + .5
         );
         double landingDx = mc.player.getX() - (landing.getX() + .5);
         double landingDz = mc.player.getZ() - (landing.getZ() + .5);
         double horizontalDistanceSquared = landingDx * landingDx + landingDz * landingDz;
         if (slot >= 0
            && WaterBucketRecoveryRules.mayPlaceWater(
               mc.player.onGround(),
               mc.player.getY(),
               step.origin().getY(),
               mc.player.getDeltaMovement().y,
               horizontalDistanceSquared,
               reachSquared,
               WorkPoseRules.SAFE_REACH_SQUARED,
               this.waterBucketFallAimTicks
            )) {
            var result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            boolean accepted = WaterBucketRecoveryRules.placementRequestAccepted(result);
            if (!this.waterBucketFallPlacementRequested || this.waterBucketFallTicks % 4 == 0) {
               TerraLevelerDiagnostics.log(
                  "RECOVERY",
                  "water-bucket-fall-place-request landing="
                     + landing.toShortString()
                     + " floor="
                     + floor.toShortString()
                     + " reachSquared="
                     + reachSquared
                      + " playerY="
                      + mc.player.getY()
                      + " horizontalDistanceSquared="
                      + horizontalDistanceSquared
                      + " verticalVelocity="
                      + mc.player.getDeltaMovement().y
                      + " result="
                      + result
                      + " accepted="
                      + accepted
               );
            }
            if (accepted) this.waterBucketFallPlacementRequested = true;
         }
         this.detail = this.waterBucketFallPlacementRequested
            ? "水桶下降中，等待服务器确认水源 " + landing.toShortString()
            : "水桶下降中，瞄准落点 " + landing.toShortString();
         return;
      }

      AutoNavigator.descendToward(mc, step.origin(), landing);
      double pickupDx = mc.player.getX() - (landing.getX() + .5);
      double pickupDz = mc.player.getZ() - (landing.getZ() + .5);
      double pickupHorizontalDistanceSquared = pickupDx * pickupDx + pickupDz * pickupDz;
      if (!WaterBucketRecoveryRules.mayPickupWater(
         mc.player.onGround(), mc.player.getY(), landing.getY(), pickupHorizontalDistanceSquared
      )) {
         this.detail = "水源已确认，等待玩家在落点稳定落地 " + landing.toShortString();
         return;
      }

      AutoNavigator.release(mc);
      int returnedWaterBuckets = inventoryCount(mc, Items.WATER_BUCKET);
      if (WaterBucketRecoveryRules.pickupConfirmed(waterPresent, returnedWaterBuckets)) {
         this.completeWaterBucketFallTransaction();
         this.detail = "水桶下降完成，等待玩家在落点稳定站立";
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "water-bucket-fall-complete landing=" + landing.toShortString()
               + " player=" + mc.player.blockPosition().toShortString()
               + " waterBuckets=" + returnedWaterBuckets
         );
         return;
      }

      if (!waterPresent) {
         this.detail = "水源已消失，等待服务器确认水桶回到背包 " + landing.toShortString();
         return;
      }

      if (inventoryCount(mc, Items.BUCKET) <= 0) {
         this.detail = "已安全落水，等待服务器确认空桶后收回水源 " + landing.toShortString();
         return;
      }
      if (!this.ensureHotbarMaterial(mc, Items.BUCKET, 1)) return;
      int bucketSlot = hotbarSlot(mc, Items.BUCKET);
      if (bucketSlot < 0) return;
      ServerActionExecutor.selectAndSyncToolSlot(mc, bucketSlot);
      AutoNavigator.lookAt(mc, landing);
      if (++this.waterBucketFallAimTicks >= 3) {
         var result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
         this.waterBucketFallAimTicks = 0;
         this.waterBucketFallPickupAttempts++;
         TerraLevelerDiagnostics.log(
            "RECOVERY",
            "water-bucket-fall-pickup-request landing="
               + landing.toShortString()
               + " attempt="
               + this.waterBucketFallPickupAttempts
               + " result="
               + result
         );
      }
      this.detail = "已安全落水，正在收回水桶 " + landing.toShortString();
   }

   private void completeWaterBucketFallTransaction() {
      this.waterBucketFallLanding = null;
      this.waterBucketFallTicks = 0;
      this.waterBucketFallAimTicks = 0;
      this.waterBucketFallPickupAttempts = 0;
      this.waterBucketFallPlacementRequested = false;
      this.waterBucketFallWaterConfirmed = false;
   }

   private boolean hasActiveWaterBucketFall() {
      return this.state == ConstructionController.State.POSITIONING
         && this.waterBucketFallLanding != null
         && this.routeIndex >= 0
         && this.routeIndex < this.route.size()
         && this.route.get(this.routeIndex).type() == SafeRoutePlanner.StepType.WATER_BUCKET_FALL;
   }

   static boolean shouldBeginGeneralWaterRecovery(boolean playerInWater, boolean activeWaterBucketFall) {
      return playerInWater && !activeWaterBucketFall;
   }

   private static int hotbarSlot(Minecraft mc, Item item) {
      if (mc == null || mc.player == null || item == null) return -1;
      for (int slot = 0; slot < Math.min(9, mc.player.getInventory().getContainerSize()); slot++) {
         if (mc.player.getInventory().getItem(slot).is(item)) return slot;
      }
      return -1;
   }

   static boolean shouldAcquireBridgeMaterial(boolean actionIdle) {
      return actionIdle;
   }

   private boolean beginFinalScaffoldPillarCleanup(Minecraft mc, BlockPos target, BlockPos start, String reason) {
      boolean finalCleanup = this.wallIndex >= this.wallTargets.size()
         && RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass);
      if (!finalCleanup
         || target == null
         || start == null
         || !this.stablePlayerStand(mc)
         || this.activeScaffoldCount() != 1
         || this.hasFlattenCleanupTransaction()
         || this.finalScaffoldPillarTarget != null) {
         return false;
      }

      return this.beginScaffoldPillarTransaction(mc, target, start, reason, false);
   }

   /**
    * Final cleanup of a scaffold hanging well above the player (0.8.73): instead of
    * walking around to high ground and building a bridge out and back (0.8.71: 123
    * scaffolds took 14 minutes and about 200 extra placements), build a temporary
    * pillar from the floor next to it, remove every owned scaffold reachable from the
    * top, then take the pillar down block by block. Falls back to the normal cleanup
    * route when no pillar column, base route, budget or material is available.
    */
   private boolean beginClusterPillarCleanup(Minecraft mc, BlockPos target) {
      if (this.wallIndex < this.wallTargets.size()
         || !RouteExecutionRules.allowsFlattenFinalCleanupBridge(this.flattenFinalCleanupPass)
         || this.hasFlattenCleanupTransaction()
         || this.finalScaffoldPillarTarget != null
         || !this.stablePlayerStand(mc)) {
         return false;
      }
      BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (!mayUseClusterPillar(start, target)) return false;
      java.util.function.Predicate<BlockPos> eligible = pos -> !this.clusterPillarRejected.contains(pos)
         && !this.parkedCleanupTargets.containsKey(pos)
         && !this.leftBehindScaffolds.contains(pos)
         && mayUseClusterPillar(start, pos)
         && ObsoleteScaffoldRules.isTopOfTrackedColumn(this.scaffoldPositions, pos)
         && mc.level.hasChunkAt(pos)
         && this.isTrackedScaffoldBlock(mc, pos);
      // Nearest high scaffold first: 0.8.74 walked 188 steps from one end of the strip to
      // the other because the cleanup order puts the highest scaffold first.
      List<BlockPos> candidates = new ArrayList<>(this.scaffoldPositions.stream()
         .filter(pos -> !pos.equals(target))
         .filter(eligible)
         .sorted(Comparator.comparingInt((BlockPos pos) -> Math.abs(pos.getX() - start.getX()) + Math.abs(pos.getZ() - start.getZ())))
         .limit(CLUSTER_PILLAR_NEAREST_CANDIDATES)
         .toList());
      if (!this.clusterPillarRejected.contains(target)) candidates.add(target);
      candidates.sort(Comparator.comparingInt((BlockPos pos) -> Math.abs(pos.getX() - start.getX()) + Math.abs(pos.getZ() - start.getZ())));
      for (BlockPos candidate : candidates) {
         // Raise the pillar to the highest scaffold of the little group around the candidate;
         // the lower ones are taken down on the way (tickPillarSideCleanup).
         BlockPos top = CleanupParkingRules.highestInGroup(candidate, this.scaffoldPositions, eligible);
         for (BlockPos attempt : top.equals(candidate) ? List.of(candidate) : List.of(top, candidate)) {
            if (this.clusterPillarRejected.contains(attempt)) continue;
            String trigger = attempt.equals(target) ? "high-cluster" : attempt.equals(candidate) ? "high-cluster-nearest" : "high-cluster-group-top";
            if (this.beginScaffoldPillarTransaction(mc, attempt, start, trigger, false, true)) {
               return true;
            }
            this.clusterPillarRejected.add(attempt.immutable());
         }
      }
      return this.prioritizePillarColumnBlockers(mc, target);
   }

   /**
    * The pillar for the current target found no reachable base: owned scaffolds standing in
    * its candidate columns are cleaned first. In 0.8.75 a two-block stub left by an earlier
    * failed pillar kept the only column inside the area occupied for the whole cleanup.
    */
   private boolean prioritizePillarColumnBlockers(Minecraft mc, BlockPos target) {
      int index = this.scaffoldCleanupIndex;
      if (index >= this.scaffoldCleanupTargets.size()
         || !target.equals(this.scaffoldCleanupTargets.get(index))
         || !this.pillarBlockersPrioritized.add(target.immutable())) {
         return false;
      }
      WorkArea area = this.workArea();
      List<BlockPos> remaining = this.scaffoldCleanupTargets.subList(index, this.scaffoldCleanupTargets.size());
      List<BlockPos> blockers = CleanupParkingRules.pillarColumnBlockers(
         target, this.scaffoldPositions, this.flattenPlan == null ? Integer.MIN_VALUE : this.flattenPlan.targetGroundY() + 1, area::containsColumn
      ).stream()
         .filter(pos -> !this.parkedCleanupTargets.containsKey(pos) && !this.leftBehindScaffolds.contains(pos))
         .filter(remaining::contains)
         .toList();
      if (blockers.isEmpty()) return false;
      List<BlockPos> reordered = new ArrayList<>(this.scaffoldCleanupTargets.subList(0, index));
      reordered.addAll(blockers);
      for (BlockPos pos : remaining) {
         if (!blockers.contains(pos)) reordered.add(pos);
      }
      this.scaffoldCleanupTargets = List.copyOf(reordered);
      this.clusterPillarRejected.remove(target);
      this.routeRetryCooldownTicks = 1;
      this.detail = "先拆挡住临时柱位置的低处脚手，再处理 " + target.toShortString();
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "pillar-column-blockers-first target=" + target.toShortString()
            + " blockers=" + blockers.stream().map(BlockPos::toShortString).toList()
      );
      return true;
   }

   static boolean mayUseClusterPillar(BlockPos start, BlockPos target) {
      return start != null && target != null && target.getY() - start.getY() >= CLUSTER_PILLAR_MIN_RISE;
   }

   private boolean beginHighExcavationPillarAccess(
      Minecraft mc,
      TerraLevelerConfig config,
      BlockPos target,
      BlockPos start,
      SafeRoutePlanner.PlanStatus failureStatus
   ) {
      if ((failureStatus != SafeRoutePlanner.PlanStatus.NO_SAFE_GOAL && failureStatus != SafeRoutePlanner.PlanStatus.NO_ROUTE)
         || target == null
         || start == null
         || !this.stablePlayerStand(mc)
         || this.finalScaffoldPillarTarget != null
         || this.hasFlattenCleanupTransaction()
         || this.excavationIndex >= this.excavationTargets.size()
         || !target.equals(this.excavationTargets.get(this.excavationIndex))
         || ExcavationPlanner.operationFor(this.classifyExcavation(mc, target)) != ExcavationPlanner.Operation.BREAK
         || mc.level.getBlockState(target).is(BlockTags.LEAVES)
         || config.flattenProtectBlockEntities && mc.level.getBlockEntity(target) != null) {
         return false;
      }

      return this.beginScaffoldPillarTransaction(mc, target, start, failureStatus.name(), true);
   }

   private boolean beginScaffoldPillarTransaction(
      Minecraft mc, BlockPos target, BlockPos start, String reason, boolean excavation
   ) {
      return this.beginScaffoldPillarTransaction(mc, target, start, reason, excavation, false);
   }

   private boolean beginScaffoldPillarTransaction(
      Minecraft mc, BlockPos target, BlockPos start, String reason, boolean excavation, boolean cluster
   ) {
      return this.beginScaffoldPillarTransaction(mc, target, start, reason, excavation, cluster, null);
   }

   private boolean beginScaffoldPillarTransaction(
      Minecraft mc, BlockPos target, BlockPos start, String reason, boolean excavation, boolean cluster,
      ConstructionController.State clusterReturnState
   ) {
      ConstructionController.State returnState = excavation
         ? ConstructionController.State.EXCAVATING
         : cluster && clusterReturnState != null
         ? clusterReturnState
         : ConstructionController.State.CLEANING_SCAFFOLDS;
      String mode = excavation ? "excavation" : cluster ? "cluster-cleanup" : "final-cleanup";

      FinalScaffoldPillarPlanner.Result geometry = excavation || cluster
         ? FinalScaffoldPillarPlanner.planAccess(
            target,
            start,
            Integer.MAX_VALUE,
            MAX_FINAL_SCAFFOLD_PILLAR_HEIGHT,
            mc.level.getMinY(),
            position -> this.scaffoldPillarCell(mc, position)
         )
         : FinalScaffoldPillarPlanner.plan(
            target,
            start,
            true,
            this.activeScaffoldCount(),
            Integer.MAX_VALUE,
            MAX_FINAL_SCAFFOLD_PILLAR_HEIGHT,
            mc.level.getMinY(),
            position -> this.scaffoldPillarCell(mc, position)
         );
      WorkArea area = this.workArea();
      SafeRoutePlanner.RoutePolicy policy = SafeRoutePlanner.RoutePolicy.flatteningFloor();
      List<FinalScaffoldPillarPlanner.Candidate> candidates = List.of();
      SafeRoutePlanner.PlanResult routePlan = null;
      String sideRejection = null;
      if (!geometry.ready()) {
         sideRejection = "pillar-rejected mode=" + mode
            + " target=" + target.toShortString() + " start=" + start.toShortString() + " status=" + geometry.status();
      } else {
         candidates = this.pillarBasesInArea(geometry.candidates(), excavation || cluster);
         if (candidates.isEmpty()) {
            sideRejection = "pillar-rejected mode=" + mode
               + " target=" + target.toShortString() + " reason=no-column-inside-work-area";
         } else {
            List<BlockPos> goals = candidates.stream().map(FinalScaffoldPillarPlanner.Candidate::baseFeet).toList();
            routePlan = SafeRoutePlanner.findRoute(mc, start, goals, area, policy);
         }
      }
      if (excavation && (routePlan == null || !routePlan.hasRoute())) {
         // 0.8.98: the side columns of a high target can all end on old scaffolds halfway up while
         // the column right under the target is open down to the ground.
         List<FinalScaffoldPillarPlanner.Candidate> underTarget = this.pillarBasesInArea(
            FinalScaffoldPillarPlanner.planUnderTarget(
               target, MAX_FINAL_SCAFFOLD_PILLAR_HEIGHT, mc.level.getMinY(), position -> this.scaffoldPillarCell(mc, position)
            ).candidates(),
            true
         );
         if (!underTarget.isEmpty()) {
            SafeRoutePlanner.PlanResult underRoute = SafeRoutePlanner.findRoute(
               mc, start, List.of(underTarget.getFirst().baseFeet()), area, policy
            );
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "pillar-under-target target=" + target.toShortString()
                  + " base=" + underTarget.getFirst().baseFeet().toShortString()
                  + " blocks=" + underTarget.getFirst().requiredBlocks()
                  + " route=" + underRoute.status()
                  + " sides=" + (routePlan == null ? geometry.status() : routePlan.status())
            );
            if (underRoute.hasRoute()) {
               candidates = underTarget;
               routePlan = underRoute;
               sideRejection = null;
            }
         }
      }
      if (sideRejection != null) {
         TerraLevelerDiagnostics.log("SCAFFOLD", sideRejection);
         return false;
      }
      if (!routePlan.hasRoute()) {
         if (excavation && target.getY() - start.getY() > 8 && !this.scaffoldPositions.isEmpty()
            && !this.strandedOnOwnScaffold(mc, start)
            && this.beginBudgetReliefScaffoldCleanup(mc, target, start)) {
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "pillar-base-altitude-recovery target=" + target.toShortString()
                  + " start=" + start.toShortString()
                  + " deltaY=" + (target.getY() - start.getY())
                  + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
            );
            return true;
         }
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "pillar-base-unreachable mode=" + mode
               + " target=" + target.toShortString() + " start=" + start.toShortString() + " status=" + routePlan.status()
         );
         return false;
      }

      BlockPos selectedBase = routePlan.steps().isEmpty() ? start : routePlan.steps().getLast().position();
      FinalScaffoldPillarPlanner.Candidate selected = candidates.stream()
         .filter(candidate -> candidate.baseFeet().equals(selectedBase))
         .findFirst()
         .orElse(null);
      // A cleanup pillar is temporary and fully taken down again, like the reserved
      // route budget of bridge excursions; it may exceed the normal budget by its height.
      // 0.8.90: excavation pillars are taken down completely as well.
      int pillarBudget = cluster || excavation ? this.temporaryPillarBudget() : this.jobScaffoldBudget;
      if (selected == null || this.activeScaffoldCount() + selected.requiredBlocks() > pillarBudget) {
         if (excavation
            && selected != null
            && this.activeScaffoldCount() > 0
            && !this.strandedOnOwnScaffold(mc, start)
            && this.beginBudgetReliefScaffoldCleanup(mc, target, start)) {
            TerraLevelerDiagnostics.log(
               "SCAFFOLD",
               "pillar-budget-relief target=" + target.toShortString() + " required=" + selected.requiredBlocks()
                  + " active=" + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget
            );
            return true;
         }
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "pillar-budget-rejected mode="
               + mode
               + " target="
               + target.toShortString()
               + " required="
               + (selected == null ? -1 : selected.requiredBlocks())
               + " active="
               + this.activeScaffoldCount()
               + "/"
               + this.jobScaffoldBudget
         );
         if (excavation && selected != null) {
            this.pause(mc, "高空挖掘临时柱需要 " + selected.requiredBlocks() + " 个脚手，但当前账本 "
               + this.activeScaffoldCount() + "/" + this.jobScaffoldBudget + "，且没有可安全回收的旧脚手");
            return true;
         }
         return false;
      }

      Item material = this.preferredScaffoldItem(mc, selected.requiredBlocks());
      if (!validScaffold(material) || availableMaterialSupply(mc, material) < selected.requiredBlocks()) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "pillar-material-rejected mode=" + mode
               + " target=" + target.toShortString() + " required=" + selected.requiredBlocks() + " item=" + materialName(material)
         );
         if (excavation) {
            this.pause(mc, "高空挖掘临时柱需要 " + selected.requiredBlocks() + " 块脚手材料，但背包和潜影盒中不足");
            return true;
         }
         return false;
      }
      if (inventoryCount(mc, material) < selected.requiredBlocks()) {
         if (!hasPotentialMaterialBox(mc, material)) return false;
         TerraLevelerDiagnostics.log(
            "MATERIAL",
            "pillar-prefetch mode=" + mode
               + " target=" + target.toShortString() + " required=" + selected.requiredBlocks() + " item=" + materialName(material)
         );
         this.beginMaterialUnpack(mc, returnState, material, selected.requiredBlocks());
         return true;
      }

      this.cancelOwnedWork(mc, excavation ? "excavation-scaffold-pillar-start" : "final-scaffold-pillar-start");
      this.finalScaffoldPillarTarget = target.immutable();
      this.finalScaffoldPillarPlan = selected;
      this.finalScaffoldPillarMaterial = material;
      this.finalScaffoldPillarDescentIndex = -1;
      this.finalScaffoldPillarPhase = ConstructionController.FinalScaffoldPillarPhase.ASCENDING;
      this.finalScaffoldPillarExcavation = excavation;
      this.finalScaffoldPillarCluster = cluster;
      this.finalScaffoldPillarClusterRemoved = 0;
      this.flattenPillarIndex = 0;
      this.flattenPillarStepTicks = 0;
      this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
      this.flattenPillarReturnState = returnState;
      if (!excavation) this.flattenCleanupTopologyBlocks.remove(target);
      this.route = routePlan.steps();
      this.routeIndex = 0;
      this.routeTaskKey = selectedBase.immutable();
      this.routeReturnState = ConstructionController.State.FLATTEN_PILLARING;
      this.routePolicy = policy;
      this.routeRequiresTaskValidation = false;
      this.routeNeedsContinuation = false;
      this.routeNoProgressTicks = 0;
      this.routeCompletionDeferredTicks = 0;
      this.lastRouteDistance = Double.POSITIVE_INFINITY;
      this.state = this.route.isEmpty() ? ConstructionController.State.FLATTEN_PILLARING : ConstructionController.State.POSITIONING;
      this.resumeState = ConstructionController.State.FLATTEN_PILLARING;
      this.detail = excavation
         ? "高空挖掘目标没有安全工作站位；前往柱底后垂直施工并逐格回收临时柱"
         : cluster
         ? "高处悬空脚手：从地面垫临时柱上去，拆掉柱顶够得着的全部脚手后逐格回收临时柱"
         : "最后一个高空脚手无可逆斜向路线；前往柱底后垂直清理并逐格回收临时柱";
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "pillar-start mode="
            + mode
            + " target="
            + target.toShortString()
            + " base="
            + selected.baseFeet().toShortString()
            + " topFeet="
            + selected.topFeet().toShortString()
            + " blocks="
            + selected.requiredBlocks()
            + " routeSteps="
            + routePlan.steps().size()
            + " material="
            + materialName(material)
            + " trigger="
            + reason
      );
      return true;
   }

   /** Pillar bases a job may use: inside the work area, where scaffolds are allowed. */
   private List<FinalScaffoldPillarPlanner.Candidate> pillarBasesInArea(
      List<FinalScaffoldPillarPlanner.Candidate> candidates, boolean restrictToArea
   ) {
      if (!restrictToArea) return candidates;
      WorkArea area = this.workArea();
      return candidates.stream()
         .filter(candidate -> area.containsColumn(candidate.baseFeet().getX(), candidate.baseFeet().getZ()))
         .filter(candidate -> area.permitsScaffoldAt(candidate.baseFeet().getX(), candidate.baseFeet().getZ()))
         .toList();
   }

   private FinalScaffoldPillarPlanner.CellKind scaffoldPillarCell(Minecraft mc, BlockPos position) {
      return switch (this.flattenPillarCell(mc, position)) {
         case OPEN_DRY -> FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
         case SOLID -> FinalScaffoldPillarPlanner.CellKind.SOLID;
         case FLUID -> FinalScaffoldPillarPlanner.CellKind.FLUID;
         case UNLOADED -> FinalScaffoldPillarPlanner.CellKind.UNLOADED;
      };
   }

   private void tickFinalScaffoldPillar(Minecraft mc, TerraLevelerConfig config) {
      FinalScaffoldPillarPlanner.Candidate plan = this.finalScaffoldPillarPlan;
      BlockPos target = this.finalScaffoldPillarTarget;
      if (plan == null || target == null || this.finalScaffoldPillarPhase == ConstructionController.FinalScaffoldPillarPhase.NONE) {
         ConstructionController.State returnState = normalizeTaskState(this.flattenPillarReturnState);
         this.clearFinalScaffoldPillar();
         this.state = returnState;
         this.resumeState = returnState;
         return;
      }

      if (this.finalScaffoldPillarPhase == ConstructionController.FinalScaffoldPillarPhase.ASCENDING) {
         this.tickFinalScaffoldPillarAscent(mc, config, plan);
      } else if (this.finalScaffoldPillarPhase == ConstructionController.FinalScaffoldPillarPhase.BREAKING_TARGET) {
         this.tickFinalScaffoldPillarTargetBreak(mc, config, plan, target);
      } else {
         this.tickFinalScaffoldPillarDescent(mc, config, plan);
      }
   }

   private boolean resumeOrTickFinalScaffoldPillar(
      Minecraft mc,
      TerraLevelerConfig config,
      ConstructionController.State expectedReturnState,
      String routeFailure
   ) {
      if (this.finalScaffoldPillarTarget == null) return false;
      if (normalizeTaskState(this.flattenPillarReturnState) != expectedReturnState) {
         this.pause(mc, "高空临时柱事务返回状态不一致；已停止以保留脚手账本");
         return true;
      }

      if (this.finalScaffoldPillarPhase == ConstructionController.FinalScaffoldPillarPhase.ASCENDING
         && this.flattenPillarIndex == 0
         && this.finalScaffoldPillarPlan != null
         && !this.finalScaffoldPillarPlan.baseFeet().equals(PlayerPhysicalState.routeAnchor(mc).orElse(null))) {
         if (!this.resumeFinalScaffoldPillarRoute(mc) && !this.abandonUnstartedPillar(mc, expectedReturnState)) {
            this.pause(mc, routeFailure);
         }
         return true;
      }

      // 0.9.21 main-instance run: a pause or detour left the player beside a half-built
      // pillar; its top block sat at foot height, so every retry timed out on the first
      // tick (stale step timer). Give the pillar up instead; its blocks stay owned scaffolds.
      if (this.finalScaffoldPillarPhase == ConstructionController.FinalScaffoldPillarPhase.ASCENDING
         && this.flattenPillarIndex > 0
         && this.finalScaffoldPillarPlan != null
         && this.flattenPillarIndex < this.finalScaffoldPillarPlan.placements().size()) {
         BlockPos next = this.finalScaffoldPillarPlan.placements().get(this.flattenPillarIndex);
         boolean inColumn = Mth.floor(mc.player.getX()) == next.getX() && Mth.floor(mc.player.getZ()) == next.getZ();
         if (!inColumn && this.stablePlayerStand(mc)) {
            TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-left-column target=" + this.finalScaffoldPillarTarget.toShortString()
               + " next=" + next.toShortString() + " step=" + this.flattenPillarIndex + "/" + this.finalScaffoldPillarPlan.placements().size()
               + " player=" + mc.player.blockPosition().toShortString());
            if (!this.abandonUnstartedPillar(mc, expectedReturnState)) {
               this.pause(mc, routeFailure);
            }
            return true;
         }
      }

      this.flattenPillarStepTicks = 0;
      this.state = ConstructionController.State.FLATTEN_PILLARING;
      this.resumeState = expectedReturnState;
      this.tickFinalScaffoldPillar(mc, config);
      return true;
   }

   /**
    * A pillar whose base can no longer be reached before its ascent progressed is given
    * up: blocks it placed stay recorded as owned scaffolds and are cleaned like any other.
    * 0.8.74 paused here repeatedly after its own first pillar block occupied the base.
    */
   private boolean abandonUnstartedPillar(Minecraft mc, ConstructionController.State returnState) {
      BlockPos target = this.finalScaffoldPillarTarget;
      if (target == null || !this.stablePlayerStand(mc)) return false;
      this.actions.cancel(mc, "pillar-abandoned");
      this.clearBreak();
      this.clearRoute();
      if (this.finalScaffoldPillarCluster || !this.finalScaffoldPillarExcavation) this.clusterPillarRejected.add(target.immutable());
      this.clearFinalScaffoldPillar();
      this.clearRouteFailures();
      this.state = returnState;
      this.resumeState = returnState;
      this.detail = "临时柱无法继续，改用其他方式处理 " + target.toShortString();
      TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-abandoned target=" + target.toShortString() + " return=" + returnState
         + " active=" + this.activeScaffoldCount());
      return true;
   }

   private boolean resumeFinalScaffoldPillarRoute(Minecraft mc) {
      FinalScaffoldPillarPlanner.Candidate plan = this.finalScaffoldPillarPlan;
      BlockPos start = PlayerPhysicalState.routeAnchor(mc).orElse(null);
      if (plan == null || start == null || !this.stablePlayerStand(mc)) return false;
      SafeRoutePlanner.RoutePolicy policy = SafeRoutePlanner.RoutePolicy.flatteningFloor();
      SafeRoutePlanner.PlanResult routePlan = SafeRoutePlanner.findRoute(mc, start, List.of(plan.baseFeet()), this.workArea(), policy);
      if (!routePlan.hasRoute()) return false;

      this.cancelOwnedWork(mc, "final-scaffold-pillar-route-resume");
      this.route = routePlan.steps();
      this.routeIndex = 0;
      this.routeTaskKey = plan.baseFeet().immutable();
      this.routeReturnState = ConstructionController.State.FLATTEN_PILLARING;
      this.routePolicy = policy;
      this.routeRequiresTaskValidation = false;
      this.routeNeedsContinuation = false;
      this.routeNoProgressTicks = 0;
      this.routeCompletionDeferredTicks = 0;
      this.lastRouteDistance = Double.POSITIVE_INFINITY;
      this.state = this.route.isEmpty() ? ConstructionController.State.FLATTEN_PILLARING : ConstructionController.State.POSITIONING;
      this.resumeState = normalizeTaskState(this.flattenPillarReturnState);
      this.detail = "返回最后高空脚手临时柱的已预检柱底";
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "final-pillar-route-resumed target="
            + this.finalScaffoldPillarTarget.toShortString()
            + " start="
            + start.toShortString()
            + " base="
            + plan.baseFeet().toShortString()
            + " steps="
            + routePlan.steps().size()
      );
      return true;
   }

   private void tickFinalScaffoldPillarAscent(Minecraft mc, TerraLevelerConfig config, FinalScaffoldPillarPlanner.Candidate plan) {
      if (this.tickPillarSideCleanup(mc, config, plan, "ascent")) return;
      if (this.flattenPillarIndex >= plan.placements().size()) {
         if (!this.stablePlayerStand(mc)) {
            if (++this.flattenPillarStepTicks > MAX_FLATTEN_PILLAR_STEP_TICKS) {
               this.pause(mc, "最终高空脚手垫柱完成后未能在顶面稳定站立");
            } else {
               AutoNavigator.holdPillarColumn(mc, false);
               this.detail = "等待真实玩家在临时柱顶落稳";
            }
            return;
         }
         this.flattenPillarStepTicks = 0;
         this.finalScaffoldPillarPhase = ConstructionController.FinalScaffoldPillarPhase.BREAKING_TARGET;
         TerraLevelerDiagnostics.log("SCAFFOLD", "final-pillar-ascent-complete target=" + this.finalScaffoldPillarTarget.toShortString());
         return;
      }

      BlockPos placement = plan.placements().get(this.flattenPillarIndex);
      if (++this.flattenPillarStepTicks > MAX_FLATTEN_PILLAR_STEP_TICKS) {
         this.pause(mc, "最终高空脚手垫柱超时 " + placement.toShortString());
         return;
      }
      if (!mc.level.hasChunkAt(placement)) {
         this.pause(mc, "最终高空脚手垫柱区块未加载 " + placement.toShortString());
         return;
      }

      BlockState current = mc.level.getBlockState(placement);
      if (!current.getFluidState().isEmpty()) {
         this.pause(mc, "最终高空脚手垫柱遇到液体 " + placement.toShortString());
         return;
      }
      if (!current.getCollisionShape(mc.level, placement).isEmpty()) {
         if (!this.scaffoldPositions.contains(placement)) {
            if (this.submittedScaffoldBlocks.containsKey(placement)) {
               // 0.8.74: the client shows the requested block before the server confirms
               // it; this is our own pending pillar block, so keep waiting for the server.
               ServerActionExecutor.Status pending = this.actions.tickPlace(
                  mc, placement, this.finalScaffoldPillarMaterial, placement.below(), ServerActionExecutor.PlacementExpectation.SOLID_BLOCK
               );
               if (pending == ServerActionExecutor.Status.SUCCESS) {
                  this.confirmFinalScaffoldPillarPlacement(mc, placement);
                  this.advanceFinalScaffoldPillarPlacement(config, placement, plan);
               } else if (pending == ServerActionExecutor.Status.FAILED) {
                  this.pause(mc, this.actionFailure("最终高空脚手垫柱放置失败"));
               } else {
                  AutoNavigator.holdPillarColumn(mc, false);
                  this.detail = "等待服务器确认临时柱方块 " + placement.toShortString();
               }
               return;
            }
            this.pause(mc, "最终高空脚手垫柱位置被非事务方块占用 " + placement.toShortString());
            return;
         }
         this.confirmFinalScaffoldPillarPlacement(mc, placement);
         this.advanceFinalScaffoldPillarPlacement(config, placement, plan);
         return;
      }
      if (!stablePlacementSupport(mc, placement.below())) {
         this.pause(mc, "最终高空脚手垫柱失去稳定下方支撑 " + placement.toShortString());
         return;
      }

      int playerX = Mth.floor(mc.player.getX());
      int playerZ = Mth.floor(mc.player.getZ());
      if (playerX != placement.getX() || playerZ != placement.getZ()) {
         if (this.stablePlayerStand(mc)) {
            AutoNavigator.moveToward(mc, new BlockPos(placement.getX(), Mth.floor(mc.player.getY()), placement.getZ()), false);
            this.detail = "垫柱前回到柱体中心 " + placement.toShortString();
         } else {
            this.pause(mc, "最终高空脚手垫柱时横向离开计划列");
         }
         return;
      }
      if (mc.player.onGround()) {
         double dx = placement.getX() + 0.5 - mc.player.getX();
         double dz = placement.getZ() + 0.5 - mc.player.getZ();
         if (Math.sqrt(dx * dx + dz * dz) > 0.1) {
            AutoNavigator.moveToward(mc, new BlockPos(placement.getX(), Mth.floor(mc.player.getY()), placement.getZ()), false);
            this.detail = "垫柱前在当前方块中心站稳 " + placement.toShortString();
            return;
         }
      }
      if (this.waitForActionCooldown(mc) && this.actions.isIdle()) return;

      Item material = this.finalScaffoldPillarMaterial;
      int remaining = plan.placements().size() - this.flattenPillarIndex;
      if (!this.ensureHotbarMaterial(mc, material, 1)) {
         this.pause(mc, "最终高空脚手垫柱材料不足；仍需 " + remaining + " 块 " + materialName(material));
      } else if (ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), placement)) {
         boolean beginJump = mc.player.onGround();
         AutoNavigator.holdPillarColumn(mc, beginJump);
         if (beginJump && this.flattenPillarLoggedJumpY != placement.getY()) {
            this.flattenPillarLoggedJumpY = placement.getY();
            TerraLevelerDiagnostics.log("SCAFFOLD", "final-pillar-jump target=" + placement.toShortString());
         }
         this.detail = "原版跳跃离开脚下格，准备放置可回收临时柱 " + placement.toShortString();
      } else {
         BlockItem blockItem = material instanceof BlockItem value ? value : null;
         if (blockItem == null) {
            this.pause(mc, "最终高空脚手垫柱材料不是实体方块");
            return;
         }
         this.rememberSubmittedScaffold(placement, blockItem.getBlock());
         ServerActionExecutor.Status status = this.actions.tickPlace(
            mc, placement, material, placement.below(), ServerActionExecutor.PlacementExpectation.SOLID_BLOCK
         );
         if (status == ServerActionExecutor.Status.RUNNING) {
            this.detail = "放置可回收临时柱并等待服务器确认 " + placement.toShortString();
         } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
            AutoNavigator.holdPillarColumn(mc, mc.player.onGround());
         } else if (status == ServerActionExecutor.Status.FAILED) {
            this.pause(mc, this.actionFailure("最终高空脚手垫柱放置失败"));
         } else if (status != ServerActionExecutor.Status.SUCCESS) {
            this.pause(mc, "最终高空脚手垫柱放置返回异常状态 " + status);
         } else {
            this.confirmFinalScaffoldPillarPlacement(mc, placement);
            this.advanceFinalScaffoldPillarPlacement(config, placement, plan);
         }
      }
   }

   private void advanceFinalScaffoldPillarPlacement(
      TerraLevelerConfig config, BlockPos placement, FinalScaffoldPillarPlanner.Candidate plan
   ) {
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "final-pillar-step-confirmed target="
            + placement.toShortString()
            + " step="
            + (this.flattenPillarIndex + 1)
            + "/"
            + plan.placements().size()
      );
      this.flattenPillarIndex++;
      this.flattenPillarStepTicks = 0;
      this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
      this.startActionCooldown(config);
   }

   private void confirmFinalScaffoldPillarPlacement(Minecraft mc, BlockPos placement) {
      Block block = mc.level.getBlockState(placement).getBlock();
      this.submittedScaffoldBlocks.remove(placement);
      if (this.scaffoldPositions.add(placement.immutable())) this.scaffoldsPlaced++;
      this.scaffoldBlocks.put(placement.immutable(), block);
      this.persistOwnedScaffolds();
   }

   private void tickFinalScaffoldPillarTargetBreak(
      Minecraft mc, TerraLevelerConfig config, FinalScaffoldPillarPlanner.Candidate plan, BlockPos target
   ) {
      if (!this.stablePlayerStand(mc)) {
         if (++this.flattenPillarStepTicks > MAX_FLATTEN_PILLAR_STEP_TICKS) {
            this.pause(mc, "最终高空脚手柱顶未能稳定站立");
         } else {
            AutoNavigator.holdPillarColumn(mc, false);
         }
         return;
      }

      if (this.finalScaffoldPillarExcavation) {
         this.tickScaffoldPillarExcavationTarget(mc, config, plan, target);
         return;
      }

      if (!this.isTrackedScaffoldBlock(mc, target)) {
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, false);
         return;
      }
      if (!this.safeReach(mc, target)) {
         this.pause(mc, "到达临时柱顶后仍无法正常触及最后脚手 " + target.toShortString());
         return;
      }
      if (this.waitForActionCooldown(mc)) return;

      this.breakPurpose = ConstructionController.BreakPurpose.SCAFFOLD_CLEANUP;
      this.breakTarget = target;
      ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, target, config);
      if (status == null || status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "在临时柱顶清理最后高空脚手 " + target.toShortString();
      } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
         this.pause(mc, "临时柱顶清理最后脚手时失去正常触及距离");
      } else if (status == ServerActionExecutor.Status.FAILED) {
         this.pause(mc, this.actionFailure("最后高空脚手清理失败"));
      } else if (status == ServerActionExecutor.Status.SUCCESS || status == ServerActionExecutor.Status.RECLASSIFY) {
         this.clearBreak();
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, false);
      } else {
         this.pause(mc, "最后高空脚手清理返回异常状态 " + status);
      }
   }

   private void tickScaffoldPillarExcavationTarget(
      Minecraft mc, TerraLevelerConfig config, FinalScaffoldPillarPlanner.Candidate plan, BlockPos target
   ) {
      if (this.excavationIndex >= this.excavationTargets.size()
         || !target.equals(this.excavationTargets.get(this.excavationIndex))) {
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, false);
         return;
      }

      ExcavationPlanner.Operation operation = ExcavationPlanner.operationFor(this.classifyExcavation(mc, target));
      if (operation != ExcavationPlanner.Operation.BREAK) {
         boolean complete = operation == ExcavationPlanner.Operation.NONE;
         if (complete) this.batchBreaks.forget(target);
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, complete);
         return;
      }
      if (mc.level.getBlockState(target).is(BlockTags.LEAVES)
         || config.flattenProtectBlockEntities && mc.level.getBlockEntity(target) != null) {
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, false);
         return;
      }
      if (!this.safeReach(mc, target)) {
         this.pause(mc, "到达高空临时柱顶后仍无法正常触及挖掘目标 " + target.toShortString());
         return;
      }
      if (this.waitForActionCooldown(mc)) return;

      this.breakPurpose = ConstructionController.BreakPurpose.EXCAVATION;
      this.breakTarget = target;
      if (this.handleBlockingAnimal(mc, config, target, "pillar-excavation-break")) return;

      ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, target, config);
      if (status == null || status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "在临时柱顶挖掘高空目标 " + target.toShortString();
      } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
         this.pause(mc, "临时柱顶挖掘高空目标时失去正常触及距离");
      } else if (status == ServerActionExecutor.Status.FAILED) {
         this.pause(mc, this.actionFailure("高空目标挖掘失败"));
      } else if (status == ServerActionExecutor.Status.RECLASSIFY) {
         this.clearBreak();
         this.fluidSnapshotSettleTicks = 4;
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, false);
      } else if (status == ServerActionExecutor.Status.SUCCESS) {
         this.clearBreak();
         this.forgetScaffold(target);
         this.batchBreaks.forget(target);
         this.fluidSnapshotSettleTicks = 4;
         this.completeFinalScaffoldPillarTargetBreak(mc, config, plan, target, true);
      } else {
         this.pause(mc, "高空目标挖掘返回异常状态 " + status);
      }
   }

   private void completeFinalScaffoldPillarTargetBreak(
      Minecraft mc,
      TerraLevelerConfig config,
      FinalScaffoldPillarPlanner.Candidate plan,
      BlockPos target,
      boolean advanceExcavation
   ) {
      this.actions.cancel(mc, "final-pillar-target-confirmed");
      this.clearBreak();
      if (this.finalScaffoldPillarExcavation) {
         if (advanceExcavation
            && this.excavationIndex < this.excavationTargets.size()
            && target.equals(this.excavationTargets.get(this.excavationIndex))) {
            this.advanceExcavation(mc, 1, "pillar-server-confirmed-break");
         }
         BlockPos next = advanceExcavation ? this.nextReachablePillarExcavationTarget(mc, config, plan) : null;
         if (next != null) {
            this.finalScaffoldPillarTarget = next;
            this.flattenPillarStepTicks = 0;
            this.startActionCooldown(config);
            TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-excavation-next removed=" + target.toShortString() + " next=" + next.toShortString());
            return;
         }
      } else {
         this.forgetScaffold(target);
         if (this.scaffoldCleanupIndex < this.scaffoldCleanupTargets.size()
            && target.equals(this.scaffoldCleanupTargets.get(this.scaffoldCleanupIndex))) {
            this.scaffoldCleanupIndex++;
         }
         if (this.finalScaffoldPillarCluster) {
            BlockPos next = this.nextReachablePillarClusterScaffold(mc, plan);
            if (next != null) {
               this.finalScaffoldPillarClusterRemoved++;
               this.finalScaffoldPillarTarget = next;
               this.flattenPillarStepTicks = 0;
               this.startActionCooldown(config);
               TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-cluster-next removed=" + target.toShortString() + " next=" + next.toShortString()
                  + " clusterRemoved=" + this.finalScaffoldPillarClusterRemoved + " active=" + this.activeScaffoldCount());
               return;
            }
         }
      }
      this.finalScaffoldPillarDescentIndex = plan.placements().size() - 1;
      this.finalScaffoldPillarPhase = ConstructionController.FinalScaffoldPillarPhase.DESCENDING;
      this.flattenPillarStepTicks = 0;
      this.startActionCooldown(config);
      TerraLevelerDiagnostics.log(
         "SCAFFOLD",
         "pillar-target-confirmed mode=" + (this.finalScaffoldPillarExcavation ? "excavation" : "final-cleanup")
            + " target=" + target.toShortString() + " advanced=" + advanceExcavation + " beginDescent=true"
      );
   }

   /**
    * Owned scaffolds passed at arm's length while a cleanup pillar goes up or comes down are
    * taken down on the way (0.8.76). 0.8.75 often built a second pillar on the same base for
    * a scaffold it had already climbed past.
    */
   private boolean tickPillarSideCleanup(Minecraft mc, TerraLevelerConfig config, FinalScaffoldPillarPlanner.Candidate plan, String phase) {
      if (this.finalScaffoldPillarExcavation) return false;
      BlockPos side = this.pillarSideTarget;
      if (side == null) {
         if (!this.actions.isIdle() || !this.stablePlayerStand(mc)) return false;
         side = this.nextPillarSideScaffold(mc, plan);
         if (side == null) return false;
         this.pillarSideTarget = side;
      }
      if (!this.isTrackedScaffoldBlock(mc, side)) {
         // Dirt breaks instantly: the client shows it gone before the action reports back.
         this.actions.cancel(mc, "pillar-side-scaffold-gone");
         if (this.forgetScaffold(side)) this.finalScaffoldPillarClusterRemoved++;
         this.pillarSideTarget = null;
         TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-side-scaffold-removed target=" + side.toShortString() + " phase=" + phase + " active=" + this.activeScaffoldCount());
         return true;
      }
      if (!this.safeReach(mc, side) || this.isBlockSupportingPlayer(mc, side)) {
         this.actions.cancel(mc, "pillar-side-scaffold-out-of-reach");
         this.pillarSideSkipped.add(side);
         this.pillarSideTarget = null;
         return false;
      }
      this.flattenPillarStepTicks = 0;
      if (this.waitForActionCooldown(mc)) return true;
      ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, side, config);
      if (status == null || status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "沿临时柱顺手拆除旁边的脚手 " + side.toShortString();
         return true;
      }
      this.pillarSideTarget = null;
      if (status == ServerActionExecutor.Status.SUCCESS || status == ServerActionExecutor.Status.RECLASSIFY) {
         this.forgetScaffold(side);
         this.finalScaffoldPillarClusterRemoved++;
         this.startActionCooldown(config);
         TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-side-scaffold-removed target=" + side.toShortString() + " phase=" + phase
            + " active=" + this.activeScaffoldCount());
         return true;
      }
      this.actions.cancel(mc, "pillar-side-scaffold-skipped");
      this.pillarSideSkipped.add(side);
      TerraLevelerDiagnostics.log("SCAFFOLD", "pillar-side-scaffold-skipped target=" + side.toShortString() + " status=" + status);
      return false;
   }

   /** Nearest owned scaffold within reach that is neither the pillar, its target, the player's support nor next to fluid. */
   private BlockPos nextPillarSideScaffold(Minecraft mc, FinalScaffoldPillarPlanner.Candidate plan) {
      Set<BlockPos> pillar = new HashSet<>(plan.placements());
      BlockPos target = this.finalScaffoldPillarTarget;
      Vec3 eye = mc.player.getEyePosition();
      return this.scaffoldPositions.stream()
         .filter(pos -> !pillar.contains(pos) && !pos.equals(target) && !this.pillarSideSkipped.contains(pos))
         .filter(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos)) <= WorkPoseRules.SAFE_REACH_SQUARED)
         .filter(pos -> mc.level.hasChunkAt(pos) && this.isTrackedScaffoldBlock(mc, pos))
         .filter(pos -> !touchesFluid(mc, pos))
         .filter(pos -> this.safeReach(mc, pos) && !this.isBlockSupportingPlayer(mc, pos))
         .min(Comparator.comparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))))
         .map(BlockPos::immutable)
         .orElse(null);
   }

   private static boolean touchesFluid(Minecraft mc, BlockPos pos) {
      for (Direction direction : Direction.values()) {
         if (!mc.level.getFluidState(pos.relative(direction)).isEmpty()) return true;
      }
      return false;
   }

   /** Highest, then nearest, owned scaffold reachable from the pillar top that is not part of the pillar. */
   /**
    * The next queued excavation target, when it is within reach of the pillar top, is dug from
    * there too instead of taking the pillar down and building another one beside it (0.8.86).
    */
   private BlockPos nextReachablePillarExcavationTarget(Minecraft mc, TerraLevelerConfig config, FinalScaffoldPillarPlanner.Candidate plan) {
      if (this.excavationIndex >= this.excavationTargets.size()) {
         return null;
      }
      BlockPos next = this.excavationTargets.get(this.excavationIndex);
      Vec3 eye = mc.player.getEyePosition();
      if (plan.placements().contains(next)
         || eye.distanceToSqr(Vec3.atCenterOf(next)) > WorkPoseRules.SAFE_REACH_SQUARED
         || !mc.level.hasChunkAt(next)
         || ExcavationPlanner.operationFor(this.classifyExcavation(mc, next)) != ExcavationPlanner.Operation.BREAK
         || mc.level.getBlockState(next).is(BlockTags.LEAVES)
         || config.flattenProtectBlockEntities && mc.level.getBlockEntity(next) != null
         || !this.safeReach(mc, next)
         || this.isBlockSupportingPlayer(mc, next)) {
         return null;
      }
      return next.immutable();
   }

   private BlockPos nextReachablePillarClusterScaffold(Minecraft mc, FinalScaffoldPillarPlanner.Candidate plan) {
      Set<BlockPos> pillar = new HashSet<>(plan.placements());
      Vec3 eye = mc.player.getEyePosition();
      return this.scaffoldPositions.stream()
         .filter(pos -> !pillar.contains(pos))
         .filter(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos)) <= WorkPoseRules.SAFE_REACH_SQUARED)
         .filter(pos -> mc.level.hasChunkAt(pos) && this.isTrackedScaffoldBlock(mc, pos))
         .filter(pos -> this.safeReach(mc, pos) && !this.isBlockSupportingPlayer(mc, pos))
         .min(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()
            .thenComparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))))
         .map(BlockPos::immutable)
         .orElse(null);
   }

   private void tickFinalScaffoldPillarDescent(Minecraft mc, TerraLevelerConfig config, FinalScaffoldPillarPlanner.Candidate plan) {
      if (this.finalScaffoldPillarDescentIndex < 0) {
         if (!this.stablePlayerStand(mc)) {
            if (++this.flattenPillarStepTicks > MAX_FLATTEN_PILLAR_STEP_TICKS) {
               this.pause(mc, "最终临时柱回收完毕后未能在原地面落稳");
            } else {
               AutoNavigator.holdPillarColumn(mc, false);
               this.detail = "等待真实玩家落回临时柱底部原地面";
            }
            return;
         }
         int removed = plan.placements().size();
         BlockPos completedTarget = this.finalScaffoldPillarTarget;
         boolean excavation = this.finalScaffoldPillarExcavation;
         boolean cluster = this.finalScaffoldPillarCluster;
         int clusterRemoved = this.finalScaffoldPillarClusterRemoved;
         ConstructionController.State returnState = normalizeTaskState(this.flattenPillarReturnState);
         this.clearFinalScaffoldPillar();
         this.clearRouteFailures();
         if (!excavation) this.resetFlattenCleanupTopologyPlanning();
         this.state = returnState;
         this.resumeState = returnState;
         this.detail = excavation
            ? "高空挖掘目标处理完毕，整根临时柱已回收，继续挖掘队列"
            : cluster
            ? "高处悬空脚手已拆除，整根临时柱已回收，继续清理其余脚手"
            : "最后高空脚手与整根临时柱已回收，继续最终审计";
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "pillar-complete mode=" + (excavation ? "excavation" : cluster ? "cluster-cleanup" : "final-cleanup")
               + " target=" + posText(completedTarget) + " removedPillarBlocks=" + removed
               + " extraClusterScaffolds=" + clusterRemoved
               + " active=" + this.activeScaffoldCount() + " return=" + returnState
         );
         return;
      }

      BlockPos support = plan.placements().get(this.finalScaffoldPillarDescentIndex);
      if (!this.isTrackedScaffoldBlock(mc, support)) {
         this.actions.cancel(mc, "final-pillar-support-already-gone");
         this.forgetScaffold(support);
         this.finalScaffoldPillarDescentIndex--;
         this.flattenPillarStepTicks = 0;
         return;
      }
      if (!this.stablePlayerStand(mc)) {
         if (++this.flattenPillarStepTicks > MAX_FLATTEN_PILLAR_STEP_TICKS) {
            this.pause(mc, "逐格回收临时柱时未能落稳到下一层 " + support.toShortString());
         } else {
            AutoNavigator.holdPillarColumn(mc, false);
            this.detail = "逐格下落并等待下一层临时柱承接";
         }
         return;
      }
      if (!this.isBlockSupportingPlayer(mc, support)) {
         this.pause(mc, "逐格回收临时柱时真实玩家没有站在预期支撑上 " + support.toShortString());
         return;
      }
      if (this.tickPillarSideCleanup(mc, config, plan, "descent")) return;
      if (this.waitForActionCooldown(mc)) return;

      ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, support, config);
      if (status == null || status == ServerActionExecutor.Status.RUNNING) {
         this.detail = "拆除脚下临时柱并自然下降一格 " + support.toShortString();
      } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
         this.pause(mc, "逐格拆除脚下临时柱时失去正常触及距离 " + support.toShortString());
      } else if (status == ServerActionExecutor.Status.FAILED) {
         this.pause(mc, this.actionFailure("逐格回收临时柱失败"));
      } else if (status == ServerActionExecutor.Status.SUCCESS || status == ServerActionExecutor.Status.RECLASSIFY) {
         this.forgetScaffold(support);
         this.finalScaffoldPillarDescentIndex--;
         this.flattenPillarStepTicks = 0;
         this.startActionCooldown(config);
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "final-pillar-support-removed target=" + support.toShortString() + " remaining=" + (this.finalScaffoldPillarDescentIndex + 1)
         );
      } else {
         this.pause(mc, "逐格回收临时柱返回异常状态 " + status);
      }
   }

   private boolean beginFlattenPillarRecovery(Minecraft mc, ConstructionController.State returnState, BlockPos start) {
      if (this.flattenPlan != null && start != null && this.stablePlayerStand(mc)) {
         Set<BlockPos> plannedFill = new HashSet<>(this.wallTargets);
         FlattenPillarPlanner.Result result = FlattenPillarPlanner.plan(
            this.flattenPlan, this.flattenPlan.targetGroundY(), start, plannedFill::contains, position -> this.flattenPillarCell(mc, position)
         );
         if (!result.ready()) {
            TerraLevelerDiagnostics.log(
               "FLATTEN",
               "pillar-rejected start=" + start.toShortString() + " targetGroundY=" + this.flattenPlan.targetGroundY() + " status=" + result.status()
            );
            return false;
         } else {
            this.cancelOwnedWork(mc, "flatten-pillar-start");
            this.flattenPlacementMaterial = null;
            this.flattenPillarTargets = result.placements();
            this.flattenPillarIndex = 0;
            this.flattenPillarStepTicks = 0;
            this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
            this.flattenPillarReturnState = normalizeTaskState(returnState);
            this.state = ConstructionController.State.FLATTEN_PILLARING;
            this.resumeState = this.flattenPillarReturnState;
            this.clearRouteFailures();
            this.detail = "平地低坑没有步行出口，使用计划内泥土/草方块从脚下逐格垫高";
            TerraLevelerDiagnostics.log(
               "FLATTEN",
               "pillar-start column="
                  + start.getX()
                  + ","
                  + start.getZ()
                  + " firstY="
                  + result.placements().getFirst().getY()
                  + " lastY="
                  + result.placements().getLast().getY()
                  + " blocks="
                  + result.placements().size()
                  + " return="
                  + this.flattenPillarReturnState
            );
            return true;
         }
      } else {
         return false;
      }
   }

   private boolean beginFlattenEscapeStep(Minecraft mc, TerraLevelerConfig config, BlockPos task, BlockPos start) {
      if (this.flattenPlan != null && task != null && start != null && start.getY() <= this.flattenPlan.targetGroundY() && this.flattenEscapeStepsUsed < 16) {
         Optional<FlattenEscapePlanner.Step> planned = FlattenEscapePlanner.nextStep(
            this.flattenPlan, start, task, this.flattenPlan.targetGroundY(), position -> this.flattenEscapeCell(mc, config, position)
         );
         if (planned.isEmpty()) {
            TerraLevelerDiagnostics.log(
               "FLATTEN",
               "escape-rejected start=" + start.toShortString() + " task=" + task.toShortString() + " stepsUsed=" + this.flattenEscapeStepsUsed + "/16"
            );
            return false;
         } else {
            FlattenEscapePlanner.Step step = planned.get();
            boolean upwardClearance = step.destination().equals(start);
            this.cancelOwnedWork(mc, "flatten-escape-step-start");
            this.flattenEscapeTaskKey = step.taskKey();
            this.flattenEscapeDestination = step.destination();
            this.flattenEscapeBreakTargets = step.breakTargets();
            this.flattenEscapeBreakIndex = 0;
            this.state = ConstructionController.State.BUILDING_WALL;
            this.resumeState = this.state;
            this.clearRouteFailures();
            this.detail = upwardClearance ? "平地填充封住出口，先只向上挖开一格再重新判断路线" : "平地低坑无路，先挖开前方两格高普通地形再向前走";
            TerraLevelerDiagnostics.log(
               "FLATTEN",
               (upwardClearance ? "escape-upward-start from=" : "escape-step-start from=")
                  + start.toShortString()
                  + " destination="
                  + this.flattenEscapeDestination.toShortString()
                  + " task="
                  + task.toShortString()
                  + " breaks="
                  + this.flattenEscapeBreakTargets
                  + " step="
                  + (this.flattenEscapeStepsUsed + 1)
                  + "/16"
            );
            return true;
         }
      } else {
         return false;
      }
   }

   private void tickFlattenEscape(Minecraft mc, TerraLevelerConfig config) {
      while (this.flattenEscapeBreakIndex < this.flattenEscapeBreakTargets.size()) {
         BlockPos target = this.flattenEscapeBreakTargets.get(this.flattenEscapeBreakIndex);
         FlattenEscapePlanner.CellKind kind = this.flattenEscapeCell(mc, config, target);
         boolean ownsCurrentBreak = this.breakPurpose == ConstructionController.BreakPurpose.FLATTEN_ESCAPE && target.equals(this.breakTarget);
         boolean finishingActiveBreak = FlattenEscapePlanner.mustFinishActiveBreak(kind, ownsCurrentBreak, this.actions.isIdle());
         if (kind != FlattenEscapePlanner.CellKind.OPEN || finishingActiveBreak) {
            if (kind != FlattenEscapePlanner.CellKind.BREAKABLE_SOLID && !finishingActiveBreak) {
               this.pause(mc, "平地脱困前方方块已变为不可安全开挖状态 " + target.toShortString() + "（" + kind + "）");
               return;
            }

            if (!finishingActiveBreak && this.handleBlockingAnimal(mc, config, target, "flatten-escape")) {
               return;
            }

            this.breakPurpose = ConstructionController.BreakPurpose.FLATTEN_ESCAPE;
            this.breakTarget = target;
            ServerActionExecutor.Status status = this.tickBreakWithTrackedScaffoldToolGuard(mc, target, config);
            if (status == null) {
               return;
            }

            if (status == ServerActionExecutor.Status.RUNNING) {
               this.detail = (finishingActiveBreak ? "等待服务器确认通道已经挖开 " : "挖开低坑前方通道 ")
                  + target.toShortString()
                  + "（"
                  + (this.flattenEscapeBreakIndex + 1)
                  + "/"
                  + this.flattenEscapeBreakTargets.size()
                  + "）";
               return;
            }

            if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
               this.pause(mc, "低坑脱困方块超出正常触及距离 " + target.toShortString());
               return;
            }

            if (status == ServerActionExecutor.Status.FAILED) {
               this.pause(mc, this.actionFailure("低坑脱困开挖失败"));
               return;
            }

            if (status != ServerActionExecutor.Status.SUCCESS && status != ServerActionExecutor.Status.RECLASSIFY) {
               this.pause(mc, "低坑脱困开挖返回异常状态 " + status);
               return;
            }

            BlockPos opened = target.immutable();
            this.clearBreak();
            this.registerFlattenEscapeRefill(opened);
            this.flattenEscapeBreakIndex++;
            this.startActionCooldown(config);
            TerraLevelerDiagnostics.log(
               "FLATTEN",
               "escape-break-confirmed target="
                  + opened.toShortString()
                  + " remaining="
                  + (this.flattenEscapeBreakTargets.size() - this.flattenEscapeBreakIndex)
            );
            return;
         }

         this.registerFlattenEscapeRefill(target);
         this.clearBreak();
         this.flattenEscapeBreakIndex++;
      }

      BlockPos taskKey = this.flattenEscapeTaskKey;
      BlockPos destination = this.flattenEscapeDestination;
      boolean upwardClearance = destination != null && PlayerPhysicalState.routeAnchor(mc).<Boolean>map(destination::equals).orElse(false);
      if (taskKey == null) {
         this.pause(mc, "平地脱困移动缺少原施工任务标识");
      } else if (SafeRoutePlanner.canOccupy(mc, destination) && SafeRoutePlanner.hasFloor(mc, destination)) {
         this.clearPendingFlattenEscape();
         this.flattenEscapeStepsUsed++;
         TerraLevelerDiagnostics.log(
            "FLATTEN",
            (upwardClearance ? "escape-upward-open task=" : "escape-step-open task=")
               + taskKey.toShortString()
               + " movementGoal="
               + destination.toShortString()
               + " stepsUsed="
               + this.flattenEscapeStepsUsed
               + "/16"
         );
         this.beginRoute(
            mc,
            taskKey,
            List.of(destination),
            ConstructionController.State.BUILDING_WALL,
            config,
            upwardClearance ? "向上挖开一格后重新判断安全出口" : "沿刚确认的两格高通道向前移动",
            false
         );
      } else {
         this.pause(mc, "低坑前方通道已挖开，但目标格仍不能安全站立 " + destination.toShortString());
      }
   }

   private FlattenEscapePlanner.CellKind flattenEscapeCell(Minecraft mc, TerraLevelerConfig config, BlockPos position) {
      if (!mc.level.hasChunkAt(position)) {
         return FlattenEscapePlanner.CellKind.UNLOADED;
      } else if (!mc.level.getFluidState(position).isEmpty()) {
         return FlattenEscapePlanner.CellKind.FLUID;
      } else {
         BlockState state = mc.level.getBlockState(position);
         if (state.getCollisionShape(mc.level, position).isEmpty()) {
            return FlattenEscapePlanner.CellKind.OPEN;
         } else if (!state.is(Blocks.BEDROCK)
            && state.getDestroySpeed(mc.level, position) >= 0.0F
            && (!config.flattenProtectBlockEntities || mc.level.getBlockEntity(position) == null)) {
            return FlattenEscapePlanner.CellKind.BREAKABLE_SOLID;
         } else {
            return FlattenEscapePlanner.CellKind.PROTECTED_SOLID;
         }
      }
   }

   private void registerFlattenEscapeRefill(BlockPos position) {
      if (this.flattenPlan != null && FlattenRefillQueue.shouldRestoreEscapeBlock(position, this.flattenPlan.targetGroundY())) {
         if (FlattenRefillQueue.insertBeforeHigherLayers(this.wallTargets, this.wallIndex, position)) {
            TerraLevelerDiagnostics.log("FLATTEN", "escape-refill-scheduled target=" + position.toShortString() + " afterCurrentIndex=" + this.wallIndex);
         }
      }
   }

   private void clearPendingFlattenEscape() {
      this.flattenEscapeTaskKey = null;
      this.flattenEscapeDestination = null;
      this.flattenEscapeBreakTargets = List.of();
      this.flattenEscapeBreakIndex = 0;
      if (this.breakPurpose == ConstructionController.BreakPurpose.FLATTEN_ESCAPE) {
         this.clearBreak();
      }
   }

   private void resetFlattenEscape() {
      this.clearPendingFlattenEscape();
      this.flattenEscapeStepsUsed = 0;
   }

   private void tickFlattenPillar(Minecraft mc, TerraLevelerConfig config) {
      if (this.finalScaffoldPillarTarget != null) {
         this.tickFinalScaffoldPillar(mc, config);
         return;
      }

      if (!this.flattenPillarTargets.isEmpty() && this.flattenPillarIndex < this.flattenPillarTargets.size()) {
         BlockPos target = this.flattenPillarTargets.get(this.flattenPillarIndex);
         if (++this.flattenPillarStepTicks > 160) {
            this.pause(mc, "从脚下垫高超时 " + target.toShortString() + "；不会继续跳跃或在计划外放方块");
         } else if (!mc.level.hasChunkAt(target)) {
            this.pause(mc, "从脚下垫高所需区块未加载 " + target.toShortString());
         } else {
            if (this.actions.isIdle() || !isFlattenSurfaceMaterial(this.flattenPlacementMaterial)) {
               this.flattenPlacementMaterial = preferredFlattenSurfaceMaterial(mc);
            }

            Item dirt = this.flattenPlacementMaterial;
            if (!(dirt instanceof BlockItem)) {
               this.pause(mc, "平地机泥土/草方块材料不可用");
            } else {
               BlockState current = mc.level.getBlockState(target);
               if (!current.getFluidState().isEmpty()) {
                  this.pause(mc, "从脚下垫高遇到液体 " + target.toShortString());
               } else if (!current.getCollisionShape(mc.level, target).isEmpty()) {
                  if (!current.is(Blocks.DIRT) && !current.is(Blocks.GRASS_BLOCK)) {
                     this.pause(mc, "从脚下垫高位置出现非泥土/草方块实体 " + target.toShortString());
                  } else {
                     this.actions.cancel(mc, "flatten-pillar-already-confirmed");
                     TerraLevelerDiagnostics.log(
                        "FLATTEN",
                        "pillar-step-confirmed target="
                           + target.toShortString()
                           + " step="
                           + (this.flattenPillarIndex + 1)
                           + "/"
                           + this.flattenPillarTargets.size()
                     );
                     this.flattenPlacementMaterial = null;
                     this.flattenPillarIndex++;
                     this.flattenPillarStepTicks = 0;
                     this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
                  }
               } else if (!stablePlacementSupport(mc, target.below())) {
                  this.pause(mc, "从脚下垫高失去稳定下方支撑 " + target.toShortString());
               } else {
                  int playerX = Mth.floor(mc.player.getX());
                  int playerZ = Mth.floor(mc.player.getZ());
                  if (playerX == target.getX() && playerZ == target.getZ()) {
                     double dx = target.getX() + 0.5 - mc.player.getX();
                     double dz = target.getZ() + 0.5 - mc.player.getZ();
                     double horizontal = Math.sqrt(dx * dx + dz * dz);
                     if (mc.player.onGround() && horizontal > 0.1) {
                        AutoNavigator.moveToward(mc, new BlockPos(target.getX(), Mth.floor(mc.player.getY()), target.getZ()), false);
                        this.detail = "垫高前先在当前安全方块中心站稳 " + target.toShortString();
                     } else if (this.waitForActionCooldown(mc) && this.actions.isIdle()) {
                        AutoNavigator.holdPillarColumn(mc, false);
                        this.detail = "等待上一格泥土服务器确认后的安全间隔";
                     } else {
                        int remainingDirt = this.flattenPillarTargets.size() - this.flattenPillarIndex;
                        int looseDirt = inventoryCount(mc, dirt);
                        int dirtRestock = materialRestockAmount(looseDirt, remainingDirt, dirt.getDefaultMaxStackSize());
                        if (dirtRestock > 0 && hasPotentialMaterialBox(mc, dirt)) {
                           AutoNavigator.holdPillarColumn(mc, false);
                           this.beginMaterialUnpack(mc, ConstructionController.State.FLATTEN_PILLARING, dirt, dirtRestock);
                        } else if (!this.ensureHotbarMaterial(mc, dirt, 1)) {
                           this.pause(mc, "泥土/草方块不足，无法从低坑安全垫高；仍需 " + remainingDirt + " 块");
                        } else if (ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), target)) {
                           boolean beginJump = mc.player.onGround();
                           AutoNavigator.holdPillarColumn(mc, beginJump);
                           if (beginJump && this.flattenPillarLoggedJumpY != target.getY()) {
                              this.flattenPillarLoggedJumpY = target.getY();
                              TerraLevelerDiagnostics.log(
                                 "FLATTEN",
                                 "pillar-jump target="
                                    + target.toShortString()
                                    + " player="
                                    + String.format("%.3f,%.3f,%.3f", mc.player.getX(), mc.player.getY(), mc.player.getZ())
                              );
                           }

                           this.detail = "原版跳跃离开脚下格，准备放置计划内地表方块 " + target.toShortString();
                        } else {
                           ServerActionExecutor.Status status = this.actions
                              .tickPlace(mc, target, dirt, target.below(), ServerActionExecutor.PlacementExpectation.SOLID_BLOCK);
                           if (status == ServerActionExecutor.Status.RUNNING) {
                              this.detail = "放置脚下计划内地表方块并等待服务器确认 " + target.toShortString();
                           } else if (status == ServerActionExecutor.Status.NEED_REPOSITION) {
                              AutoNavigator.holdPillarColumn(mc, mc.player.onGround());
                              this.detail = "玩家碰撞箱尚未离开脚下目标，保持同列并重试跳跃";
                           } else if (status == ServerActionExecutor.Status.FAILED) {
                              this.pause(mc, this.actionFailure("脚下计划内地表方块放置失败"));
                           } else if (status != ServerActionExecutor.Status.SUCCESS) {
                              this.pause(mc, "脚下计划内地表方块放置返回异常状态 " + status);
                           } else {
                              TerraLevelerDiagnostics.log(
                                 "FLATTEN",
                                 "pillar-step-confirmed target="
                                    + target.toShortString()
                                    + " step="
                                    + (this.flattenPillarIndex + 1)
                                    + "/"
                                    + this.flattenPillarTargets.size()
                              );
                              this.flattenPlacementMaterial = null;
                              this.flattenPillarIndex++;
                              this.flattenPillarStepTicks = 0;
                              this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
                              this.startActionCooldown(config);
                           }
                        }
                     }
                  } else {
                     if (this.stablePlayerStand(mc)) {
                        TerraLevelerDiagnostics.log(
                           "FLATTEN", "pillar-abort-moved target=" + target.toShortString() + " player=" + mc.player.blockPosition().toShortString()
                        );
                        this.actions.cancel(mc, "flatten-pillar-player-moved");
                        ConstructionController.State next = this.flattenPillarReturnState;
                        this.clearFlattenPillar();
                        this.state = next;
                        this.resumeState = next;
                        this.detail = "真实玩家已离开垫高列，从当前位置重新规划";
                     } else {
                        this.beginFallRecovery(mc, this.flattenPillarReturnState, "垫高跳跃时横向离开计划列");
                     }
                  }
               }
            }
         }
      } else if (!this.stablePlayerStand(mc)) {
         if (++this.flattenPillarStepTicks > 160) {
            this.beginFallRecovery(mc, this.flattenPillarReturnState, "计划内泥土/草方块垫高完成后未能在顶面稳定站立");
         } else {
            AutoNavigator.holdPillarColumn(mc, false);
            this.detail = "等待真实玩家落稳到平地目标层";
         }
      } else {
         ConstructionController.State next = this.flattenPillarReturnState;
         TerraLevelerDiagnostics.log(
            "FLATTEN",
            "pillar-complete blocks=" + this.flattenPillarTargets.size() + " player=" + mc.player.blockPosition().toShortString() + " return=" + next
         );
         this.clearFlattenPillar();
         this.clearRouteFailures();
         this.state = next;
         this.resumeState = next;
         this.detail = "已用计划内泥土/草方块回到施工面，重新规划填充路线";
      }
   }

   private FlattenPillarPlanner.CellKind flattenPillarCell(Minecraft mc, BlockPos position) {
      if (!mc.level.hasChunkAt(position)) {
         return FlattenPillarPlanner.CellKind.UNLOADED;
      } else if (!mc.level.getFluidState(position).isEmpty()) {
         return FlattenPillarPlanner.CellKind.FLUID;
      } else {
         return mc.level.getBlockState(position).getCollisionShape(mc.level, position).isEmpty()
            ? FlattenPillarPlanner.CellKind.OPEN_DRY
            : FlattenPillarPlanner.CellKind.SOLID;
      }
   }

   private void clearFlattenPillar() {
      this.flattenPillarTargets = List.of();
      this.flattenPillarIndex = 0;
      this.flattenPillarStepTicks = 0;
      this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
      this.flattenPillarReturnState = ConstructionController.State.BUILDING_WALL;
      this.flattenPlacementMaterial = null;
   }

   private void clearFinalScaffoldPillar() {
      this.finalScaffoldPillarTarget = null;
      this.finalScaffoldPillarPlan = null;
      this.finalScaffoldPillarMaterial = null;
      this.finalScaffoldPillarDescentIndex = -1;
      this.finalScaffoldPillarPhase = ConstructionController.FinalScaffoldPillarPhase.NONE;
      this.finalScaffoldPillarExcavation = false;
      this.finalScaffoldPillarCluster = false;
      this.finalScaffoldPillarClusterRemoved = 0;
      this.flattenPillarIndex = 0;
      this.flattenPillarStepTicks = 0;
      this.flattenPillarLoggedJumpY = Integer.MIN_VALUE;
      this.pillarSideTarget = null;
      this.pillarSideSkipped.clear();
   }

   private boolean routeTaskNoLongerNeedsTravel(Minecraft mc, TerraLevelerConfig config) {
      return this.routeTaskNoLongerNeedsTravel(mc, config, true);
   }

   private boolean routeTaskNoLongerNeedsTravel(Minecraft mc, TerraLevelerConfig config, boolean reachableCountsAsComplete) {
      if (this.routeTaskKey == null) {
         return true;
      }

      if (this.returningFromCombat && this.combatReturnAnchor != null && this.combatReturnAnchor.equals(this.routeTaskKey)) {
         BlockPos current = PlayerPhysicalState.routeAnchor(mc).orElse(null);
         return this.combatReturnAnchor.equals(current) && horizontalDistance(mc, this.combatReturnAnchor) <= 0.2;
      }

      return switch (this.routeReturnState) {
         case EXCAVATING -> {
            if (this.excavationIndex >= this.excavationTargets.size()) {
               yield true;
            } else {
               BlockPos target = this.excavationTargets.get(this.excavationIndex);
               if (!target.equals(this.routeTaskKey)) {
                  yield true;
               } else {
                  ExcavationPlanner.Operation operation = ExcavationPlanner.operationFor(this.classifyExcavation(mc, target));
                  yield operation != ExcavationPlanner.Operation.BREAK
                     || reachableCountsAsComplete && this.safeReach(mc, target) && !this.isBlockSupportingPlayer(mc, target);
               }
            }
         }
         default -> false;
         case LOADING -> this.unloadedTarget == null || mc.level.hasChunkAt(this.unloadedTarget);
         case CONTAINING_FLUID -> {
            if (this.flattenFluidTransaction != null && this.fluidActionIndex < this.flattenFluidTransaction.placements().size()) {
               FlattenFluidDrainPlanner.Placement placement = this.flattenFluidTransaction.placements().get(this.fluidActionIndex);
               FluidCellKind current = fluidCell(mc, placement.target());
               yield !placement.target().equals(this.routeTaskKey)
                  || current != FluidCellKind.WATER && current != FluidCellKind.LAVA
                  || reachableCountsAsComplete
                     && !ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), placement.target())
                     && this.safeReach(mc, placement.support());
            } else {
               yield true;
            }
         }
         case CLEANING_SCAFFOLDS -> {
            if (this.scaffoldCleanupIndex >= this.scaffoldCleanupTargets.size()) {
               yield true;
            } else {
               BlockPos target = this.scaffoldCleanupTargets.get(this.scaffoldCleanupIndex);
               yield !mc.level.hasChunkAt(target)
                  ? true
                  : !target.equals(this.routeTaskKey)
                     || !this.isTrackedScaffoldBlock(mc, target)
                     || reachableCountsAsComplete && this.safeReach(mc, target) && !this.isBlockSupportingPlayer(mc, target);
            }
         }
         case BUILDING_WALL -> {
            if (this.wallIndex >= this.wallTargets.size()) {
               yield true;
            } else {
               BlockPos target = this.wallTargets.get(this.wallIndex);
               if (!target.equals(this.routeTaskKey)) {
                  yield true;
               } else if (!mc.level.hasChunkAt(target)) {
                  yield true;
               } else {
                  BlockState current = mc.level.getBlockState(target);
                  BlockItem wall = blockItem(this.jobWallBlock);
                  if (current.is(Blocks.BEDROCK) || wall == null) {
                     yield true;
                  } else if (current.getBlock() != wall.getBlock()
                     && (!current.is(Blocks.DIRT) && !current.is(Blocks.GRASS_BLOCK))) {
                     if (!current.isAir()
                        && current.getBlock() != wall.getBlock()
                        && (!(FluidBlocks.isPureFluid(current)))) {
                        yield reachableCountsAsComplete && this.safeReach(mc, target) && !this.isBlockSupportingPlayer(mc, target);
                     } else {
                        BlockPos support = this.flattenPlan != null && target.getY() == this.flattenPlan.targetGroundY()
                           ? findFlattenSurfaceSupport(mc, target)
                           : target.below();
                        yield reachableCountsAsComplete
                           && stablePlacementSupport(mc, support)
                           && this.safeReach(mc, support)
                           && !ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), target);
                     }
                  } else {
                     yield true;
                  }
               }
            }
         }
      };
   }

   private void tickLoading(final Minecraft mc, TerraLevelerConfig config) {
      if (this.unloadedTarget == null) {
         this.state = this.loadingReturnState;
      } else if (mc.level.hasChunkAt(this.unloadedTarget)) {
         ConstructionController.State next = this.loadingReturnState;
         this.unloadedTarget = null;
         this.loadingRelayAttempts = 0;
         this.state = next;
         this.detail = "目标区块已随真实玩家加载，返回施工任务";
      } else {
         BlockPos origin = PlayerPhysicalState.routeStart(mc).orElse(null);
         if (origin == null) {
            if (this.stablePlayerStand(mc)) {
               this.pause(mc, "真实玩家位于非整数高度碰撞面，无法选择安全区块加载中继");
            } else {
               this.beginFallRecovery(mc, this.loadingReturnState, "选择区块加载中继时没有稳定碰撞支撑");
            }
         } else {
            Optional<BlockPos> relay = LoadingRelayPlanner.choose(new LoadingRelayPlanner.Environment() {
               @Override
               public boolean loaded(BlockPos position) {
                  return mc.level.hasChunkAt(position);
               }

               @Override
               public boolean safeStand(BlockPos feet) {
                  return SafeRoutePlanner.canOccupy(mc, feet) && SafeRoutePlanner.hasFloor(mc, feet);
               }
            }, origin, this.unloadedTarget, this.workArea(), 40, this.failedLoadingRelays);
            if (relay.isEmpty()) {
               if (++this.loadingRelayAttempts >= 3) {
                  this.pause(mc, "目标区块未加载，且三次找不到朝目标方向的已加载安全中继站位 " + this.unloadedTarget.toShortString());
               } else {
                  this.detail = "等待地形加载并重新寻找安全中继（" + this.loadingRelayAttempts + "/3）";
               }
            } else {
               this.beginRoute(mc, relay.get(), List.of(relay.get()), ConstructionController.State.LOADING, config, "前往已加载区边缘，让远处边界继续加载");
            }
         }
      }
   }

   private void beginLoading(Minecraft mc, BlockPos target, ConstructionController.State returnState) {
      this.cancelOwnedWork(mc, "unloaded-target");
      this.unloadedTarget = target == null ? null : target.immutable();
      this.loadingReturnState = returnState;
      this.loadingRelayAttempts = 0;
      this.failedLoadingRelays.clear();
      this.state = ConstructionController.State.LOADING;
      this.resumeState = returnState;
      this.detail = target == null ? "等待所需区块加载" : "目标尚未加载，准备安全中继导航 " + target.toShortString();
   }

   private void beginWaterRecovery(Minecraft mc) {
      ConstructionController.State returnState = this.baseTaskState();
      BlockPos cleanupDropTarget = this.fallRecoveryScaffoldCleanupTarget;
      DestructiveSupportDropPlanner.Result cleanupDrop = this.fallRecoveryScaffoldCleanupDrop;
      BlockPos fallStart = this.flattenCleanupFallStart;
      boolean wasRouteReturn = this.flattenCleanupFallWasRouteReturn || this.routeIsFlattenCleanupReturn;
      if (returnState == ConstructionController.State.CLEANING_SCAFFOLDS
         && this.breakPurpose == ConstructionController.BreakPurpose.SCAFFOLD_CLEANUP
         && this.breakTarget != null
         && this.scaffoldCleanupDrop != null) {
         cleanupDropTarget = this.breakTarget.immutable();
         cleanupDrop = this.scaffoldCleanupDrop;
      }
      boolean rebaseAfterFall = this.flattenCleanupRebaseAfterFall
         || returnState == ConstructionController.State.CLEANING_SCAFFOLDS
            && FlattenCleanupExcursionRules.shouldRebaseAfterFall(
               this.hasFlattenCleanupTransaction(), cleanupDrop != null
            );
      if (rebaseAfterFall && fallStart == null) {
         fallStart = PlayerPhysicalState.routeAnchor(mc).orElse(mc.player.blockPosition()).immutable();
      }
      this.recoveryReturnState = returnState;
      this.cancelOwnedWork(mc, "accidental-water");
      if (returnState == ConstructionController.State.CLEANING_SCAFFOLDS) {
         this.fallRecoveryScaffoldCleanupTarget = cleanupDropTarget;
         this.fallRecoveryScaffoldCleanupDrop = cleanupDrop;
         this.flattenCleanupFallStart = fallStart;
         this.flattenCleanupFallWasRouteReturn = wasRouteReturn;
         this.flattenCleanupRebaseAfterFall = rebaseAfterFall;
      }
      this.state = ConstructionController.State.RECOVERING_WATER;
      this.waterPhase = ConstructionController.WaterPhase.ASCEND;
      this.waterExit = null;
      this.waterWaypoints = List.of();
      this.waterWaypointIndex = 0;
      this.waterRecoveryTicks = 0;
      this.waterNoProgressTicks = 0;
      this.waterRouteNoProgressTicks = 0;
      this.waterReplanCooldown = 0;
      this.waterBestMetric = Double.POSITIVE_INFINITY;
      this.waterHighestY = mc.player.getY();
      this.waterDryTicks = 0;
      this.waterDryUnstableTicks = 0;
      this.detail = "意外落水：进入独立脱困状态，不继续施工";
      TerraLevelerDiagnostics.log("RECOVERY", "water-start return=" + this.recoveryReturnState + " player=" + mc.player.blockPosition().toShortString());
   }

   private void tickWaterRecovery(final Minecraft mc) {
      if (++this.waterRecoveryTicks > 500) {
         this.pause(mc, "落水脱困超过 25 秒仍未找到安全干岸");
      } else {
         RecoveryTransitionRules.WaterResult transition = RecoveryTransitionRules.waterStep(
            mc.player.isInWater(), this.stablePlayerStand(mc), this.waterDryTicks, this.waterDryUnstableTicks, 10, 160
         );
         this.waterDryTicks = transition.stableDryTicks();
         this.waterDryUnstableTicks = transition.unstableDryTicks();
         if (transition.action() != RecoveryTransitionRules.WaterAction.IN_WATER) {
            this.waterPhase = ConstructionController.WaterPhase.DRY_CONFIRM;
            if (RecoveryTransitionRules.shouldContinueBankClimb(transition.action(), this.waterExit != null)) {
               boolean upwardAssist = RecoveryTransitionRules.needsUpwardBankAssist(mc.player.getY(), this.waterExit.getY());
               if (upwardAssist) {
                  AutoNavigator.swimToward(mc, this.waterExit);
               } else {
                  AutoNavigator.moveToward(mc, this.waterExit, false);
               }

               this.detail = "离水跨上安全干岸 "
                  + this.waterExit.toShortString()
                  + (upwardAssist ? "；保持上浮和水平推进" : "；已越过岸顶，停止上浮并等待重力落地")
                  + "（"
                  + this.waterDryUnstableTicks
                  + "/160）";
            } else {
               AutoNavigator.release(mc);
               if (transition.action() == RecoveryTransitionRules.WaterAction.RESUME_TASK) {
                  ConstructionController.State next = this.recoveryReturnState;
                  this.resetWaterRecovery();
                  this.state = next;
                  if (!this.finishFlattenCleanupFallRecovery(mc)) {
                     this.detail = "已确认真实玩家离水并站稳，重新规划原任务";
                  }
               } else if (transition.action() == RecoveryTransitionRules.WaterAction.PAUSE_UNSTABLE) {
                  this.pause(mc, "离水后悬空超过 8 秒仍未落到稳定地面");
               } else if (transition.action() == RecoveryTransitionRules.WaterAction.WAIT_UNSTABLE) {
                  this.detail = "离水后仍未站稳；保留同一次落水恢复计时并等待安全落地（" + this.waterDryUnstableTicks + "/160）";
               } else {
                  this.detail = "离水后已站稳，连续确认干燥地面（" + this.waterDryTicks + "/10）";
               }
            }
         } else {
            BlockPos origin = mc.player.blockPosition();
            if (this.waterReplanCooldown > 0) {
               this.waterReplanCooldown--;
            }

            if (this.waterExit == null && this.waterReplanCooldown <= 0 || this.waterRouteNoProgressTicks > 40) {
               this.waterPhase = ConstructionController.WaterPhase.FIND_DRY_EXIT;
               WaterEscapePlanner.EscapePlan escape = WaterEscapePlanner.nearestDryEscape(
                     new WaterEscapePlanner.Environment() {
                        @Override
                        public boolean loaded(BlockPos position) {
                           return mc.level.hasChunkAt(position);
                        }

                        @Override
                        public boolean safeDryStand(BlockPos feet) {
                           return SafeRoutePlanner.canOccupy(mc, feet)
                              && SafeRoutePlanner.hasFloor(mc, feet)
                              && mc.level.getFluidState(feet).isEmpty()
                              && mc.level.getFluidState(feet.above()).isEmpty();
                        }

                        @Override
                        public boolean water(BlockPos position) {
                           return !mc.level.getFluidState(position).isEmpty() && mc.level.getFluidState(position).is(FluidTags.WATER);
                        }

                        @Override
                        public boolean passable(BlockPos position) {
                           if (!mc.level.hasChunkAt(position)) {
                              return false;
                           }

                           BlockState state = mc.level.getBlockState(position);
                           return state.getCollisionShape(mc.level, position).isEmpty()
                              && (state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.WATER));
                        }
                     },
                     origin,
                     WATER_ESCAPE_RADIUS
                  )
                  .orElse(null);
               this.waterExit = escape == null ? null : escape.bank();
               this.waterWaypoints = escape == null ? List.of() : escape.waterWaypoints();
               this.waterWaypointIndex = 0;
               this.waterRouteNoProgressTicks = 0;
               this.waterReplanCooldown = 20;
               this.waterBestMetric = Double.POSITIVE_INFINITY;
               TerraLevelerDiagnostics.log(
                  "RECOVERY",
                  "water-plan origin="
                     + origin.toShortString()
                     + " radius="
                     + WATER_ESCAPE_RADIUS
                     + " exit="
                     + (this.waterExit == null ? "none" : this.waterExit.toShortString())
                     + " waypoints="
                     + this.waterWaypoints.size()
               );
            }

            if (this.waterExit != null) {
               for (this.waterPhase = ConstructionController.WaterPhase.SWIM_TO_EXIT;
                  this.waterWaypointIndex < this.waterWaypoints.size() && reachedWaterWaypoint(mc, this.waterWaypoints.get(this.waterWaypointIndex));
                  this.waterBestMetric = Double.POSITIVE_INFINITY
               ) {
                  this.waterWaypointIndex++;
                  this.waterRouteNoProgressTicks = 0;
               }

               BlockPos swimTarget = this.waterWaypointIndex < this.waterWaypoints.size() ? this.waterWaypoints.get(this.waterWaypointIndex) : this.waterExit;
               double metric = horizontalDistance(mc, swimTarget) + Math.abs(mc.player.getY() - swimTarget.getY()) * 0.35;
               if (metric < this.waterBestMetric - 0.03) {
                  this.waterBestMetric = metric;
                  this.waterNoProgressTicks = 0;
                  this.waterRouteNoProgressTicks = 0;
               } else {
                  this.waterNoProgressTicks++;
                  this.waterRouteNoProgressTicks++;
               }

               AutoNavigator.swimToward(mc, swimTarget);
               this.detail = "落水脱困：沿连通水路前往安全干岸 "
                  + this.waterExit.toShortString()
                  + "，水路节点 "
                  + Math.min(this.waterWaypointIndex + 1, this.waterWaypoints.size())
                  + "/"
                  + this.waterWaypoints.size()
                  + "（"
                  + this.waterRecoveryTicks
                  + "/500）";
            } else {
               this.waterPhase = ConstructionController.WaterPhase.ASCEND;
               if (mc.player.getY() > this.waterHighestY + 0.03) {
                  this.waterHighestY = mc.player.getY();
                  this.waterNoProgressTicks = 0;
                  this.waterRouteNoProgressTicks = 0;
               } else {
                  this.waterNoProgressTicks++;
                  this.waterRouteNoProgressTicks++;
               }

               AutoNavigator.swimUp(mc);
               this.detail = "落水脱困：先上浮并扫描干岸（" + this.waterRecoveryTicks + "/500）";
            }

            if (this.waterNoProgressTicks > 120) {
               this.pause(mc, "落水脱困 6 秒没有位置进展，可能被水下顶板或封闭水体困住");
            }
         }
      }
   }

   private void beginFallRecovery(Minecraft mc, ConstructionController.State returnState, String reason) {
      ConstructionController.State normalizedReturnState = normalizeTaskState(returnState);
      BlockPos cleanupDropTarget = this.fallRecoveryScaffoldCleanupTarget;
      DestructiveSupportDropPlanner.Result cleanupDrop = this.fallRecoveryScaffoldCleanupDrop;
      BlockPos fallStart = this.flattenCleanupFallStart;
      boolean wasRouteReturn = this.flattenCleanupFallWasRouteReturn || this.routeIsFlattenCleanupReturn;
      if (normalizedReturnState == ConstructionController.State.CLEANING_SCAFFOLDS
         && this.breakPurpose == ConstructionController.BreakPurpose.SCAFFOLD_CLEANUP
         && this.breakTarget != null
         && this.scaffoldCleanupDrop != null) {
         cleanupDropTarget = this.breakTarget.immutable();
         cleanupDrop = this.scaffoldCleanupDrop;
      }
      boolean rebaseAfterFall = this.flattenCleanupRebaseAfterFall
         || normalizedReturnState == ConstructionController.State.CLEANING_SCAFFOLDS
            && FlattenCleanupExcursionRules.shouldRebaseAfterFall(
               this.hasFlattenCleanupTransaction(), cleanupDrop != null
            );
      if (rebaseAfterFall && fallStart == null) {
         fallStart = PlayerPhysicalState.routeAnchor(mc).orElse(mc.player.blockPosition()).immutable();
      }
      this.cancelOwnedWork(mc, "fall-recovery");
      this.fallRecoveryScaffoldCleanupTarget = cleanupDropTarget;
      this.fallRecoveryScaffoldCleanupDrop = cleanupDrop;
      this.flattenCleanupFallStart = fallStart;
      this.flattenCleanupFallWasRouteReturn = wasRouteReturn;
      this.flattenCleanupRebaseAfterFall = rebaseAfterFall;
      this.recoveryReturnState = normalizedReturnState;
      this.state = ConstructionController.State.RECOVERING_FALL;
      this.fallRecoveryTicks = 0;
      this.defenseTargetId = -1;
      this.defenseReason = null;
      this.defenseNoThreatTicks = 0;
      this.defenseApproachCooldown = 0;
      this.defenseRouteTarget = null;
      this.defenseTicks = 0;
      this.ignoredDefenseEntities.clear();
      this.detail = reason + "；停止水平移动并等待安全落地";
      TerraLevelerDiagnostics.log("RECOVERY", "fall-start return=" + this.recoveryReturnState + " player=" + mc.player.blockPosition().toShortString());
   }

   private void tickFallRecovery(Minecraft mc) {
      AutoNavigator.release(mc);
      if (mc.player.isInWater()) {
         this.beginWaterRecovery(mc);
      } else if (this.stablePlayerStand(mc)) {
         this.state = this.recoveryReturnState;
         this.fallRecoveryTicks = 0;
         if (!this.finishFlattenCleanupFallRecovery(mc)) {
            this.detail = "真实玩家已经安全落地，重新规划原任务";
         }
      } else if (++this.fallRecoveryTicks > 160) {
         this.pause(mc, "下落或无地面状态持续超过 8 秒；为避免死亡已停止");
      } else {
         this.detail = "等待真实玩家落到稳定地面（" + this.fallRecoveryTicks + "/160）";
      }
   }

   private boolean finishFlattenCleanupFallRecovery(Minecraft mc) {
      BlockPos removed = this.fallRecoveryScaffoldCleanupTarget;
      DestructiveSupportDropPlanner.Result supportDrop = this.fallRecoveryScaffoldCleanupDrop;
      BlockPos fallStart = this.flattenCleanupFallStart;
      boolean wasRouteReturn = this.flattenCleanupFallWasRouteReturn;
      boolean shouldRebase = this.flattenCleanupRebaseAfterFall;
      this.fallRecoveryScaffoldCleanupTarget = null;
      this.fallRecoveryScaffoldCleanupDrop = null;
      this.flattenCleanupFallStart = null;
      this.flattenCleanupFallWasRouteReturn = false;
      this.flattenCleanupRebaseAfterFall = false;
      if (this.recoveryReturnState != ConstructionController.State.CLEANING_SCAFFOLDS || !shouldRebase) {
         return false;
      }

      BlockPos landing = PlayerPhysicalState.routeAnchor(mc).orElse(mc.player.blockPosition()).immutable();
      if (removed != null && mc.level.hasChunkAt(removed) && !this.isTrackedScaffoldBlock(mc, removed)) {
         this.forgetScaffold(removed);
      }
      BlockPos expectedLanding = supportDrop == null ? null : supportDrop.landing();
      if (!FlattenCleanupExcursionRules.didActuallyFall(fallStart, expectedLanding, landing)) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-fall-rebase-skipped reason=no-physical-drop start="
               + posText(fallStart)
               + " actual="
               + landing.toShortString()
               + " expected="
               + posText(expectedLanding)
               + " routeCreated="
               + this.flattenCleanupRouteScaffolds.size()
         );
         if (wasRouteReturn && !this.flattenCleanupRouteScaffolds.isEmpty()) {
            this.flattenCleanupRouteReturnPending = true;
            this.beginFlattenCleanupRouteReturn(mc);
            return true;
         }
         return false;
      }
      this.rescheduleFlattenCleanupAfterFall(removed, supportDrop, landing);
      this.detail = "真实玩家已经安全落地，已从真实落点重排剩余脚手清理";
      return true;
   }

   private List<BlockPos> workPoses(Minecraft mc, BlockPos taskTarget, BlockPos interactionTarget, ConstructionController.WorkAction action) {
      List<BlockPos> result = new ArrayList<>();
      int maximumFeetY = WorkPoseRules.maximumScanFeetY(
         taskTarget, interactionTarget, action == ConstructionController.WorkAction.PLACE
      );

      for (int y = taskTarget.getY() - 2; y <= maximumFeetY; y++) {
         for (int x = taskTarget.getX() - 3; x <= taskTarget.getX() + 3; x++) {
            for (int z = taskTarget.getZ() - 3; z <= taskTarget.getZ() + 3; z++) {
               BlockPos pose = new BlockPos(x, y, z);
               if (SafeRoutePlanner.canOccupy(mc, pose)) {
                  boolean valid = action == ConstructionController.WorkAction.BREAK
                     ? WorkPoseRules.breakAllowed(pose, taskTarget)
                     : WorkPoseRules.placementAllowed(pose, taskTarget, interactionTarget);
                  if (valid) {
                     result.add(pose.immutable());
                  }
               }
            }
         }
      }

      result.sort(Comparator.comparingDouble(pos -> mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)));
      return result;
   }

   /**
    * Filling walked one block per surface cell (0.9.08 test: about 0.4 s a cell, 45 minutes
    * for 6121 cells) because the nearest stand that reaches the cell was always chosen. Keep
    * only stands that also reach most of the next cells, so one walk serves several cells.
    */
   /**
    * 0.9.10 test (4306 cells in 26 minutes): a route ends as soon as the active cell is in
    * reach, so the player kept standing in the next cell and stepped once per cell. Before
    * walking, place every other cell reachable from here (0.9.11).
    */
   private boolean promoteFillPlaceableHere(Minecraft mc) {
      if (this.flattenPlan == null || mc.player == null) return false;
      int targetY = this.flattenPlan.targetGroundY();
      int promoted = FlattenRefillQueue.promoteNearestPlaceableHere(
         this.wallTargets,
         this.wallIndex,
         mc.player.blockPosition(),
         candidate -> {
            if (candidate.getY() != targetY || !mc.level.hasChunkAt(candidate) || !mc.level.getBlockState(candidate).isAir()) return false;
            if (ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), candidate)) return false;
            BlockPos candidateSupport = findFlattenSurfaceSupport(mc, candidate);
            return candidateSupport != null && stablePlacementSupport(mc, candidateSupport) && this.safeReach(mc, candidateSupport);
         }
      );
      if (promoted < 0) return false;
      this.fillPlacedHere++;
      if (TerraLevelerDiagnostics.isEnabled() && this.fillPlacedHere % 100 == 1) {
         TerraLevelerDiagnostics.log("FLATTEN", "fill-placeable-here target=" + this.wallTargets.get(this.wallIndex).toShortString()
            + " fromIndex=" + promoted + " wall=" + this.wallIndex + "/" + this.wallTargets.size() + " count=" + this.fillPlacedHere);
      }
      return true;
   }

   private void preferFillLookaheadPoses(BlockPos taskTarget, List<BlockPos> poses) {
      if (this.wallIndex >= this.wallTargets.size()) return;
      List<BlockPos> upcoming = this.wallTargets.subList(
         this.wallIndex, Math.min(this.wallTargets.size(), this.wallIndex + WorkPoseRules.FILL_LOOKAHEAD_TARGETS)
      );
      int[] covered = new int[poses.size()];
      for (int index = 0; index < poses.size(); index++) {
         covered[index] = WorkPoseRules.coveredTargets(poses.get(index), upcoming, WorkPoseRules.FILL_LOOKAHEAD_REACH_SQUARED);
      }
      List<Integer> chosen = WorkPoseRules.lookaheadChoice(covered, WorkPoseRules.FILL_LOOKAHEAD_MIN_USEFUL);
      if (chosen == null || chosen.size() == poses.size()) return;
      List<BlockPos> kept = new ArrayList<>();
      int best = 0;
      for (int index : chosen) {
         kept.add(poses.get(index));
         best = Math.max(best, covered[index]);
      }
      int before = poses.size();
      poses.retainAll(kept);
      if (TerraLevelerDiagnostics.isEnabled() && this.fillLookaheadTried.size() % 50 == 1) {
         TerraLevelerDiagnostics.log("ROUTE", "fill-lookahead task=" + taskTarget.toShortString() + " poses=" + before + "->" + poses.size()
            + " bestCovered=" + best + " upcoming=" + upcoming.size());
      }
   }

   private void rejectUnusableRouteGoal(Minecraft mc, String reason) {
      ConstructionController.State next = this.routeReturnState;
      BlockPos failedKey = this.routeTaskKey;
      this.clearRoute();
      int failures = this.recordRouteFailure(failedKey, next);
      TerraLevelerDiagnostics.log("ROUTE", "goal-rejected task=" + posText(failedKey) + " state=" + next + " failures=" + failures + " reason=" + reason);
      if (failures >= 3) {
         this.pause(mc, reason + "；连续三次路线终点均无法执行任务");
      } else {
         this.state = next;
         this.routeRetryCooldownTicks = 10;
         this.detail = reason + "，将在短暂退避后重新规划（" + failures + "/3）";
      }
   }

   private void finishRoute(Minecraft mc) {
      if (this.routeIsFlattenCleanupReturn) {
         AutoNavigator.release(mc);
         this.route = List.of();
         this.routeIndex = 0;
         this.routeReservedScaffoldBudget = 0;
         this.routeNeedsContinuation = false;
         this.routeRequiresTaskValidation = false;
         this.routeNoProgressTicks = 0;
         this.routeCompletionDeferredTicks = 0;
         this.lastRouteDistance = Double.POSITIVE_INFINITY;
         this.detail = "已回到首轮清场安全起点，回收最后一段路线脚手";
      } else {
         Optional<BlockPos> currentAnchor = PlayerPhysicalState.routeAnchor(mc);
         if (this.flattenCleanupRouteReturnAnchor != null
            && this.routeIndex < this.route.size()
            && currentAnchor.filter(this.route.get(this.routeIndex).position()::equals).isPresent()
            && !this.recordFlattenCleanupOutboundStep(mc, this.route.get(this.routeIndex))) {
            return;
         }

         ConstructionController.State next = this.routeReturnState;
         boolean completedMaterialPickup = this.routeIsMaterialPickupDetour;
         boolean completedCombatReturn = this.returningFromCombat && this.combatReturnAnchor != null && this.combatReturnAnchor.equals(this.routeTaskKey);
         boolean completedExcavationWorkRoute = this.routeRequiresTaskValidation
            && next == ConstructionController.State.EXCAVATING
            && !completedMaterialPickup
            && !completedCombatReturn;
         BlockPos completedWorkTask = completedExcavationWorkRoute && this.routeTaskKey != null ? this.routeTaskKey.immutable() : null;
         AutoNavigator.release(mc);
         this.clearRoute();
         this.clearRouteFailures();
         if (completedCombatReturn) {
            TerraLevelerDiagnostics.log("COMBAT", "return-arrived anchor=" + this.combatReturnAnchor.toShortString() + " resume=" + next);
            this.combatReturnAnchor = null;
            this.returningFromCombat = false;
         }

         this.state = next;
         if (completedWorkTask != null && this.recordUncommittedWorkRouteArrival(completedWorkTask)) {
            this.pause(
               mc,
               "同一施工目标已连续 " + this.uncommittedWorkRouteArrivals
                  + " 次到达可施工站位却没有提交动作；已停止重复搭拆路线 " + completedWorkTask.toShortString()
            );
         } else if (completedMaterialPickup) {
            this.dirtPickupCooldownTicks = 40;
            this.detail = "已走过附近施工材料掉落点，继续原任务";
            TerraLevelerDiagnostics.log("MATERIAL", "pickup-detour-complete resume=" + next);
         } else {
            this.detail = completedCombatReturn ? "已返回战斗前站位，继续施工" : "已到达经复验的安全站位，返回原任务";
         }
      }
   }

   private void continuePartialRoute(Minecraft mc, TerraLevelerConfig config) {
      if (this.routeIsFlattenCleanupReturn) {
         AutoNavigator.release(mc);
         this.clearRoute();
         this.state = ConstructionController.State.CLEANING_SCAFFOLDS;
         this.resumeState = this.state;
         this.beginFlattenCleanupRouteReturn(mc);
      } else {
         ConstructionController.State next = this.routeReturnState;
         BlockPos task = this.routeTaskKey;
         boolean combatReturn = this.returningFromCombat && this.combatReturnAnchor != null && this.combatReturnAnchor.equals(task);
         AutoNavigator.release(mc);
         this.clearRoute();
         if (combatReturn) {
            TerraLevelerDiagnostics.log("ROUTE", "partial-complete task=" + posText(task) + " resume=COMBAT_RETURN replan=true");
            this.beginCombatReturn(mc, config);
         } else {
            this.state = next;
            this.resumeState = next;
            this.routeRetryCooldownTicks = 1;
            this.clearRouteFailures();
            this.detail = "已走完安全分段，按服务器实际位置继续规划剩余路线";
            TerraLevelerDiagnostics.log("ROUTE", "partial-complete task=" + posText(task) + " resume=" + next + " replan=true");
         }
      }
   }

   private void invalidateRoute(Minecraft mc, String reason) {
      ConstructionController.State next = this.routeReturnState;
      BlockPos failedKey = this.routeTaskKey;
      boolean materialPickupDetour = this.routeIsMaterialPickupDetour;
      boolean flattenCleanupReturn = this.routeIsFlattenCleanupReturn;
      this.actions.cancel(mc, "route-invalid");
      AutoNavigator.release(mc);
      this.clearRoute();
      if (flattenCleanupReturn) {
         this.abortInvalidFlattenCleanupReturn(mc, reason);
      } else if (materialPickupDetour) {
         this.dirtPickupCooldownTicks = 100;
         this.state = next;
         this.resumeState = next;
         this.detail = "附近材料拾取路线失效，已跳过并继续原施工";
         TerraLevelerDiagnostics.log("MATERIAL", "pickup-detour-skipped target=" + posText(failedKey) + " reason=" + reason + " resume=" + next);
      } else if (next == ConstructionController.State.LOADING) {
         this.markLoadingRelayFailure(mc, failedKey, reason);
      } else {
         int failures = this.recordRouteFailure(failedKey, next);
         if (failures >= 3) {
            this.pause(mc, reason + "；连续三次路线失效");
         } else {
            this.state = next;
            this.detail = reason + "，重新规划（" + failures + "/3）";
         }
      }
   }

   private boolean recordRouteProgress(Minecraft mc, double distance) {
      if (distance < this.lastRouteDistance - 0.025) {
         this.lastRouteDistance = distance;
         this.routeNoProgressTicks = 0;
      } else {
         this.routeNoProgressTicks++;
      }

      if (this.routeNoProgressTicks <= 60) {
         return true;
      }

      this.invalidateRoute(mc, "路线 60 刻没有实际进展");
      return false;
   }

   private boolean handleBlockingAnimal(Minecraft mc, TerraLevelerConfig config, BlockPos waypoint, String context) {
      if (config.autoDefend && (this.state != ConstructionController.State.POSITIONING || this.routeReturnState != ConstructionController.State.DEFENDING)) {
         long gameTime = mc.level.getGameTime();
         DefensiveCombat.Threat blocker = DefensiveCombat.findRouteBlocker(
            mc,
            waypoint,
            Math.max(4.5, config.defenseRange),
            entityId -> this.ignoredDefenseEntities.contains(entityId, gameTime)
         );
         if (blocker == null) {
            this.clearRouteBlockerCandidate();
            return false;
         }

         if (this.routeBlockerCandidateId == blocker.entity().getId()) {
            this.routeBlockerTicks++;
         } else {
            this.routeBlockerCandidateId = blocker.entity().getId();
            this.routeBlockerTicks = 1;
            TerraLevelerDiagnostics.log(
               "COMBAT",
               "route-blocker-observed target="
                  + DefensiveCombat.describe(blocker.entity())
                  + " name="
                  + blocker.entity().getName().getString()
                  + " context="
                  + context
                  + " waypoint="
                  + waypoint.toShortString()
            );
         }

         boolean lowHealth = mc.player.getHealth() < config.minimumHealth;
         if (!DefensiveCombat.routeBlockerConfirmed(this.routeBlockerTicks, lowHealth)) {
            return false;
         }

         TerraLevelerDiagnostics.log(
            "COMBAT",
            "route-blocker-confirmed target="
               + DefensiveCombat.describe(blocker.entity())
               + " ticks="
               + this.routeBlockerTicks
               + " lowHealth="
               + lowHealth
               + " health="
               + mc.player.getHealth()
               + " threshold="
               + config.minimumHealth
               + " context="
               + context
               + " waypoint="
               + waypoint.toShortString()
         );
         this.clearRouteBlockerCandidate();
         this.beginDefense(mc, blocker);
         return true;
      } else {
         this.clearRouteBlockerCandidate();
         return false;
      }
   }

   /**
    * Something alive stands in the surface cell about to be placed (0.9.09: the 0.9.08 test
    * paused twice after three refused placements). A hostile mob, or an ordinary animal when
    * enabled, is killed after a short confirmation; anything else gets a few seconds to move
    * and then the cell is placed at the end of the fill instead. Returns true while handled.
    */
   private boolean handlePlacementBlocker(Minecraft mc, TerraLevelerConfig config, BlockPos target) {
      List<LivingEntity> occupants = DefensiveCombat.placementOccupants(mc, target);
      if (occupants.isEmpty()) {
         this.placementBlockerTarget = null;
         this.placementBlockerTicks = 0;
         return false;
      }
      if (!target.equals(this.placementBlockerTarget)) {
         this.placementBlockerTarget = target.immutable();
         this.placementBlockerTicks = 0;
         TerraLevelerDiagnostics.log("COMBAT", "placement-blocker-observed target=" + target.toShortString()
            + " occupants=" + occupants.stream().map(DefensiveCombat::describe).toList());
      }
      this.placementBlockerTicks++;
      AutoNavigator.release(mc);
      long gameTime = mc.level.getGameTime();
      DefensiveCombat.Threat threat = config.autoDefend
         ? DefensiveCombat.findPlacementBlocker(mc, target, Math.max(4.5, config.defenseRange), config.clearBlockingAnimals,
            entityId -> this.ignoredDefenseEntities.contains(entityId, gameTime))
         : null;
      if (threat != null && DefensiveCombat.routeBlockerConfirmed(this.placementBlockerTicks)) {
         TerraLevelerDiagnostics.log("COMBAT", "placement-blocker-confirmed target=" + target.toShortString()
            + " entity=" + DefensiveCombat.describe(threat.entity()) + " ticks=" + this.placementBlockerTicks);
         this.placementBlockerTarget = null;
         this.placementBlockerTicks = 0;
         this.beginDefense(mc, threat);
         return true;
      }
      if (this.placementBlockerTicks < PLACEMENT_BLOCKER_WAIT_TICKS || threat != null) {
         this.detail = "等待 " + occupants.getFirst().getName().getString() + " 离开要放方块的位置 " + target.toShortString();
         return true;
      }
      // Not ours to kill and not moving: place this cell at the end of the fill.
      if (this.wallIndex < this.wallTargets.size() && this.blockedPlacementsDeferred.add(target.immutable())) {
         List<BlockPos> reordered = new ArrayList<>(this.wallTargets);
         reordered.remove(this.wallIndex);
         reordered.add(target.immutable());
         this.wallTargets = reordered;
         TerraLevelerDiagnostics.log("COMBAT", "placement-blocker-deferred target=" + target.toShortString()
            + " occupants=" + occupants.stream().map(DefensiveCombat::describe).toList());
         this.placementBlockerTarget = null;
         this.placementBlockerTicks = 0;
         this.detail = "位置 " + target.toShortString() + " 被生物占着，先铺别处，最后再回来";
         return true;
      }
      // Already deferred once: keep waiting rather than fail three placements and pause.
      this.detail = "等待 " + occupants.getFirst().getName().getString() + " 离开要放方块的位置 " + target.toShortString();
      return true;
   }

   private void clearRouteBlockerCandidate() {
      this.routeBlockerCandidateId = -1;
      this.routeBlockerTicks = 0;
   }

   private int recordRouteFailure(BlockPos taskKey, ConstructionController.State taskState) {
      if (taskKey == null || !taskKey.equals(this.routeFailureKey) || taskState != this.routeFailureState) {
         this.routeFailureKey = taskKey == null ? null : taskKey.immutable();
         this.routeFailureState = taskState;
         this.routeFailures = 0;
      }

      return ++this.routeFailures;
   }

   private void markLoadingRelayFailure(Minecraft mc, BlockPos relay, String reason) {
      if (relay != null && this.failedLoadingRelays.add(relay.immutable())) {
         this.loadingRelayAttempts++;
      }

      if (this.loadingRelayAttempts >= 3) {
         this.pause(mc, reason + "；三个不同中继站位均不可达");
      } else {
         this.state = ConstructionController.State.LOADING;
         this.detail = reason + "，排除该点并寻找下一中继（" + this.loadingRelayAttempts + "/3）";
      }
   }

   private void clearRouteFailures() {
      this.routeFailures = 0;
      this.routeFailureKey = null;
      this.routeFailureState = null;
   }

   private boolean hasPendingExcavationRouteCommit() {
      if (this.pendingExcavationRouteCommit == null) {
         return false;
      }
      if (this.state != ConstructionController.State.EXCAVATING
         || this.excavationIndex >= this.excavationTargets.size()
         || !this.pendingExcavationRouteCommit.equals(this.excavationTargets.get(this.excavationIndex))) {
         this.clearUncommittedWorkRoute();
         return false;
      }
      return true;
   }

   private boolean isPendingExcavationRouteCommit(BlockPos target) {
      return target != null && this.hasPendingExcavationRouteCommit() && target.equals(this.pendingExcavationRouteCommit);
   }

   private boolean recordUncommittedWorkRouteArrival(BlockPos target) {
      this.pendingExcavationRouteCommit = target.immutable();
      if (target.equals(this.uncommittedWorkRouteTarget)) {
         this.uncommittedWorkRouteArrivals++;
      } else {
         this.uncommittedWorkRouteTarget = target.immutable();
         this.uncommittedWorkRouteArrivals = 1;
      }
      TerraLevelerDiagnostics.log(
         "ROUTE",
         "work-commit-pending task=" + target.toShortString()
            + " arrivals=" + this.uncommittedWorkRouteArrivals + "/" + MAX_UNCOMMITTED_WORK_ROUTE_ARRIVALS
            + " overflow=" + posText(this.rollingScaffoldOverflowTarget)
      );
      return RouteExecutionRules.repeatedWorkRouteArrivalsExceeded(
         this.uncommittedWorkRouteArrivals, MAX_UNCOMMITTED_WORK_ROUTE_ARRIVALS
      );
   }

   private void commitReachedExcavationTarget(BlockPos target, String reason) {
      if (target != null && target.equals(this.pendingExcavationRouteCommit)) {
         TerraLevelerDiagnostics.log(
            "ROUTE",
            "work-commit-submitted task=" + target.toShortString()
               + " arrivals=" + this.uncommittedWorkRouteArrivals + " reason=" + reason
         );
      }
      this.clearUncommittedWorkRoute();
   }

   private void clearUncommittedWorkRoute() {
      this.pendingExcavationRouteCommit = null;
      this.uncommittedWorkRouteTarget = null;
      this.uncommittedWorkRouteArrivals = 0;
   }

   private void advanceExcavation(Minecraft mc, int count, String reason) {
      this.cancelMotionAndRoute(mc, reason);
      this.clearBreak();
      int actual = Math.min(count, this.excavationTargets.size() - this.excavationIndex);
      for (int index = this.excavationIndex; index < this.excavationIndex + actual; index++) {
         this.deferredFoliageTargets.remove(this.excavationTargets.get(index));
      }
      this.excavationIndex += actual;
      if (actual > 0) {
         this.clearUncommittedWorkRoute();
         this.deferredRouteTargets.clear();
      }

      if (!this.flattenDirtHarvesting) {
         this.completed += actual;
      }
   }

   private void setExcavationTargets(List<BlockPos> targets) {
      this.excavationTargets = targets == null ? List.of() : targets;
      this.excavationSpatialIndex.rebuild(this.excavationTargets);
   }

   /**
    * Own scaffolds plus fluid left in place (0.8.94). Without the latter every pre-fill rescan put
    * the 21 abandoned cells back in the queue, the queue skipped them at once and the next rescan
    * of all 55852 columns followed: 209 rescans in a minute, the game ran at a few frames.
    */
   /**
    * Fill (paving) targets without the cells around fluid left in place. 0.8.95 paved 99 of 106
    * cells of a small selection and then paused ten times on -3653,62,-10205, the floor right
    * under the abandoned water line, which is out of reach for the same reason as the water.
    */
   private List<BlockPos> fillTargetsOutsideAbandonedFluid(FlattenPlan plan, BlockPos origin) {
      List<BlockPos> fill = new ArrayList<>(plan.fillTargetsFrom(origin));
      if (!this.abandonedFluidZone.isEmpty()) fill.removeIf(this.abandonedFluidZone::contains);
      return fill;
   }

   private Set<BlockPos> rescanExclusions(Minecraft mc) {
      Set<BlockPos> owned = this.confirmedOwnedScaffolds(mc);
      if (this.abandonedFluidZone.isEmpty()) return owned;
      Set<BlockPos> exclusions = new HashSet<>(owned);
      exclusions.addAll(this.abandonedFluidZone);
      return exclusions;
   }

   private FlattenExcavationScheduler.Result scheduleFlattenExcavation(Minecraft mc, FlattenPlan candidate, BlockPos origin, String phase) {
      FlattenExcavationScheduler.Result result = candidate.scheduledExcavationTargetsFrom(origin, this.rescanExclusions(mc));
      TerraLevelerDiagnostics.log(
         "PLAN",
         "flatten-excavation-scheduled phase="
            + phase
            + " source="
            + candidate.primaryExcavationTargetCount()
            + " treeTargets="
            + candidate.treeTargetCount()
            + " trees="
            + candidate.treeCount()
            + " targets="
            + result.targets().size()
            + " excludedOwnedScaffolds="
            + result.excludedScaffolds()
            + " tiles="
            + result.tileCount()
            + " origin="
            + posText(origin)
      );
      return result;
   }

   private FlattenExcavationScheduler.Result scheduleFlattenFoliage(Minecraft mc, FlattenPlan candidate, BlockPos origin, String phase) {
      FlattenExcavationScheduler.Result result = candidate.scheduledDeferredFoliageTargetsFrom(
         origin, this.rescanExclusions(mc)
      );
      TerraLevelerDiagnostics.log(
         "PLAN",
         "flatten-foliage-scheduled phase=" + phase
            + " source=" + candidate.deferredFoliageTargetCount()
            + " targets=" + result.targets().size()
            + " excludedOwnedScaffolds=" + result.excludedScaffolds()
            + " tiles=" + result.tileCount()
            + " origin=" + posText(origin)
      );
      return result;
   }

   private List<BlockPos> cleanupFlattenFoliageTargets(List<BlockPos> foliage, TerraLevelerConfig config, String phase) {
      boolean cleanBoundary = config != null && config.flattenCleanBoundaryFoliage;
      List<BlockPos> cleanup = BoundaryFoliageClassifier.cleanupTargets(
         foliage, this.flattenInteriorFoliageTargets, cleanBoundary
      );
      TerraLevelerDiagnostics.log(
         "PLAN",
         "flatten-foliage-policy phase=" + phase
            + " all=" + foliage.size()
            + " interiorTracked=" + this.flattenInteriorFoliageTargets.size()
            + " cleanup=" + cleanup.size()
            + " skippedBoundary=" + (foliage.size() - cleanup.size())
            + " cleanBoundary=" + cleanBoundary
      );
      return cleanup;
   }

   private Set<BlockPos> confirmedOwnedScaffolds(Minecraft mc) {
      Set<BlockPos> confirmed = new LinkedHashSet<>();
      for (BlockPos scaffold : this.scaffoldPositions) {
         if (this.isTrackedScaffoldBlock(mc, scaffold)) confirmed.add(scaffold.immutable());
      }
      return confirmed;
   }

   /**
    * 0.9.11 test: the later passes dug the layer the player stood on one block at a time
    * (route one step, wait, break, repeat; about 200-600 blocks a minute against 2000 at the
    * start), because the next block in the queue was usually the one under the player. Before
    * walking, dig the nearest planned block that is safely breakable from here, using the same
    * checks as the batch bubble (0.9.13). Returns true when the queue head changed.
    */
   private boolean promoteExcavationReachableHere(Minecraft mc, BlockPos queueTarget) {
      if (mc.player == null || this.excavationSpatialIndex.size() == 0) return false;
      Vec3 eye = mc.player.getEyePosition();
      int feetY = mc.player.blockPosition().getY();
      long gameTime = mc.level.getGameTime();
      boolean treeOnly = this.flattenPlan != null && this.flattenPlan.isTreeTarget(queueTarget);
      List<BlockPos> nearby = this.excavationSpatialIndex.within(eye, 4.5);
      nearby.sort(Comparator.comparingDouble(position -> eye.distanceToSqr(Vec3.atCenterOf(position))));
      for (BlockPos candidate : nearby) {
         if (candidate.equals(queueTarget)
            || !BatchMiningSafetyRules.allowsReachPromotion(candidate.getY(), feetY)
            || treeOnly && !this.flattenPlan.isSameTree(queueTarget, candidate)
            || this.deferredFluidZone.contains(candidate)
            || this.abandonedFluidZone.contains(candidate)
            || this.batchBreaks.isPending(candidate)
            || !this.batchBreaks.canSubmit(candidate, gameTime)
            || !this.batchBreakSafeCandidate(mc, candidate)) {
            continue;
         }
         int index = this.excavationTargets.indexOf(candidate);
         if (index <= this.excavationIndex) continue;
         if (!(this.excavationTargets instanceof ArrayList<BlockPos>)) {
            // Same targets, so the spatial index stays valid.
            this.excavationTargets = new ArrayList<>(this.excavationTargets);
         }
         this.excavationTargets.add(this.excavationIndex, this.excavationTargets.remove(index));
         this.excavationReachPromotions++;
         if (TerraLevelerDiagnostics.isEnabled() && this.excavationReachPromotions % 200 == 1) {
            TerraLevelerDiagnostics.log("PLAN", "excavation-reachable-here target=" + candidate.toShortString()
               + " instead=" + queueTarget.toShortString() + " fromIndex=" + index + " excavation=" + this.excavationIndex
               + "/" + this.excavationTargets.size() + " count=" + this.excavationReachPromotions);
         }
         return true;
      }
      return false;
   }

   private boolean promoteFoliageAccessTarget(Minecraft mc, BlockPos taskTarget, String reason) {
      // 0.9.23 main-instance run: promoted leaves were as unreachable as the log behind them,
      // so each one promoted the next (79 in 3 s) until none were left and the job paused.
      BlockPos root = this.promotedFoliageRoots.get(taskTarget);
      if (root != null) {
         return this.dropUnreachableFoliageAccess(taskTarget, root, reason);
      }
      if (this.deferredFoliageTargets.isEmpty() || this.foliageAccessExhausted.contains(taskTarget)) {
         return false;
      }
      Optional<BlockPos> selected = FoliageAccessPlanner.select(
         taskTarget,
         mc.player == null ? null : mc.player.blockPosition(),
         this.deferredFoliageTargets,
         position -> mc.level.hasChunkAt(position) && mc.level.getBlockState(position).is(BlockTags.LEAVES),
         position -> this.isBlockSupportingPlayer(mc, position)
      );
      if (selected.isEmpty()) return false;

      BlockPos foliage = selected.get();
      this.deferredFoliageTargets.remove(foliage);
      this.promotedFoliageRoots.put(foliage.immutable(), taskTarget.immutable());
      List<BlockPos> promoted = new ArrayList<>(this.excavationTargets.size() + 1);
      promoted.addAll(this.excavationTargets.subList(0, this.excavationIndex));
      promoted.add(foliage);
      promoted.addAll(this.excavationTargets.subList(this.excavationIndex, this.excavationTargets.size()));
      this.cancelMotionAndRoute(mc, "foliage-access-promoted");
      this.clearBreak();
      this.setExcavationTargets(promoted);
      this.clearUncommittedWorkRoute();
      this.clearRouteFailures();
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = this.state;
      this.detail = "树叶阻挡主体施工，只清除必要访问方块 " + foliage.toShortString();
      TerraLevelerDiagnostics.log(
         "PLAN",
         "leaf-obstruction-promoted target=" + foliage.toShortString()
            + " task=" + taskTarget.toShortString()
            + " reason=" + reason
            + " deferredRemaining=" + this.deferredFoliageTargets.size()
      );
      return true;
   }

   /**
    * A promoted access leaf that cannot be reached either is left where it is (leaves are not
    * cleared by default) and its excavation target stops promoting leaves, so it goes on to the
    * normal unreachable handling (defer, pillar) instead of chaining through the whole crown.
    */
   private boolean dropUnreachableFoliageAccess(BlockPos leaf, BlockPos root, String reason) {
      if (this.excavationIndex >= this.excavationTargets.size() || !leaf.equals(this.excavationTargets.get(this.excavationIndex))) {
         return false;
      }
      List<BlockPos> remaining = new ArrayList<>(this.excavationTargets.size() - 1);
      remaining.addAll(this.excavationTargets.subList(0, this.excavationIndex));
      remaining.addAll(this.excavationTargets.subList(this.excavationIndex + 1, this.excavationTargets.size()));
      this.promotedFoliageRoots.remove(leaf);
      this.foliageAccessExhausted.add(root);
      this.clearBreak();
      this.setExcavationTargets(remaining);
      this.clearUncommittedWorkRoute();
      this.clearRouteFailures();
      this.state = ConstructionController.State.EXCAVATING;
      this.resumeState = this.state;
      this.detail = "够不着的树叶留在原处 " + leaf.toShortString();
      TerraLevelerDiagnostics.log("PLAN", "leaf-access-dropped target=" + leaf.toShortString() + " task=" + root.toShortString()
         + " reason=" + reason);
      return true;
   }

   private List<BlockPos> orderedScaffoldCleanupTargets(Minecraft mc, String phase) {
      this.clusterPillarRejected.clear();
      this.parkedCleanupTargets.clear();
      this.pillarBlockersPrioritized.clear();
      this.parkedCleanupRetries = 0;
      if (this.hasScaffoldsLeftToClean()) {
         BlockPos origin = mc.player == null ? null : mc.player.blockPosition();
         FlattenScaffoldCleanupScheduler.Result result = FlattenScaffoldCleanupScheduler.schedule(
            new ArrayList<>(this.scaffoldPositions.stream().filter(pos -> !this.leftBehindScaffolds.contains(pos)).toList()), origin
         );
         this.scaffoldCleanupClusters = result.clusterIds();
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "flatten-cleanup-scheduled phase="
               + phase
               + " targets="
               + result.targets().size()
                + " order=height-priority-touching-component-top-down clusters="
                + result.clusterCount()
                + " connectivity=touching-26"
                + " pass="
               + this.flattenFinalCleanupPass
               + " origin="
               + posText(origin)
         );
         return result.targets();
      } else {
         this.scaffoldCleanupClusters = Map.of();
         return List.copyOf(this.scaffoldPositions);
      }
   }

   private boolean beginFlattenDirtHarvest(Minecraft mc, Item dirtItem) {
      if (this.flattenPlan != null && mc.player != null && mc.level != null && isFlattenSurfaceMaterial(dirtItem)) {
         int targetY = this.flattenPlan.targetGroundY();
         int topY = this.flattenPlan.scannedTopY();

         for (int x = this.flattenPlan.minX(); x <= this.flattenPlan.maxX(); x++) {
            for (int z = this.flattenPlan.minZ(); z <= this.flattenPlan.maxZ(); z++) {
               if (!this.flattenPlan.containsColumn(x, z)) continue;
               if (mc.level.hasChunkAt(x, z)) {
                  topY = Math.max(topY, TerrainTopResolver.surfaceBlockY(mc.level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, x, z)));
               }
            }
         }

         int remainingFill = Math.max(1, this.wallTargets.size() - this.wallIndex);
         int desired = Math.min(64, remainingFill);
         FlattenDirtHarvestPlanner.Plan harvest = FlattenDirtHarvestPlanner.plan(
            this.flattenPlan, targetY, topY, mc.player.blockPosition(), desired, pos -> this.classifyFlattenDirtHarvestCell(mc, pos)
         );
         if (harvest.targets().isEmpty()) {
            TerraLevelerDiagnostics.log("MATERIAL", "flatten-dirt-harvest-unavailable targetY=" + targetY + " scannedTopY=" + topY + " remainingFill=" + remainingFill);
            return false;
         }

         this.cancelOwnedWork(mc, "flatten-dirt-harvest-start");
         this.flattenDirtHarvesting = true;
         this.flattenDirtHarvestGoal = Math.min(desired, harvest.expectedDirt());
         this.flattenDirtHarvestExpected = harvest.expectedDirt();
         this.flattenDirtPickupSettleTicks = 0;
         this.setExcavationTargets(new ArrayList<>(harvest.targets()));
         this.deferredRouteTargets.clear();
         this.excavationIndex = 0;
         this.state = ConstructionController.State.EXCAVATING;
         this.resumeState = this.state;
         this.detail = "泥土/草方块用尽，优先削除选区内目标 Y 以上的就近土层补料";
         TerraLevelerDiagnostics.log(
            "MATERIAL",
            "flatten-dirt-harvest-start targetY="
               + targetY
               + " topY="
               + topY
               + " targets="
               + this.excavationTargets.size()
               + " expectedDirt="
               + this.flattenDirtHarvestExpected
               + " goal="
               + this.flattenDirtHarvestGoal
               + " returnWall="
               + this.wallIndex
               + "/"
               + this.wallTargets.size()
         );
         if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("TerraLeveler：泥土/草方块已用尽，先挖选区内高于目标 Y 的土层补充，随后自动继续填平。"));
         }

         return true;
      } else {
         return false;
      }
   }

   private FlattenDirtHarvestPlanner.CellKind classifyFlattenDirtHarvestCell(Minecraft mc, BlockPos pos) {
      if (mc.level != null && mc.level.hasChunkAt(pos)) {
         BlockState state = mc.level.getBlockState(pos);
         if (!state.isAir() && !state.getCollisionShape(mc.level, pos).isEmpty()) {
            TerraLevelerConfig config = TerraLeveler.CONFIG;
            boolean protectBlockEntities = config == null || config.flattenProtectBlockEntities;
            if (state.getFluidState().isEmpty() && (!protectBlockEntities || mc.level.getBlockEntity(pos) == null)) {
               if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) {
                  return FlattenDirtHarvestPlanner.CellKind.DIRT_SOURCE;
               } else {
                  return !state.is(Blocks.DIRT_PATH)
                        && !state.is(Blocks.FARMLAND)
                        && !state.is(Blocks.PODZOL)
                        && !state.is(Blocks.MYCELIUM)
                        && !state.is(Blocks.COARSE_DIRT)
                        && !state.is(Blocks.ROOTED_DIRT)
                        && !state.is(Blocks.SNOW)
                     ? FlattenDirtHarvestPlanner.CellKind.BLOCKED
                     : FlattenDirtHarvestPlanner.CellKind.SOIL_OVERBURDEN;
               }
            } else {
               return FlattenDirtHarvestPlanner.CellKind.BLOCKED;
            }
         } else {
            return FlattenDirtHarvestPlanner.CellKind.AIR;
         }
      } else {
         return FlattenDirtHarvestPlanner.CellKind.BLOCKED;
      }
   }

   private void finishFlattenDirtHarvest(Minecraft mc, String reason) {
      int collected = flattenSurfaceInventoryCount(mc);
      int harvestedTargets = this.excavationIndex;
      this.cancelOwnedWork(mc, "flatten-dirt-harvest-finish-" + reason);
      this.flattenDirtHarvesting = false;
      this.flattenDirtHarvestGoal = 0;
      this.flattenDirtHarvestExpected = 0;
      this.flattenDirtPickupSettleTicks = 0;
      this.setExcavationTargets(List.of());
      this.deferredRouteTargets.clear();
      this.excavationIndex = 0;
      this.state = ConstructionController.State.BUILDING_WALL;
      this.resumeState = this.state;
      this.detail = "已获得 " + collected + " 块泥土/草方块，返回原填充位置继续施工";
      TerraLevelerDiagnostics.log(
         "MATERIAL",
         "flatten-dirt-harvest-finish reason="
            + reason
            + " collected="
            + collected
            + " brokenTargets="
            + harvestedTargets
            + " resumeWall="
            + this.wallIndex
            + "/"
            + this.wallTargets.size()
      );
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal("TerraLeveler：已补充 " + collected + " 块泥土/草方块，继续原填平进度。"));
      }
   }

   private void advanceWall(Minecraft mc, String reason) {
      this.cancelMotionAndRoute(mc, reason);
      this.clearBreak();
      this.resetFlattenEscape();
      this.wallIndex++;
      this.completed++;
   }

   private void pause(Minecraft mc, String reason) {
      this.pause(mc, reason, autoRetryAllowedForPause(reason)
         ? ConstructionController.PauseKind.AUTO_RETRY
         : ConstructionController.PauseKind.MANUAL_RESUME);
   }

   /**
    * Pause with an explicit resume policy instead of inferring it from the
    * message text: AUTO_RETRY revalidates after 30 seconds, MANUAL_RESUME waits
    * for the player, WAIT_FOR_HEALTH resumes once health has recovered.
    */
   private void pause(Minecraft mc, String reason, ConstructionController.PauseKind kind) {
      this.resumeState = this.baseTaskState();
      if (this.state == ConstructionController.State.EATING) {
         this.cancelEating(mc, "pause-" + reason);
      }

      if (this.defenseTargetId >= 0 || this.combatWeaponHotbar >= 0) {
         this.releaseCombatWeapon(mc, "pause-" + reason);
         this.clearDefenseForPause();
      }

      this.state = ConstructionController.State.PAUSED;
      this.cancelOwnedWork(mc, "pause");
      this.flushOwnedScaffoldLedger();
      this.pausedTicks = 0;
      this.pauseReason = reason;
      this.resetAutoRetryStreakAfterProgress();
      boolean retryable = kind == ConstructionController.PauseKind.AUTO_RETRY;
      boolean exhausted = retryable && autoRetryAttemptsExhausted(this.consecutiveAutoRetryAttempts);
      this.pauseAutoRetryAllowed = retryable && !exhausted;
      this.pauseWaitsForHealth = kind == ConstructionController.PauseKind.WAIT_FOR_HEALTH;
      this.detail = exhausted
         ? reason + "，连续自动恢复 " + this.consecutiveAutoRetryAttempts + " 次仍未取得施工进展，已停止自动重试"
         : reason + "，已暂停";
      TerraLevelerDiagnostics.log(
         "STATE",
         "pause reason="
            + reason
            + " excavation="
            + this.excavationIndex
            + "/"
            + this.excavationTargets.size()
            + " wall="
            + this.wallIndex
            + "/"
            + this.wallTargets.size()
            + " scaffolds="
            + this.activeScaffoldCount()
            + " placements="
            + this.scaffoldsPlaced
            + " autoRetry="
            + this.pauseAutoRetryAllowed
            + " autoRetryAttempts="
            + this.consecutiveAutoRetryAttempts
            + " autoRetryAfterTicks="
            + (this.pauseAutoRetryAllowed ? AUTO_RETRY_PAUSE_TICKS : -1)
      );
      TerraLevelerAlerts.paused(mc, "TerraLeveler", exhausted ? this.detail : reason,
         this.pauseWaitsForHealth ? TerraLevelerAlerts.Resume.AFTER_HEALTH
            : this.pauseAutoRetryAllowed ? TerraLevelerAlerts.Resume.AUTO_RETRY : TerraLevelerAlerts.Resume.BY_PLAYER);
   }

   private void tickPausedAutoRetry(Minecraft mc) {
      AutoNavigator.release(mc);
      if (this.pauseWaitsForHealth) {
         TerraLevelerConfig config = TerraLeveler.CONFIG;
         int minimumHealth = config == null ? 12 : config.minimumHealth;
         float resumeAt = lowHealthResumeThreshold(minimumHealth, mc.player.getMaxHealth());
         if (mc.player.getHealth() < resumeAt || !safeInventoryStateForAutoRetry(mc)) {
            this.detail = String.format("生命值 %.1f/%.0f，暂停中；回复后自动继续施工", mc.player.getHealth(), resumeAt);
            return;
         }
         ConstructionController.State next = normalizeTaskState(this.resumeState);
         this.cancelOwnedWork(mc, "health-recovered");
         this.clearBudgetReliefSearch();
         this.clearBudgetReliefRejections();
         this.clearRouteFailures();
         this.routeRetryCooldownTicks = 0;
         this.pausedTicks = 0;
         this.pauseWaitsForHealth = false;
         this.pauseReason = null;
         this.state = next;
         this.resumeState = next;
         this.detail = "生命值已回复，重新验证并继续施工";
         TerraLevelerDiagnostics.log("STATE", "resume-after-health-recovered health=" + mc.player.getHealth() + " resume=" + next);
         return;
      }
      if (!this.pauseAutoRetryAllowed) {
         return;
      }

      this.pausedTicks++;
      int remaining = Math.max(0, AUTO_RETRY_PAUSE_TICKS - this.pausedTicks);
      if (!shouldAutoRetryPause(this.pausedTicks)) {
         int remainingSeconds = (remaining + 19) / 20;
         this.detail = this.pauseReason + "，暂停中；约 " + remainingSeconds + " 秒后自动重试";
         return;
      }

      this.consecutiveAutoRetryAttempts++;
      if (!safeInventoryStateForAutoRetry(mc)) {
         this.pausedTicks = 0;
         boolean exhausted = autoRetryAttemptsExhausted(this.consecutiveAutoRetryAttempts);
         this.pauseAutoRetryAllowed = !exhausted;
         this.detail = exhausted
            ? this.pauseReason + "，连续 10 次自动恢复均被未关闭容器或鼠标持有物品阻止，已停止自动重试"
            : this.pauseReason
               + "，本次自动恢复被未关闭容器或鼠标持有物品阻止；30 秒后重试（"
               + this.consecutiveAutoRetryAttempts
               + "/"
               + MAX_CONSECUTIVE_AUTO_RETRIES
               + "）";
         TerraLevelerDiagnostics.log(
            "STATE",
            "auto-retry-blocked-unsafe-inventory reason="
               + this.pauseReason
               + " attempt="
               + this.consecutiveAutoRetryAttempts
               + "/"
               + MAX_CONSECUTIVE_AUTO_RETRIES
               + " exhausted="
               + exhausted
         );
         return;
      }

      ConstructionController.State next = normalizeTaskState(this.resumeState);
      String previousReason = this.pauseReason;
      this.recordAutoRetryProgressSnapshot();
      this.cancelOwnedWork(mc, "thirty-second-auto-retry");
      this.clearBudgetReliefSearch();
      this.clearBudgetReliefRejections();
      this.clearRouteFailures();
      this.routeRetryCooldownTicks = 0;
      this.pausedTicks = 0;
      this.pauseAutoRetryAllowed = false;
      this.pauseReason = null;
      this.state = next;
      this.resumeState = next;
      this.detail = "暂停满 30 秒，自动重新验证并尝试继续施工（"
         + this.consecutiveAutoRetryAttempts
         + "/"
         + MAX_CONSECUTIVE_AUTO_RETRIES
         + "）";
      TerraLevelerDiagnostics.log(
         "STATE",
         "auto-retry-after-pause reason="
            + previousReason
            + " resume="
            + next
            + " elapsedTicks="
            + AUTO_RETRY_PAUSE_TICKS
            + " attempt="
            + this.consecutiveAutoRetryAttempts
            + "/"
            + MAX_CONSECUTIVE_AUTO_RETRIES
      );
   }

   static boolean autoRetryAllowedForPause(String reason) {
      return reason != null
         && !reason.isBlank()
         && !reason.contains("用户请求")
         && !reason.contains("用户主动")
         && !reason.contains("确定性拓扑阻塞")
         && !reason.contains("确定性清场高差阻塞");
   }

   static boolean shouldAutoRetryPause(int pausedTicks) {
      return pausedTicks >= AUTO_RETRY_PAUSE_TICKS;
   }

   static boolean autoRetryAttemptsExhausted(int attempts) {
      return attempts >= MAX_CONSECUTIVE_AUTO_RETRIES;
   }

   private static boolean safeInventoryStateForAutoRetry(Minecraft mc) {
      return mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.inventoryMenu.getCarried().isEmpty();
   }

   private void recordAutoRetryProgressSnapshot() {
      this.autoRetryCompletedSnapshot = this.completed;
      this.autoRetryCleanupIndexSnapshot = this.scaffoldCleanupIndex;
      this.autoRetryScaffoldCountSnapshot = this.activeScaffoldCount();
   }

   private void resetAutoRetryStreakAfterProgress() {
      if (this.consecutiveAutoRetryAttempts > 0
         && (this.completed != this.autoRetryCompletedSnapshot
            || this.scaffoldCleanupIndex != this.autoRetryCleanupIndexSnapshot
            || this.activeScaffoldCount() < this.autoRetryScaffoldCountSnapshot)) {
         this.resetAutoRetryStreak();
      }
   }

   private void resetAutoRetryStreak() {
      this.consecutiveAutoRetryAttempts = 0;
      this.autoRetryCompletedSnapshot = -1L;
      this.autoRetryCleanupIndexSnapshot = -1;
      this.autoRetryScaffoldCountSnapshot = -1;
   }

   private void resetRuntime(Minecraft mc, String reason) {
      this.flushOwnedScaffoldLedger();
      if (this.state == ConstructionController.State.EATING && mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) {
         mc.gameMode.releaseUsingItem(mc.player);
      }

      this.releaseFoodControls(mc, "runtime-reset-" + reason);
      this.releaseCombatWeapon(mc, "runtime-reset-" + reason);
      this.toolBoxTransaction.cancel(mc, "runtime-reset-" + reason);
      this.toolBoxTransaction.reset();
      this.actions.cancel(mc, reason);
      this.batchBreaks.reset(reason);
      this.completionJunkDiscardTicks = 0;
      this.deferredJunkDiscardItem = null;
      this.obsoleteScaffoldTarget = null;
      this.obsoleteScaffoldRejected.clear();
      this.obsoleteSweepExhaustedFeet = null;
      this.obsoleteScaffoldScanCooldown = 0;
      this.rejectedScaffoldPlacements.clear();
      this.consecutiveScaffoldPlacementFailures = 0;
      this.clusterPillarRejected.clear();
      this.parkedCleanupTargets.clear();
      this.leftBehindScaffolds.clear();
      this.pillarBlockersPrioritized.clear();
      this.strandedEmergencyScaffoldDebt = 0;
      this.strandedEmergencyStart = null;
      this.skyBucketTask = null;
      this.baseScaffoldBudget = -1;
      this.parkedScaffoldAllowance = 0;
      this.lowestFlattenWorkY = Integer.MAX_VALUE;
      this.climbReliefTask = null;
      this.climbReliefActive = -1;
      this.pillarFirstTask = null;
      this.strandedDescentTask = null;
      this.strandedDescentFailedStart = null;
      this.strandedDescentFailedCount = -1;
      this.parkedCleanupRetries = 0;
      this.pillarSideTarget = null;
      this.pillarSideSkipped.clear();
      this.deferredFluidZone.clear();
      this.abandonedFluidZone.clear();
      this.lastPrefillReplanTargets = Set.of();
      this.lastFinalReplanTargets = Set.of();
      this.stuckWatchTarget = null;
      this.stuckWatchTicks = 0;
      this.stuckDeferredTargets.clear();
      this.fluidPlacementRotations = 0;
      this.deferredFluidRevisit = false;
      AutoNavigator.release(mc);
      this.clearRoute();
      this.clearBreak();
      this.clearFluidTask();
      this.unloadedTarget = null;
      this.loadingRelayAttempts = 0;
      this.failedLoadingRelays.clear();
      this.scaffoldPositions.clear();
      this.scaffoldBlocks.clear();
      this.submittedScaffoldBlocks.clear();
      this.inheritedScaffolds.clear();
      this.budgetReliefExhaustedTick = Long.MIN_VALUE;
      this.budgetReliefExhaustedCount = -1;
      this.budgetReliefExhaustedStart = null;
      this.scaffoldJobKey = null;
      this.scaffoldCleanupTargets = List.of();
      this.scaffoldCleanupClusters = Map.of();
      this.scaffoldCleanupIndex = 0;
      this.flattenFinalCleanupPass = 0;
      this.flattenCleanupRouteScaffolds.clear();
      this.flattenCleanupRouteRetireTarget = null;
      this.flattenCleanupRouteReturnAnchor = null;
      this.flattenCleanupRouteTask = null;
      this.flattenCleanupRouteReturnPending = false;
      this.routeIsFlattenCleanupReturn = false;
      this.flattenCleanupOutboundRoute.clear();
      this.flattenCleanupExcursionDeferredTargets.clear();
      this.flattenCleanupReservedScaffoldPeak = 0;
      this.routeReservedScaffoldBudget = 0;
      this.resetFlattenCleanupTopologyPlanning();
      this.clearBudgetReliefRejections();
      this.budgetReliefScaffoldTarget = null;
      this.clearBudgetReliefSearch();
      this.clearRollingScaffoldSwap();
      this.rollingScaffoldOverflowTarget = null;
      this.rollingScaffoldOverflowWasPlayerSupport = false;
      this.clearUncommittedWorkRoute();
      this.scaffoldsPlaced = 0;
      this.clearFlattenPillar();
      this.clearFinalScaffoldPillar();
      this.resetFlattenEscape();
      this.clearRouteFailures();
      this.actionCooldownTicks = 0;
      this.fluidSnapshotSettleTicks = 0;
      this.routeRetryCooldownTicks = 0;
      this.resetWaterRecovery();
      this.fallRecoveryTicks = 0;
      this.clearEatingRuntime();
      this.clearMaterialUnpack();
      this.toolBoxReturnState = ConstructionController.State.EXCAVATING;
      this.toolBoxReturnPurpose = ConstructionController.BreakPurpose.NONE;
      this.toolBoxTarget = null;
      this.dirtPickupCooldownTicks = 0;
      this.materialStorageCooldownTicks = 0;
      this.jobPickupItems = List.of(Blocks.DIRT.asItem());
      this.jobPickupMaterialFingerprint = "";
      this.inventoryDiscardSettleTicks = 0;
      this.unexpectedPickupRevision = 0L;
      this.inventoryDiscardObservedPickupRevision = -1L;
      this.inventoryDiscardStableTicks = 0;
      this.inventoryDiscardTransaction = null;
      this.pendingUserRequest = ConstructionController.PendingUserRequest.NONE;
      if (this.directedItemDiscardTransaction != null) {
         this.directedItemDiscardTransaction.cancel(mc);
         this.directedItemDiscardTransaction = null;
      }
      this.retainedInventoryBaseline.clear();
      this.pendingUnexpectedPickupDiscard.clear();
      this.pendingUnexpectedPickupGraceUntil.clear();
      this.pendingUnexpectedPickupObserved.clear();
      this.inventoryDiscardSplitBlockedItems.clear();
      this.confirmedPickupEntityIds.clear();
      this.flattenPlacementMaterial = null;
      this.clearHotbarMaterialRetry();
      this.pausedTicks = 0;
      this.pauseAutoRetryAllowed = false;
      this.pauseWaitsForHealth = false;
      this.resetAutoRetryStreak();
      this.pauseReason = null;
      this.deferredUnsupportedFluidTargets.clear();
      this.deferredRouteTargets.clear();
      this.deferredFoliageTargets.clear();
      this.promotedFoliageRoots.clear();
      this.foliageAccessExhausted.clear();
      this.flattenInteriorFoliageTargets.clear();
      this.flattenFoliageCleanup = false;
      this.flattenDirtHarvesting = false;
      this.flattenDirtHarvestGoal = 0;
      this.flattenDirtHarvestExpected = 0;
      this.flattenDirtPickupSettleTicks = 0;
      this.combatReturnAnchor = null;
      this.returningFromCombat = false;
      this.nearbyDefenseCooldownUntilTick = 0L;
      this.placementBlockerTarget = null;
      this.placementBlockerTicks = 0;
      this.blockedPlacementsDeferred.clear();
      this.fillLookaheadTried.clear();
      this.fillPlacedHere = 0;
      this.excavationReachPromotions = 0;
   }

   private void cancelOwnedWork(Minecraft mc, String reason) {
      this.actions.cancel(mc, reason);
      AutoNavigator.release(mc);
      this.clearRoute();
      this.clearBreak();
   }

   private void cancelMotionAndRoute(Minecraft mc, String reason) {
      this.actions.cancel(mc, reason);
      AutoNavigator.release(mc);
      this.clearRoute();
   }

   private void clearRoute() {
      this.route = List.of();
      this.routeIndex = 0;
      this.routeReservedScaffoldBudget = 0;
      this.routeTaskKey = null;
      this.routeRequiresTaskValidation = false;
      this.routeNeedsContinuation = false;
      this.routeIsMaterialPickupDetour = false;
      this.routeIsFlattenCleanupReturn = false;
      this.bridgePlacementTarget = null;
      this.bridgePlacementItem = null;
      this.bridgeRecenterTicks = 0;
      this.routeNoProgressTicks = 0;
      this.clearRouteBlockerCandidate();
      this.routeCompletionDeferredTicks = 0;
      this.lastRouteDistance = Double.POSITIVE_INFINITY;
      this.waterBucketFallLanding = null;
      this.waterBucketFallTicks = 0;
      this.waterBucketFallAimTicks = 0;
      this.waterBucketFallPickupAttempts = 0;
      this.waterBucketFallPlacementRequested = false;
      this.waterBucketFallWaterConfirmed = false;
   }

   private void clearBreak() {
      this.breakPurpose = ConstructionController.BreakPurpose.NONE;
      this.breakTarget = null;
      this.verticalRecoverySupportDrop = null;
      this.scaffoldCleanupDrop = null;
      this.fallRecoveryScaffoldCleanupTarget = null;
      this.fallRecoveryScaffoldCleanupDrop = null;
      this.flattenCleanupFallStart = null;
      this.flattenCleanupFallWasRouteReturn = false;
      this.flattenCleanupRebaseAfterFall = false;
   }

   private void repositionInterruptedBreak(Minecraft mc, TerraLevelerConfig config, ConstructionController.State returnState, String reason) {
      BlockPos interruptedTarget = this.breakTarget;
      String blockage = this.actions.blockage();
      this.clearBreak();
      if (interruptedTarget == null) {
         this.pause(mc, "破坏事务要求重新站位，但原目标已经丢失");
      } else {
         TerraLevelerDiagnostics.log("ACTION", "break-reposition target=" + interruptedTarget.toShortString() + " return=" + returnState + " reason=" + blockage);
         this.beginWorkRoute(mc, interruptedTarget, interruptedTarget, ConstructionController.WorkAction.BREAK, returnState, config, reason);
      }
   }

   private void clearFluidTask() {
      this.flattenFluidTransaction = null;
      this.fluidActionIndex = 0;
      this.fluidVerifiedTicks = 0;
      this.fluidReflowAttempts = 0;
      this.fluidAdvancesExcavation = false;
   }

   private void resetWaterRecovery() {
      this.waterPhase = ConstructionController.WaterPhase.ASCEND;
      this.waterExit = null;
      this.waterWaypoints = List.of();
      this.waterWaypointIndex = 0;
      this.waterRecoveryTicks = 0;
      this.waterNoProgressTicks = 0;
      this.waterRouteNoProgressTicks = 0;
      this.waterReplanCooldown = 0;
      this.waterBestMetric = Double.POSITIVE_INFINITY;
      this.waterHighestY = Double.NEGATIVE_INFINITY;
      this.waterDryTicks = 0;
      this.waterDryUnstableTicks = 0;
   }

   private boolean waitForActionCooldown(Minecraft mc) {
      if (this.actions.isIdle() && this.actionCooldownTicks > 0) {
         this.actionCooldownTicks--;
         AutoNavigator.release(mc);
         this.detail = "按设置等待下一次服务器操作（剩余 " + this.actionCooldownTicks + " 刻）";
         return true;
      } else {
         return false;
      }
   }

   private void startActionCooldown(TerraLevelerConfig config) {
      this.actionCooldownTicks = Math.max(0, config.operationDelayTicks);
   }

   private boolean stablePlayerStand(Minecraft mc) {
      return PlayerPhysicalState.stablySupported(mc);
   }

   private static boolean playerOnStepCorridor(BlockPos player, SafeRoutePlanner.RouteStep step) {
      if (!player.equals(step.origin()) && !player.equals(step.position())) {
         if ((
               step.type() == SafeRoutePlanner.StepType.STEP_DOWN
                  || step.type() == SafeRoutePlanner.StepType.WATER_BUCKET_FALL
                  || step.type() == SafeRoutePlanner.StepType.STEP_UP
                  || step.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP
            )
            && step.origin() != null) {
            boolean destinationColumn = player.getX() == step.position().getX() && player.getZ() == step.position().getZ();
            boolean sourceColumn = player.getX() == step.origin().getX() && player.getZ() == step.origin().getZ();
            int high = Math.max(step.origin().getY(), step.position().getY());
            int low = Math.min(step.origin().getY(), step.position().getY());
            return (sourceColumn || destinationColumn) && player.getY() >= low && player.getY() <= high;
         } else {
            return false;
         }
      } else {
         return true;
      }
   }

   private static boolean stablePlacementSupport(Minecraft mc, BlockPos support) {
      if (support == null) {
         return false;
      } else if (mc.level.hasChunkAt(support) && mc.level.getFluidState(support).isEmpty()) {
         BlockState state = mc.level.getBlockState(support);
         return !state.isAir() && !state.getCollisionShape(mc.level, support).isEmpty();
      } else {
         return false;
      }
   }

   private static BlockPos findFlattenSurfaceSupport(Minecraft mc, BlockPos target) {
      BlockPos best = null;
      double bestDistance = Double.POSITIVE_INFINITY;

      for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
         BlockPos candidate = target.relative(direction);
         if (stablePlacementSupport(mc, candidate)) {
            double distance = mc.player == null
               ? 0.0
               : mc.player.getEyePosition().distanceToSqr(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
            if (distance < bestDistance) {
               best = candidate.immutable();
               bestDistance = distance;
            }
         }
      }

      if (best != null) {
         return best;
      }

      BlockPos below = target.below();
      return stablePlacementSupport(mc, below) ? below.immutable() : null;
   }

   private boolean safeReach(Minecraft mc, BlockPos target) {
      return mc.player.getEyePosition().distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= 20.25;
   }

   private boolean isTrackedScaffoldBlock(Minecraft mc, BlockPos target) {
      if (target != null && this.scaffoldPositions.contains(target) && mc.level != null && mc.level.hasChunkAt(target)) {
         Block expected = this.scaffoldBlocks.get(target);
         return expected != null && scaffoldBlockMatches(expected, mc.level.getBlockState(target).getBlock());
      } else {
         return false;
      }
   }

   private boolean canPerformFromCurrentPlayer(Minecraft mc, BlockPos taskTarget, BlockPos interactionTarget, ConstructionController.WorkAction action) {
      return action == ConstructionController.WorkAction.BREAK
         ? this.safeReach(mc, taskTarget) && !this.isBlockSupportingPlayer(mc, taskTarget)
         : this.safeReach(mc, interactionTarget) && !ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), taskTarget);
   }

   private boolean isBlockSupportingPlayer(Minecraft mc, BlockPos target) {
      return PlayerPhysicalState.isSupportingBlock(mc, target);
   }

   private static double horizontalDistance(Minecraft mc, BlockPos target) {
      double dx = target.getX() + 0.5 - mc.player.getX();
      double dz = target.getZ() + 0.5 - mc.player.getZ();
      return Math.sqrt(dx * dx + dz * dz);
   }

   private static boolean reachedWaterWaypoint(Minecraft mc, BlockPos target) {
      return horizontalDistance(mc, target) < 0.55 && Math.abs(mc.player.getY() - target.getY()) < 0.9;
   }

   private static Item item(String id) {
      Identifier parsed = Identifier.tryParse(id);
      return parsed == null ? null : (Item)BuiltInRegistries.ITEM.getValue(parsed);
   }

   private static BlockItem blockItem(String id) {
      return item(id) instanceof BlockItem blockItem ? blockItem : null;
   }

   private static boolean validScaffold(Item item) {
      if (item instanceof BlockItem blockItem && !(blockItem.getBlock() instanceof FallingBlock)) {
         BlockState state = blockItem.getBlock().defaultBlockState();
         return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
      } else {
         return false;
      }
   }

   static boolean isFlattenSurfaceMaterial(Item item) {
      return item == Blocks.DIRT.asItem() || item == Blocks.GRASS_BLOCK.asItem();
   }

   static int flattenSurfaceMaterialPriority(int looseGrass, int looseDirt, int boxedDirt, int boxedGrass) {
      if (looseGrass > 0) {
         return 0;
      } else if (looseDirt > 0) {
         return 1;
      } else if (boxedDirt > 0) {
         return 1;
      } else {
         return boxedGrass > 0 ? 0 : 1;
      }
   }

   private static Item preferredFlattenSurfaceMaterial(Minecraft mc) {
      Item dirt = Blocks.DIRT.asItem();
      Item grass = Blocks.GRASS_BLOCK.asItem();
      int priority = flattenSurfaceMaterialPriority(
         inventoryCount(mc, grass), inventoryCount(mc, dirt), containedInventoryCount(mc, dirt), containedInventoryCount(mc, grass)
      );
      return priority == 0 ? grass : dirt;
   }

   private static int flattenSurfaceInventoryCount(Minecraft mc) {
      return inventoryCount(mc, Blocks.DIRT.asItem()) + inventoryCount(mc, Blocks.GRASS_BLOCK.asItem());
   }

    private Item preferredScaffoldItem(Minecraft mc) {
       return this.preferredScaffoldItem(mc, 1);
    }

    private Item preferredScaffoldItem(Minecraft mc, int required) {
       int demand = Math.max(1, required);
       Item firstValid = null;
       Item partial = null;
       for (Item candidate : this.activePickupMaterialItems()) {
          if (!validScaffold(candidate)) continue;
          if (firstValid == null) firstValid = candidate;
          int supply = availableMaterialSupply(mc, candidate);
          if (supply >= demand) return candidate;
          if (supply > 0 && partial == null) partial = candidate;
       }
       return partial != null ? partial : firstValid;
    }

    static int scaffoldMaterialPriority(int cobblestoneSupply, int dirtSupply) {
       return scaffoldMaterialPriority(cobblestoneSupply, dirtSupply, 1);
    }

    static int scaffoldMaterialPriority(int cobblestoneSupply, int dirtSupply, int required) {
       int demand = Math.max(1, required);
       if (cobblestoneSupply >= demand) {
          return 0;
       }

       if (dirtSupply >= demand) {
          return 1;
       }

       if (cobblestoneSupply > 0) {
          return 0;
       }

       return dirtSupply > 0 ? 1 : 2;
   }

   private static int availableMaterialSupply(Minecraft mc, Item item) {
      return inventoryCount(mc, item) + containedInventoryCount(mc, item);
   }

   private void beginMaterialUnpack(Minecraft mc, ConstructionController.State returnState, Item requestedItem, int amount) {
      if (requestedItem == null) {
         this.pause(mc, "潜影盒补货请求缺少有效物品");
      } else if (mc.screen instanceof AbstractContainerScreen) {
         AutoNavigator.release(mc);
         this.detail = "请先关闭背包界面；TerraLeveler 不会在打开的背包界面上存取潜影盒";
      } else {
         Inventory inventory = mc.player.getInventory();
         ConstructionController.MaterialBoxCandidate candidate = findMaterialBoxCandidate(mc, requestedItem);
         int bestSlot = candidate == null ? -1 : candidate.slot();
         if (bestSlot < 0) {
            this.pause(mc, "背包里没有装着 " + materialName(requestedItem) + " 的潜影盒");
         } else {
            int extractionSlot = materialExtractionDestination(bestSlot, slot -> inventory.getItem(slot).isEmpty());
            if (extractionSlot < 0) {
               if (mc.player.containerMenu == mc.player.inventoryMenu
                  && mc.player.inventoryMenu.getCarried().isEmpty()
                  && this.droppableJunkSlot(mc, null) >= 0) {
                  AutoNavigator.release(mc);
                  // While a previous throw settles, wait instead of falling through to a pause.
                  this.detail = this.throwJunkStackForSpace(mc, "material-extraction")
                     ? "背包已满：先丢出一组施工杂物，腾出潜影盒补货用的空格"
                     : "背包已满：等待刚丢出的施工杂物离开背包后再潜影盒补货";
               } else if (!this.beginMaterialStorageForExtractionSpace(mc, returnState, requestedItem)) {
                  if (!this.maybeStoreInventoryOverflow(mc)) {
                     this.pause(mc, "从潜影盒取材料需要至少一个空背包格");
                  }
               }
            } else if (mc.player.containerMenu != mc.player.inventoryMenu) {
               this.pause(mc, "准备潜影盒补货时检测到其他容器界面仍在打开");
            } else if (!mc.player.inventoryMenu.getCarried().isEmpty()) {
               this.pause(mc, "准备潜影盒补货时鼠标上仍拿着物品");
            } else {
               this.cancelOwnedWork(mc, "material-unpack-start");
               int initialLoose = inventoryCount(mc, requestedItem);
               int wanted = Math.max(1, amount);
               if (!this.boxTransaction.begin(mc, ShulkerBoxMenuTransaction.Kind.EXTRACT, requestedItem, bestSlot, wanted, -1)) {
                  this.pause(mc, "无法打开潜影盒取出 " + materialName(requestedItem) + "：" + this.boxTransaction.failure());
                  return;
               }
               this.materialReturnState = returnState;
               this.materialOperation = ConstructionController.MaterialOperation.EXTRACT;
               this.materialRequestedItem = requestedItem;
               this.materialTicks = 0;
               this.materialFailureReason = null;
               this.materialStopRequested = false;
               this.materialResumeAfterRollback = false;
               this.state = ConstructionController.State.UNPACKING_MATERIAL;
               this.resumeState = returnState;
               this.detail = "打开潜影盒取出 " + materialName(requestedItem);
               TerraLevelerDiagnostics.log("MATERIAL", "box-extract-start boxSlot=" + bestSlot + " item=" + materialName(requestedItem)
                  + " contained=" + candidate.extractableItemCount() + " initialLoose=" + initialLoose + " wanted=" + wanted
                  + " return=" + returnState);
            }
         }
      }
   }

   private boolean beginMaterialStorageForExtractionSpace(Minecraft mc, ConstructionController.State returnState, Item requestedItem) {
      List<Item> candidates = new ArrayList<>();
      if (requestedItem != null) candidates.add(requestedItem);
      for (Item candidate : this.activePickupMaterialItems()) {
         if (!candidates.contains(candidate)) candidates.add(candidate);
      }

      for (Item material : candidates) {
         int[] counts = new int[Math.min(36, mc.player.getInventory().getContainerSize())];
         for (int slot = 0; slot < counts.length; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            counts[slot] = stack.is(material) ? stack.getCount() : 0;
         }

         int sourceSlot = materialDepositSource(0, counts, mc.player.getInventory().getSelectedSlot());
         if (sourceSlot < 0) continue;
         MaterialStorageBoxCandidate box = findMaterialStorageBoxCandidate(mc, material, counts[sourceSlot]);
         if (box != null && sourceSlot != box.slot()) {
            TerraLevelerDiagnostics.log(
               "MATERIAL",
               "extraction-space-store item=" + materialName(material)
                  + " itemSlot=" + sourceSlot
                  + " boxSlot=" + box.slot()
                  + " loose=" + inventoryCount(mc, material)
            );
            return this.beginMaterialStore(mc, returnState, material, sourceSlot, box);
         }
      }
      return false;
   }

   private void tickMaterialUnpack(Minecraft mc) {
      AutoNavigator.release(mc);
      this.materialTicks++;
      Item requestedItem = this.materialRequestedItem;
      if (requestedItem == null) {
         this.boxTransaction.cancel(mc, "lost-item");
         this.boxTransaction.reset();
         this.clearMaterialUnpack();
         this.pause(mc, "潜影盒事务丢失了目标物品");
         return;
      }
      // The player opened their inventory: the hidden box menu must not stay open under it.
      if (mc.screen instanceof AbstractContainerScreen) {
         this.beginMaterialRollback(mc, "潜影盒事务期间打开了背包界面，已关闭潜影盒");
         return;
      }
      ShulkerBoxMenuTransaction.Result result = this.boxTransaction.tick(mc);
      this.detail = this.boxTransaction.detail();
      if (result == ShulkerBoxMenuTransaction.Result.RUNNING) return;

      boolean store = this.materialOperation == ConstructionController.MaterialOperation.STORE;
      if (result == ShulkerBoxMenuTransaction.Result.FAILED) {
         String failure = this.boxTransaction.failure();
         if (this.boxTransaction.menuNeverOpened() && "menu-open-timeout".equals(failure)) {
            markQuickShulkerUnsupported();
         }
         this.boxTransaction.reset();
         this.finishMaterialRollback(mc, (store ? "潜影盒存放 " : "潜影盒取出 ") + materialName(requestedItem) + " 失败：" + failure);
         return;
      }

      int moved = this.boxTransaction.movedCount(mc);
      this.boxTransaction.reset();
      ConstructionController.State next = this.materialReturnState;
      if (!store && moved <= 0) {
         this.finishMaterialRollback(mc, "潜影盒已打开，但没有取出 " + materialName(requestedItem) + "（背包可能没有空位）");
         return;
      }
      TerraLevelerDiagnostics.log("MATERIAL", (store ? "box-store-complete" : "box-extract-complete") + " item=" + materialName(requestedItem)
         + " moved=" + moved + " loose=" + inventoryCount(mc, requestedItem) + " return=" + next);
      this.clearMaterialUnpack();
      if (store) {
         // Nothing could be stored (box full): do not reopen it every few ticks.
         this.materialStorageCooldownTicks = moved <= 0 ? 600 : this.storeAgainSoon(mc, requestedItem) ? 10 : 100;
      } else {
         // The extracted stacks are about to be used; do not judge them as overflow first.
         this.materialStorageCooldownTicks = Math.max(this.materialStorageCooldownTicks, EXTRACTION_STORE_COOLDOWN_TICKS);
      }
      this.state = next;
      this.resumeState = next;
      this.detail = store ? "已把 " + materialName(requestedItem) + " 存入潜影盒，继续施工" : "已从潜影盒取出 " + materialName(requestedItem) + "，继续原事务";
   }

   private void beginMaterialRollback(Minecraft mc, String reason) {
      this.materialFailureReason = reason;
      this.boxTransaction.cancel(mc, reason);
      this.boxTransaction.reset();
      this.finishMaterialRollback(mc, reason);
   }

   private boolean beginMaterialStore(
      Minecraft mc,
      ConstructionController.State returnState,
      Item storedItem,
      int itemSlot,
      ConstructionController.MaterialStorageBoxCandidate box,
      boolean required
   ) {
      int stack = itemSlot < 0 || mc.player == null ? 0 : mc.player.getInventory().getItem(itemSlot).getCount();
      return this.beginMaterialStore(mc, returnState, storedItem, itemSlot, box, required, stack);
   }

   private boolean beginMaterialStore(
      Minecraft mc, ConstructionController.State returnState, Item storedItem, int itemSlot, ConstructionController.MaterialStorageBoxCandidate box
   ) {
      return this.beginMaterialStore(mc, returnState, storedItem, itemSlot, box, false);
   }

   /** Stores at least the stack in {@code itemSlot}, up to {@code amount} items, by shift-clicking into the box. */
   private boolean beginMaterialStore(
      Minecraft mc,
      ConstructionController.State returnState,
      Item storedItem,
      int itemSlot,
      ConstructionController.MaterialStorageBoxCandidate box,
      boolean required,
      int amount
   ) {
      if (storedItem == null
         || itemSlot < 0
         || box == null
         || mc.player.containerMenu != mc.player.inventoryMenu
         || !mc.player.inventoryMenu.getCarried().isEmpty()) {
         return false;
      }

      if (mc.screen instanceof AbstractContainerScreen) {
         AutoNavigator.release(mc);
         this.detail = "请先关闭背包界面；TerraLeveler 不会在打开的背包界面上存取潜影盒";
         return false;
      }

      Inventory inventory = mc.player.getInventory();
      ItemStack itemStack = inventory.getItem(itemSlot);
      ItemStack boxStack = inventory.getItem(box.slot());
      if (!itemStack.is(storedItem) || itemStack.isEmpty() || !isShulkerBoxStack(boxStack)
         || !materialStorageCanEmptySource(box.capacity(), itemStack.getCount())) {
         return false;
      }
      this.cancelOwnedWork(mc, "material-store-start");
      int wanted = Math.max(itemStack.getCount(), amount);
      if (!this.boxTransaction.begin(mc, ShulkerBoxMenuTransaction.Kind.STORE, storedItem, box.slot(), wanted, inventory.getSelectedSlot())) {
         return false;
      }
      this.materialReturnState = returnState;
      this.materialOperation = ConstructionController.MaterialOperation.STORE;
      this.materialRequestedItem = storedItem;
      this.materialTicks = 0;
      this.materialFailureReason = null;
      this.materialStopRequested = false;
      this.materialStoreRequired = required;
      this.state = ConstructionController.State.UNPACKING_MATERIAL;
      this.resumeState = returnState;
      this.detail = "打开潜影盒存放 " + materialName(storedItem);
      TerraLevelerDiagnostics.log("MATERIAL", "box-store-start boxSlot=" + box.slot() + " itemSlot=" + itemSlot
         + " item=" + materialName(storedItem) + " stack=" + itemStack.getCount() + " wanted=" + wanted
         + " loose=" + inventoryCount(mc, storedItem) + " storedBefore=" + box.storedItemCount()
         + " capacity=" + box.capacity() + " required=" + required + " return=" + returnState);
      return true;
   }

   private void finishMaterialRollback(Minecraft mc, String reason) {
      boolean stopRequested = this.materialStopRequested;
      boolean resumeAfterEquivalentMaterial = this.materialResumeAfterRollback;
      boolean requiredStore = this.materialOperation == ConstructionController.MaterialOperation.STORE && this.materialStoreRequired;
      boolean resumeAfterOptionalStore = this.materialOperation == ConstructionController.MaterialOperation.STORE
         && !requiredStore
         && !reason.contains("用户请求");
      ConstructionController.State storeReturnState = this.materialReturnState;
      this.clearMaterialUnpack();
      if (stopRequested) {
         this.resetRuntime(mc, "safe-material-stop");
         this.state = ConstructionController.State.IDLE;
         this.detail = "潜影盒材料事务已回滚，施工已停止";
         TerraLevelerDiagnostics.log("STATE", "stop afterMaterialRollback=true reason=" + reason);
      } else if (resumeAfterEquivalentMaterial) {
         this.state = storeReturnState;
         this.resumeState = storeReturnState;
         this.detail = "潜影盒已安全归位，改用背包中的泥土/草方块继续填平";
         TerraLevelerDiagnostics.log("MATERIAL", "quick-shulker-extract-skipped reason=" + reason + " resume=" + storeReturnState + " surfaceMaterial=equivalent");
      } else if (resumeAfterOptionalStore) {
         this.materialStorageCooldownTicks = 600;
         this.state = storeReturnState;
         this.resumeState = storeReturnState;
         this.detail = "本次泥土潜影盒收纳已安全回滚，跳过收纳并继续施工";
         TerraLevelerDiagnostics.log(
            "MATERIAL", "quick-shulker-store-skipped reason=" + reason + " resume=" + storeReturnState + " retryCooldown=" + this.materialStorageCooldownTicks
         );
      } else if (requiredStore) {
         // The box is back in place and nothing was lost; overflow storage is retried
         // later. 0.8.71 paused 30 seconds here every time a store was interrupted.
         this.materialStorageCooldownTicks = 200;
         this.state = storeReturnState;
         this.resumeState = storeReturnState;
         this.detail = "本次超额泥土收纳已安全回滚，稍后重试收纳并继续施工";
         TerraLevelerDiagnostics.log(
            "MATERIAL", "quick-shulker-store-retry-later reason=" + reason + " resume=" + storeReturnState + " retryCooldown=" + this.materialStorageCooldownTicks
         );
      } else {
         this.pause(mc, reason);
      }
   }

   static int materialRestockAmount(int looseCount, int remainingUses, int maxStackSize) {
      int safeLoose = Math.max(0, looseCount);
      int safeRemaining = Math.max(0, remainingUses);
      int safeMaximum = Math.max(1, maxStackSize);
      return safeRemaining > safeLoose && safeLoose <= safeMaximum / 2 ? Math.max(1, Math.min(safeMaximum, safeRemaining) - safeLoose) : 0;
   }

    static int materialExtractionDestination(int boxSlot, IntPredicate emptySlot) {
       for (int slot = 9; slot < 36; slot++) {
          if (slot != boxSlot && emptySlot.test(slot)) {
             return slot;
          }
       }

       for (int slot = 0; slot < 9; slot++) {
         if (slot != boxSlot && emptySlot.test(slot)) {
            return slot;
         }
      }

      return -1;
   }

   static int materialReturnDestination(int preferredBoxSlot, int emptiedItemSlot, IntPredicate emptySlot) {
      if (preferredBoxSlot >= 0 && emptySlot.test(preferredBoxSlot)) {
         return preferredBoxSlot;
      }

      if (emptiedItemSlot >= 0 && emptySlot.test(emptiedItemSlot)) {
         return emptiedItemSlot;
      }

      for (int slot = 9; slot < 36; slot++) {
         if (emptySlot.test(slot)) {
            return slot;
         }
      }

      for (int slot = 0; slot < 9; slot++) {
         if (emptySlot.test(slot)) {
            return slot;
         }
      }

      return -1;
   }

   /**
    * The original box slot when its contents grew, otherwise a box holding exactly the old
    * contents plus the inserted amount. Other boxes of the same kind are never guessed.
    */
   static int autoReturnedStoreBoxSlot(int sourceSlot, int initialStored, int inserted, int[] storedCounts) {
      if (storedCounts == null || inserted <= 0) return -1;
      if (sourceSlot >= 0 && sourceSlot < storedCounts.length && storedCounts[sourceSlot] > initialStored) {
         return sourceSlot;
      }
      for (int slot = 0; slot < storedCounts.length; slot++) {
         if (storedCounts[slot] == initialStored + inserted) return slot;
      }
      return -1;
   }

   static int storedMaterialBoxSlot(int preferredSlot, int observedStored, int[] storedCounts) {
      if (storedCounts != null && observedStored > 0) {
         if (preferredSlot >= 0 && preferredSlot < storedCounts.length && storedCounts[preferredSlot] == observedStored) {
            return preferredSlot;
         }

         for (int slot = 0; slot < storedCounts.length; slot++) {
            if (storedCounts[slot] == observedStored) {
               return slot;
            }
         }

         int bestSlot = -1;
         int bestStored = Integer.MAX_VALUE;

         for (int slot = 0; slot < storedCounts.length; slot++) {
            int stored = storedCounts[slot];
            if (stored > observedStored && stored < bestStored) {
               bestStored = stored;
               bestSlot = slot;
            }
         }

         return bestSlot;
      } else {
         return -1;
      }
   }

   private static boolean hasPotentialMaterialBox(Minecraft mc, Item item) {
      if (quickShulkerUnsupported) return false;
      return findMaterialBoxCandidate(mc, item) != null;
   }

   private static ConstructionController.MaterialBoxCandidate findMaterialBoxCandidate(Minecraft mc, Item item) {
      if (item != null && mc.player != null) {
         Inventory inventory = mc.player.getInventory();
         ConstructionController.MaterialBoxCandidate best = null;

         for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isShulkerBoxStack(stack)) {
               int extractable = extractableContainedItemCount(stack, item);
               if (extractable > 0) {
                  ConstructionController.MaterialBoxCandidate candidate = new ConstructionController.MaterialBoxCandidate(slot, extractable);
                  if (best == null || extractable > best.extractableItemCount()) {
                     best = candidate;
                  }
               }
            }
         }

         return best;
      } else {
         return null;
      }
   }

   private static int containedInventoryCount(Minecraft mc, Item item) {
      if (item != null && mc.player != null) {
         int count = 0;
         Inventory inventory = mc.player.getInventory();

         for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            count += extractableContainedItemCount(inventory.getItem(slot), item);
         }

         return count;
      } else {
         return 0;
      }
   }

   private static ConstructionController.MaterialStorageBoxCandidate findMaterialStorageBoxCandidate(Minecraft mc, Item item, int requiredCapacity) {
      if (quickShulkerUnsupported) return null;
      if (item != null && mc.player != null) {
         Inventory inventory = mc.player.getInventory();
         ConstructionController.MaterialStorageBoxCandidate bestExisting = null;
         ConstructionController.MaterialStorageBoxCandidate firstEmpty = null;

         for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack box = inventory.getItem(slot);
            if (isShulkerBoxStack(box) && box.get(DataComponents.CONTAINER_LOOT) == null) {
               ItemContainerContents contents = (ItemContainerContents)box.get(DataComponents.CONTAINER);
               List<ItemStack> nonEmpty = contents == null ? List.of() : contents.nonEmptyItemCopyStream().toList();
               boolean customNamed = box.get(DataComponents.CUSTOM_NAME) != null;
               if (materialStorageBoxEligible(nonEmpty, item, customNamed)) {
                  int stored = nonEmpty.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
                  int capacity = materialStorageCapacity(nonEmpty, item, 27);
                  if (materialStorageCanEmptySource(capacity, requiredCapacity)) {
                     ConstructionController.MaterialStorageBoxCandidate candidate = new ConstructionController.MaterialStorageBoxCandidate(
                        slot, stored, capacity
                     );
                     if (stored > 0) {
                        if (bestExisting == null || stored > bestExisting.storedItemCount()) {
                           bestExisting = candidate;
                        }
                     } else if (nonEmpty.isEmpty() && firstEmpty == null) {
                        firstEmpty = candidate;
                     }
                  }
               }
            }
         }

         return bestExisting != null ? bestExisting : firstEmpty;
      } else {
         return null;
      }
   }

   static boolean materialStorageCanEmptySource(int capacity, int sourceStackCount) {
      return sourceStackCount > 0 && capacity >= sourceStackCount;
   }

   static boolean materialStorageBoxEligible(List<ItemStack> nonEmpty, Item item, boolean customNamed) {
      return item != null
         && nonEmpty != null
         && !nonEmpty.stream().anyMatch(stack -> !stack.is(item))
         && (!nonEmpty.isEmpty() || !customNamed);
   }

   private static int totalContainedItemCount(ItemStack containerStack, Item item) {
      if (isShulkerBoxStack(containerStack) && item != null && containerStack.get(DataComponents.CONTAINER_LOOT) == null) {
         ItemContainerContents contents = (ItemContainerContents)containerStack.get(DataComponents.CONTAINER);
         return contents == null ? 0 : contents.nonEmptyItemCopyStream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
      } else {
         return 0;
      }
   }

   static int materialStorageCapacity(List<ItemStack> nonEmpty, Item item, int containerSlots) {
      if (item != null && containerSlots > 0) {
         int maximum = Math.max(1, item.getDefaultMaxStackSize());
         int capacity = Math.max(0, containerSlots - nonEmpty.size()) * maximum;

         for (ItemStack stack : nonEmpty) {
            if (stack.is(item)) {
               capacity += Math.max(0, maximum - stack.getCount());
            }
         }

         return capacity;
      } else {
         return 0;
      }
   }

   /**
    * While filling, the stacks just taken out of the box for the surface stay loose (up to the
    * remaining fill and the extraction batch plus one stack) instead of going straight back in.
    */
   static int overflowStoreThreshold(int allowance, int remainingDemand, boolean fillPhase) {
      int threshold = overflowStoreThreshold(allowance, remainingDemand);
      if (!fillPhase || remainingDemand <= 0) return threshold;
      return Math.max(threshold, Math.max(0, allowance) + Math.min(remainingDemand, (FILL_EXTRACTION_STACKS + 1) * 64));
   }

   /** How much surface material one opening takes while filling: up to four stacks, never more than still needed. */
   static int fillExtractionAmount(int looseCount, int remainingUses, int maxStackSize) {
      int needed = Math.max(0, remainingUses) - Math.max(0, looseCount);
      return Math.max(1, Math.min(needed, FILL_EXTRACTION_STACKS * Math.max(1, maxStackSize)));
   }

   static int overflowStoreThreshold(int allowance, int remainingDemand) {
      int safeAllowance = Math.max(0, allowance);
      return remainingDemand > 0 ? safeAllowance + 64 : safeAllowance;
   }

   static int materialDepositSource(int reserve, int[] stackCounts) {
      return materialDepositSource(reserve, stackCounts, -1);
   }

   /** Like {@link #materialDepositSource(int, int[])}, but never picks {@code excludedSlot} (it still counts). */
   static int materialDepositSource(int reserve, int[] stackCounts, int excludedSlot) {
      int total = 0;

      for (int count : stackCounts) {
         total += Math.max(0, count);
      }

      int surplus = total - Math.max(0, reserve);
      if (surplus <= 0) {
         return -1;
      }

      int bestSlot = -1;
      int bestCount = 0;

      for (int pass = 0; pass < 2; pass++) {
         int start = pass == 0 ? Math.min(9, stackCounts.length) : 0;
         int end = pass == 0 ? stackCounts.length : Math.min(9, stackCounts.length);

         for (int slot = start; slot < end; slot++) {
            if (slot == excludedSlot) continue;
            int count = Math.max(0, stackCounts[slot]);
            if (count > bestCount && count <= surplus) {
               bestSlot = slot;
               bestCount = count;
            }
         }

         if (bestSlot >= 0) {
            return bestSlot;
         }
      }

      return -1;
   }

   static int dirtStorageReserve(boolean pickupCapacityBlocked) {
      return pickupCapacityBlocked ? 0 : 64;
   }

   static boolean materialPickupHasCapacity(boolean hasEmptySlot, int maxStackSize, int[] matchingStackCounts) {
      if (hasEmptySlot) {
         return true;
      }

      int maximum = Math.max(1, maxStackSize);

      for (int count : matchingStackCounts) {
         if (count > 0 && count < maximum) {
            return true;
         }
      }

      return false;
   }

   /** Items of this kind in a carried box; QuickShulker can take any of its stacks (0.9.08). */
   private static int extractableContainedItemCount(ItemStack containerStack, Item item) {
      return totalContainedItemCount(containerStack, item);
   }

   private static boolean isShulkerBoxStack(ItemStack stack) {
      return !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
   }

   private void clearMaterialUnpack() {
      this.materialReturnState = ConstructionController.State.EXCAVATING;
      this.materialOperation = ConstructionController.MaterialOperation.EXTRACT;
      if (this.boxTransaction.active()) this.boxTransaction.cancel(Minecraft.getInstance(), "material-cleared");
      this.boxTransaction.reset();
      this.materialRequestedItem = null;
      this.materialTicks = 0;
      this.materialFailureReason = null;
      this.materialStopRequested = false;
      this.materialResumeAfterRollback = false;
      this.materialStoreRequired = false;
   }

   private static String materialName(Item item) {
      return item == null ? "unknown" : BuiltInRegistries.ITEM.getKey(item).toString();
   }

   private static int hotbarCount(Minecraft mc, Item item) {
      if (item == null) {
         return 0;
      }

      int count = 0;

      for (int slot = 0; slot < 9; slot++) {
         ItemStack stack = mc.player.getInventory().getItem(slot);
         if (stack.is(item)) {
            count += stack.getCount();
         }
      }

      return count;
   }

   private static int inventoryCount(Minecraft mc, Item item) {
      if (item != null && mc.player != null) {
         int count = 0;
         Inventory inventory = mc.player.getInventory();

         for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
               count += stack.getCount();
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   static boolean transactionMaterialAvailable(int inventoryCount, int required) {
      return inventoryCount >= required;
   }

   private boolean waitForHotbarMaterialRetry(Minecraft mc, Item item) {
      if (this.hotbarMaterialRetryItem != item) {
         this.hotbarMaterialRetryItem = item;
         this.hotbarMaterialRetryTicks = 0;
      }

      this.hotbarMaterialRetryTicks++;
      boolean visibleContainer = mc.screen instanceof AbstractContainerScreen;
      if (this.hotbarMaterialRetryTicks == 1 || this.hotbarMaterialRetryTicks % 10 == 0) {
         TerraLevelerDiagnostics.log(
            "MATERIAL",
            "auto-hotbar-retry item="
               + materialName(item)
               + " tick="
               + this.hotbarMaterialRetryTicks
               + " hotbar="
               + hotbarCount(mc, item)
               + " inventory="
               + inventoryCount(mc, item)
               + " playerMenu="
               + (mc.player.containerMenu == mc.player.inventoryMenu)
               + " visibleContainer="
               + visibleContainer
         );
      }

      this.detail = visibleContainer
         ? "等待关闭当前容器后把 " + materialName(item) + " 换入快捷栏"
         : "等待库存同步后把 " + materialName(item) + " 换入快捷栏（" + this.hotbarMaterialRetryTicks + "/40）";
      return shouldWaitForHotbarMaterial(this.hotbarMaterialRetryTicks, visibleContainer);
   }

   static boolean shouldWaitForHotbarMaterial(int retryTicks, boolean visibleContainer) {
      return visibleContainer || retryTicks <= 40;
   }

   private void clearHotbarMaterialRetry() {
      this.hotbarMaterialRetryItem = null;
      this.hotbarMaterialRetryTicks = 0;
   }

   static int materialHotbarDestination(IntPredicate empty, IntPredicate sameItem) {
      return materialHotbarDestination(empty, sameItem, slot -> false);
   }

   /**
    * Hotbar slot that receives a construction material. Slots whose item the player
    * relies on (the apple selection tool, tools, shulker boxes) are displaced only
    * when every other slot already holds the material.
    */
   static int materialHotbarDestination(IntPredicate empty, IntPredicate sameItem, IntPredicate keep) {
      for (int slot = 0; slot < 9; slot++) {
         if (empty.test(slot)) {
            return slot;
         }
      }

      for (int slot = 8; slot >= 0; slot--) {
         if (!sameItem.test(slot) && !keep.test(slot)) {
            return slot;
         }
      }

      for (int slot = 8; slot >= 0; slot--) {
         if (!sameItem.test(slot)) {
            return slot;
         }
      }

      return 8;
   }

   private static boolean keepInHotbar(ItemStack stack) {
      if (stack.isEmpty()) return false;
      TerraLevelerConfig config = TerraLeveler.CONFIG;
      if (stack.is(Items.APPLE) && (config == null || config.appleSelectionEnabled)) return true;
      return stack.isDamageableItem() || isShulkerBoxStack(stack);
   }

   private boolean ensureHotbarMaterial(Minecraft mc, Item item, int required) {
      if (item != null && mc.player != null && mc.gameMode != null) {
         if (hotbarCount(mc, item) >= required) {
            return true;
         }

         Inventory inventory = mc.player.getInventory();
         int sourceInventorySlot = -1;
         int sourceCount = -1;

         for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && stack.getCount() > sourceCount) {
               sourceInventorySlot = slot;
               sourceCount = stack.getCount();
            }
         }

         if (sourceInventorySlot >= 0 && mc.player.containerMenu == mc.player.inventoryMenu) {
            int destinationHotbarSlot = materialHotbarDestination(
               slot -> inventory.getItem(slot).isEmpty(), slot -> inventory.getItem(slot).is(item), slot -> keepInHotbar(inventory.getItem(slot))
            );
            Slot menuSlot = null;

            for (Slot candidate : mc.player.inventoryMenu.slots) {
               if (candidate.container == inventory && candidate.getContainerSlot() == sourceInventorySlot) {
                  menuSlot = candidate;
                  break;
               }
            }

            if (menuSlot == null) {
               return false;
            }

            ItemStack displaced = inventory.getItem(destinationHotbarSlot).copy();
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index, destinationHotbarSlot, ContainerInput.SWAP, mc.player);
            boolean ready = hotbarCount(mc, item) >= required;
            TerraLevelerDiagnostics.log(
               "MATERIAL",
               "auto-hotbar item="
                  + item
                  + " required="
                  + required
                  + " sourceInventory="
                  + sourceInventorySlot
                  + " sourceCount="
                  + sourceCount
                  + " destinationHotbar="
                  + destinationHotbarSlot
                  + " displaced="
                  + displaced
                  + " hotbarAfter="
                  + hotbarCount(mc, item)
                  + " ready="
                  + ready
            );
            return ready;
         } else {
            TerraLevelerDiagnostics.log(
               "MATERIAL",
               "auto-hotbar-blocked item="
                  + item
                  + " required="
                  + required
                  + " hotbar="
                  + hotbarCount(mc, item)
                  + " inventory="
                  + inventoryCount(mc, item)
                  + " source="
                  + sourceInventorySlot
                  + " playerMenu="
                  + (mc.player.containerMenu == mc.player.inventoryMenu)
            );
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean ensureHotbarScaffold(Minecraft mc, Item scaffold) {
      if (scaffold == null || mc.player == null || mc.gameMode == null) {
         return false;
      }

      if (hotbarCount(mc, scaffold) > 0) {
         return true;
      }

      Inventory inventory = mc.player.getInventory();
      int sourceInventorySlot = -1;

      for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
         if (inventory.getItem(slot).is(scaffold)) {
            sourceInventorySlot = slot;
            break;
         }
      }

      if (sourceInventorySlot < 0) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD", "unavailable item=" + scaffold + " hotbar=0 inventory=0 budgetRemaining=" + (this.jobScaffoldBudget - this.activeScaffoldCount())
         );
         return false;
      }

      if (mc.player.containerMenu != mc.player.inventoryMenu) {
         TerraLevelerDiagnostics.log(
            "SCAFFOLD", "auto-hotbar-blocked item=" + scaffold + " reason=non-player-container-open menu=" + mc.player.containerMenu.getClass().getName()
         );
         return false;
      }

      int destinationHotbarSlot = materialHotbarDestination(
         slot -> inventory.getItem(slot).isEmpty(), slot -> inventory.getItem(slot).is(scaffold), slot -> keepInHotbar(inventory.getItem(slot))
      );

      Slot menuSlot = null;

      for (Slot candidate : mc.player.inventoryMenu.slots) {
         if (candidate.container == inventory && candidate.getContainerSlot() == sourceInventorySlot) {
            menuSlot = candidate;
            break;
         }
      }

      if (menuSlot == null) {
         TerraLevelerDiagnostics.log("SCAFFOLD", "auto-hotbar-blocked item=" + scaffold + " reason=inventory-menu-slot-not-found source=" + sourceInventorySlot);
         return false;
      } else {
         ItemStack displaced = inventory.getItem(destinationHotbarSlot).copy();
         mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index, destinationHotbarSlot, ContainerInput.SWAP, mc.player);
         boolean ready = hotbarCount(mc, scaffold) > 0;
         TerraLevelerDiagnostics.log(
            "SCAFFOLD",
            "auto-hotbar item="
               + scaffold
               + " sourceInventory="
               + sourceInventorySlot
               + " destinationHotbar="
               + destinationHotbarSlot
               + " displaced="
               + displaced
               + " ready="
               + ready
         );
         return ready;
      }
   }

   private static FluidCellKind fluidCell(Minecraft mc, BlockPos pos) {
      return mc.level != null && mc.level.hasChunkAt(pos) ? classifyFluidCell(mc.level.getBlockState(pos)) : FluidCellKind.UNLOADED;
   }

   static boolean flattenDrainPlacementSupport(FluidCellKind kind) {
      return kind == FluidCellKind.SOLID
         || kind == FluidCellKind.SAND
         || kind == FluidCellKind.WATERLOGGED_SOLID;
   }

   static FluidCellKind classifyFluidCell(BlockState state) {
      boolean hasFluid = !state.getFluidState().isEmpty();
      return classifyFluidCell(
         state.is(Blocks.SAND),
         state.isAir(),
         FallingBlock.isFree(state),
         FluidBlocks.isPureFluid(state),
         hasFluid,
         hasFluid && state.getFluidState().is(FluidTags.WATER),
         hasFluid && state.getFluidState().is(FluidTags.LAVA)
      );
   }

   static FluidCellKind classifyFluidCell(
      boolean sand, boolean air, boolean fallingBlockFree, boolean liquidBlock, boolean hasFluid, boolean water, boolean lava
   ) {
      if (sand) {
         return FluidCellKind.SAND;
      }

      if (air) {
         return FluidCellKind.AIR;
      }

      if (!liquidBlock && fallingBlockFree) {
         return FluidCellKind.FALL_THROUGH;
      }

      if (hasFluid) {
         if (water) {
            return liquidBlock ? FluidCellKind.WATER : FluidCellKind.WATERLOGGED_SOLID;
         } else {
            return lava ? FluidCellKind.LAVA : FluidCellKind.OTHER_FLUID;
         }
      } else {
         return fallingBlockFree ? FluidCellKind.FALL_THROUGH : FluidCellKind.SOLID;
      }
   }

   private String actionFailure(String fallback) {
      String failure = this.actions.failure();
      return failure != null && !failure.isEmpty() ? failure : fallback;
   }

   private WorkArea workArea() {
      return this.flattenPlan;
   }

   private ExcavationPlanner.CellKind classifyExcavation(Minecraft mc, BlockPos pos) {
      if (mc.level != null && mc.level.hasChunkAt(pos)) {
         BlockState state = mc.level.getBlockState(pos);
         if (state.is(Blocks.BEDROCK)) {
            return ExcavationPlanner.CellKind.BEDROCK;
         } else if (!state.getFluidState().isEmpty()) {
            return FluidBlocks.isPureFluid(state) ? ExcavationPlanner.CellKind.FLUID : ExcavationPlanner.CellKind.SOLID;
         } else {
            return state.isAir() ? ExcavationPlanner.CellKind.CLEAR_AIR : ExcavationPlanner.CellKind.SOLID;
         }
      } else {
         return ExcavationPlanner.CellKind.UNLOADED;
      }
   }

   private static String posText(BlockPos pos) {
      return pos == null ? "" : pos.toShortString();
   }

   private ConstructionController.State baseTaskState() {
      return switch (this.state) {
         case POSITIONING -> this.routeReturnState == ConstructionController.State.FLATTEN_PILLARING && this.finalScaffoldPillarTarget != null
            ? normalizeTaskState(this.flattenPillarReturnState)
            : normalizeTaskState(this.routeReturnState);
         case LOADING -> normalizeTaskState(this.loadingReturnState);
         default -> normalizeTaskState(this.state);
         case RECOVERING_WATER, RECOVERING_FALL -> normalizeTaskState(this.recoveryReturnState);
         case DEFENDING -> normalizeTaskState(this.defenseReturnState);
         case EATING -> normalizeTaskState(this.eatingReturnState);
         case UNPACKING_MATERIAL -> normalizeTaskState(this.materialReturnState);
         case SWAPPING_TOOL -> normalizeTaskState(this.toolBoxReturnState);
         case FLATTEN_PILLARING -> normalizeTaskState(this.flattenPillarReturnState);
         case PAUSED -> normalizeTaskState(this.resumeState);
      };
   }

   private static ConstructionController.State normalizeTaskState(ConstructionController.State value) {
      return switch (value) {
          case EXCAVATING, CONTAINING_FLUID, UNPACKING_MATERIAL, CLEANING_SCAFFOLDS, BUILDING_WALL -> value;
         default -> ConstructionController.State.EXCAVATING;
      };
   }

   private static boolean isRunningState(ConstructionController.State value) {
      return switch (value) {
          case EXCAVATING, POSITIONING, LOADING, CONTAINING_FLUID, RECOVERING_WATER, RECOVERING_FALL, DEFENDING, EATING, UNPACKING_MATERIAL, SWAPPING_TOOL, CLEANING_SCAFFOLDS, BUILDING_WALL, FLATTEN_PILLARING -> true;
         default -> false;
      };
   }

   private void diagnosticSnapshot(Minecraft mc) {
      if (++this.diagnosticTicks >= 20) {
         this.diagnosticTicks = 0;
         double horizontalMoved = Math.abs(mc.player.getX() - this.lastDiagnosticX) + Math.abs(mc.player.getZ() - this.lastDiagnosticZ);
         boolean expectsHorizontalMotion = this.state == ConstructionController.State.POSITIONING
            || this.state == ConstructionController.State.RECOVERING_WATER;
         if (expectsHorizontalMotion && horizontalMoved < 0.04) {
            this.horizontalStationarySnapshots++;
         } else {
            this.horizontalStationarySnapshots = 0;
         }

         this.lastDiagnosticX = mc.player.getX();
         this.lastDiagnosticZ = mc.player.getZ();
         BlockPos target = this.state == ConstructionController.State.BUILDING_WALL && this.wallIndex < this.wallTargets.size()
            ? this.wallTargets.get(this.wallIndex)
            : (this.excavationIndex < this.excavationTargets.size() ? this.excavationTargets.get(this.excavationIndex) : null);
         SafeRoutePlanner.RouteStep next = this.routeIndex < this.route.size() ? this.route.get(this.routeIndex) : null;
         TerraLevelerDiagnostics.log(
            expectsHorizontalMotion && this.horizontalStationarySnapshots >= 3 ? "STUCK" : "TICK",
            String.format(
               "state=%s player=%.3f,%.3f,%.3f velocity=%.3f,%.3f,%.3f onGround=%s shift=%s crouching=%s support=%s anchor=%s horizontalMoved=%.3f stationary=%d routeNoProgress=%d completionDeferred=%d target=%s excavation=%d/%d wall=%d/%d route=%d/%d next=%s break=%s:%s actionIdle=%s batchPending=%d water=%s underwater=%s waterPhase=%s freecam=%s scaffolds=%d fluidAction=%d/%d combatTarget=%d combatReason=%s health=%.1f hunger=%d eatingSlot=%d eatingItem=%s emptySlots=%d pendingPickups=%d detail=%s",
               this.state,
               mc.player.getX(),
               mc.player.getY(),
               mc.player.getZ(),
               mc.player.getDeltaMovement().x,
               mc.player.getDeltaMovement().y,
               mc.player.getDeltaMovement().z,
               mc.player.onGround(),
               mc.player.isShiftKeyDown(),
               mc.player.isCrouching(),
               PlayerPhysicalState.supportingBlocks(mc),
               PlayerPhysicalState.routeAnchor(mc).<String>map(Vec3i::toShortString).orElse("none"),
               horizontalMoved,
               this.horizontalStationarySnapshots,
               this.routeNoProgressTicks,
               this.routeCompletionDeferredTicks,
               target == null ? "null" : target.toShortString(),
               this.excavationIndex,
               this.excavationTargets.size(),
               this.wallIndex,
               this.wallTargets.size(),
               this.routeIndex,
               this.route.size(),
               next == null ? "null" : next.type() + ":" + next.position().toShortString(),
               this.breakPurpose,
               this.breakTarget,
               this.actions.isIdle(),
               this.batchBreaks.pendingCount(),
               mc.player.isInWater(),
               mc.player.isUnderWater(),
               this.waterPhase,
               AutoNavigator.isFreeCamera(mc),
               this.activeScaffoldCount(),
               this.fluidActionIndex,
               this.flattenFluidTransaction == null ? 0 : this.flattenFluidTransaction.placements().size(),
               this.defenseTargetId,
               this.defenseReason,
               mc.player.getHealth(),
               mc.player.getFoodData().getFoodLevel(),
               this.eatingSlot,
               this.eatingItem,
               emptyMainInventorySlots(mc.player.getInventory()),
               this.pendingUnexpectedPickupDiscard.size(),
               this.detail
            )
         );
      }
   }

   public ConstructionController.State state() {
      return this.state;
   }

   /** True while a construction plan is actively running (not idle, paused or complete). */
   public boolean isConstructionActive() {
      return isRunningState(this.state) && this.workArea() != null;
   }

   /**
    * TerraLeveler never presses movement key mappings (it drives velocity directly), so a
    * held movement or jump key means the player has taken over. Free-camera mods
    * use those keys for the camera, and inventory transactions must finish first.
    */
   private boolean manualMovementOverride(Minecraft mc) {
      if (mc.screen != null || AutoNavigator.isFreeCamera(mc)
         || this.state == ConstructionController.State.UNPACKING_MATERIAL
         || this.state == ConstructionController.State.SWAPPING_TOOL
         || this.directedItemDiscardTransaction != null) {
         return false;
      }
      return manualMovementKeysHeld(
         mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyLeft.isDown(),
         mc.options.keyRight.isDown(), mc.options.keyJump.isDown()
      );
   }

   static boolean manualMovementKeysHeld(boolean forward, boolean back, boolean left, boolean right, boolean jump) {
      return forward || back || left || right || jump;
   }

   public String detail() {
      return this.detail;
   }

   public long completed() {
      return this.completed;
   }

   public long total() {
      return this.flattenPlan == null ? 0L : this.flattenPlan.estimatedBlocks() + this.flattenPlan.estimatedFillBlocks();
   }

   enum BreakPurpose {
      NONE,
      EXCAVATION,
      FLUID_TEMPORARY,
      SCAFFOLD_CLEANUP,
      ROUTE_SCAFFOLD_CLEANUP,
      WALL_OBSTRUCTION,
      FLATTEN_ESCAPE,
      VERTICAL_RECOVERY_SUPPORT;
   }

   private record DeferredReplacement(int index, BlockPos pos, int steps) {
   }

   private record PendingFlattenCleanupReplan(
      BlockPos taskKey,
      List<BlockPos> goals,
      ConstructionController.State returnState,
      String reason,
      boolean validateTaskAtGoal,
      Set<BlockPos> forbiddenScaffoldPlacements,
      int topologyReplanAttempt,
      BlockPos start,
      int activeScaffoldCount
   ) {
   }

   private record FlattenCleanupTopologyBlock(BlockPos start, int activeScaffoldCount) {
      private boolean matches(BlockPos currentStart, int currentActiveScaffoldCount) {
         return sameFlattenCleanupTopologyFailureState(
            this.start, this.activeScaffoldCount, currentStart, currentActiveScaffoldCount
         );
      }
   }

   private record MaterialBoxCandidate(int slot, int extractableItemCount) {
   }

   private enum MaterialOperation {
      EXTRACT,
      STORE;
   }

   private record MaterialStorageBoxCandidate(int slot, int storedItemCount, int capacity) {
   }

   private record NearbyPickup(Item item, ItemEntity entity, BlockPos goal, List<SafeRoutePlanner.RouteStep> steps) {
   }

   private static final class RouteSearchBudget {
      private int remaining;
      private int used;

      private RouteSearchBudget(int maximum) {
         this.remaining = maximum;
      }

      private boolean tryUse() {
         if (this.remaining <= 0) {
            return false;
         }

         this.remaining--;
         this.used++;
         return true;
      }

      private int used() {
         return this.used;
      }
   }

   public enum State {
      IDLE,
      EXCAVATING,
      POSITIONING,
      LOADING,
      CONTAINING_FLUID,
      RECOVERING_WATER,
      RECOVERING_FALL,
      DEFENDING,
      EATING,
      UNPACKING_MATERIAL,
      SWAPPING_TOOL,
      CLEANING_SCAFFOLDS,
      BUILDING_WALL,
      FLATTEN_PILLARING,
      PAUSED,
      COMPLETE;
   }

   private enum WaterPhase {
      ASCEND,
      FIND_DRY_EXIT,
      SWIM_TO_EXIT,
      DRY_CONFIRM;
   }

   private enum FinalScaffoldPillarPhase {
      NONE,
      ASCENDING,
      BREAKING_TARGET,
      DESCENDING
   }

   private enum WorkAction {
      BREAK,
      PLACE;
   }

   private enum PendingUserRequest {
      NONE,
      TOGGLE,
      STOP
   }

   enum PauseKind {
      AUTO_RETRY,
      MANUAL_RESUME,
      WAIT_FOR_HEALTH
   }
}
