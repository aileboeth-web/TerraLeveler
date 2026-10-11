package cn.terraleveler;

import net.minecraft.core.BlockPos;

/** Finds the first loaded, non-hazardous landing below a support that will be mined. */
final class DestructiveSupportDropPlanner {
   private static final int MAX_SAFE_SOLID_DROP = 3;
   static final float MINIMUM_REMAINING_HEALTH = 6.0F;

   enum CellKind {
      OPEN,
      SOLID,
      WATER,
      HAZARDOUS_FLUID,
      /** Fire, magma, campfire, cactus, berry bush, cobweb, powder snow or dripstone in the fall column. */
      HAZARDOUS_BLOCK,
      UNLOADED
   }

   enum Status {
      SAFE_SOLID,
      SAFE_WATER,
      FALL_DAMAGE,
      UNLOADED,
      HAZARDOUS_FLUID,
      HAZARDOUS_BLOCK,
      VOID
   }

   interface Environment {
      CellKind cell(BlockPos position);

      int minimumY();
   }

   record Result(Status status, BlockPos landing, BlockPos conflict, int dropDistance) {
      boolean allowed() {
         return this.status == Status.SAFE_SOLID || this.status == Status.SAFE_WATER;
      }
   }

   private DestructiveSupportDropPlanner() {
   }

   static Result findLanding(Environment environment, BlockPos support) {
      return findLanding(environment, support, MAX_SAFE_SOLID_DROP);
   }

   static Result findLanding(Environment environment, BlockPos support, int maximumSafeSolidDrop) {
      int startFeetY = support.getY() + 1;

      for (int y = support.getY() - 1; y >= environment.minimumY(); y--) {
         BlockPos probe = new BlockPos(support.getX(), y, support.getZ());
         CellKind cell = environment.cell(probe);
         if (cell == CellKind.UNLOADED) {
            return rejected(Status.UNLOADED, probe);
         }

         if (cell == CellKind.HAZARDOUS_FLUID) {
            return rejected(Status.HAZARDOUS_FLUID, probe);
         }

         if (cell == CellKind.HAZARDOUS_BLOCK) {
            return rejected(Status.HAZARDOUS_BLOCK, probe);
         }

         if (cell == CellKind.WATER) {
            return new Result(Status.SAFE_WATER, probe.immutable(), null, startFeetY - probe.getY());
         }

         if (cell == CellKind.SOLID) {
            BlockPos landingFeet = probe.above().immutable();
            int dropDistance = startFeetY - landingFeet.getY();
            if (dropDistance > Math.max(MAX_SAFE_SOLID_DROP, maximumSafeSolidDrop)) {
               return new Result(Status.FALL_DAMAGE, landingFeet, probe.immutable(), dropDistance);
            }
            return new Result(Status.SAFE_SOLID, landingFeet, null, dropDistance);
         }
      }

      return rejected(Status.VOID, new BlockPos(support.getX(), environment.minimumY(), support.getZ()));
   }

   /**
    * Longest deliberate solid drop that still leaves {@link #MINIMUM_REMAINING_HEALTH}.
    * Fall damage is ceil(fallDistance - 3) and a real fall is slightly longer than
    * the block delta, so one extra point is kept back for rounding. The old budget
    * (health + 2) left the player on a single point of health or killed them.
    */
   static int maximumSurvivableSolidDrop(float health, float absorption) {
      float spare = Math.max(0.0F, health) + Math.max(0.0F, absorption) - MINIMUM_REMAINING_HEALTH;
      int extraDamage = (int)Math.floor(spare) - 1;
      return MAX_SAFE_SOLID_DROP + Math.max(0, extraDamage);
   }

   private static Result rejected(Status status, BlockPos conflict) {
      return new Result(status, null, conflict.immutable(), -1);
   }
}
