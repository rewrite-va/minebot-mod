package minebot.mod.pathfinding;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Cost model for A*, ported from minebot's earlier Python pathfinding port
 * (pure-protocol-backend branch, itself a port of
 * mineflayer-pathfinder@2.4.5's lib/movements.js) -- walk/climb/parkour
 * moves only. Every digging and block-placing branch from the original is
 * dropped: minebot-mod has no digging or block-placement commands
 * implemented at all yet, so those moves could never actually be executed
 * even if the search found a path using them. Entity-avoidance cost
 * weighting and scaffolding-item tracking are also dropped for the same
 * reason as before -- no inventory/entity-list use for this yet.
 *
 * Block classification queries the real live ClientLevel directly
 * (BlockState.isAir()/getFluidState()/isCollisionShapeFullBlock()/
 * BlockTags.CLIMBABLE) rather than a pre-generated registry dump, since
 * running inside the actual client means this data already exists to
 * query -- no extraction step needed the way the from-scratch Python
 * protocol implementation required (see block_registry_775.json's
 * generation story on pure-protocol-backend for why that was ever
 * necessary in the first place).
 *
 * Every block query landing in a chunk that isn't loaded is treated as
 * unsafe/non-physical/unknown rather than optimistically guessed either
 * way -- same conservative default as the earlier Python port: guessing
 * wrong in either direction could walk the bot off a ledge just as easily
 * as make it think open space is a wall, and "don't route through the
 * unknown" is the safe default for a pathfinder whose whole point is not
 * falling through unseen gaps.
 */
public final class Movements {
    private static final int[][] CARDINAL_DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] DIAGONAL_DIRECTIONS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    private static final double BLOCKED = 100.0; // movements.js's "can't move here" cost sentinel
    private static final double MAX_STEP_HEIGHT = 1.2; // movements.js's hardcoded jump-height cap (blocks)

    private final ClientLevel level;
    public boolean allowParkour = true;
    public boolean allowSprinting = true;

    public Movements(final ClientLevel level) {
        this.level = level;
    }

    public BlockInfo getBlock(final int originX, final int originY, final int originZ, final int dx, final int dy, final int dz) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        BlockPos pos = new BlockPos(x, y, z);

        if (!level.isLoaded(pos)) {
            return new BlockInfo(x, y, z, false, false, false, false, false);
        }

        BlockState state = level.getBlockState(pos);
        boolean isAir = state.isAir();
        boolean isLiquid = !state.getFluidState().isEmpty();
        boolean isLadder = state.getBlock().builtInRegistryHolder().is(BlockTags.CLIMBABLE);
        boolean isSolid = state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

        // movements.js: b.safe = (boundingBox === 'empty' || climbable || carpet) && !avoid.
        // Liquids have no collision box in vanilla (you can swim through
        // them), so they count as "empty"/safe too -- confirmed against
        // getLandingBlock's `blockLand.liquid && blockLand.safe` check on
        // the earlier Python port, which would be dead code otherwise.
        boolean safe = isAir || isLadder || isLiquid;

        return new BlockInfo(x, y, z, true, safe, isSolid, isLiquid, isLadder);
    }

    private BlockInfo getBlock(final BlockInfo origin, final int dx, final int dy, final int dz) {
        return getBlock(origin.x, origin.y, origin.z, dx, dy, dz);
    }

    private BlockInfo getBlock(final Move origin, final int dx, final int dy, final int dz) {
        return getBlock(origin.x, origin.y, origin.z, dx, dy, dz);
    }

    private static double safeOrBlocked(final BlockInfo block) {
        if (!block.known) {
            return BLOCKED;
        }
        return block.safe ? 0.0 : BLOCKED;
    }

    public Move getMoveForward(final Move node, final int dx, final int dz) {
        BlockInfo blockB = getBlock(node, dx, 1, dz);
        BlockInfo blockC = getBlock(node, dx, 0, dz);
        BlockInfo blockD = getBlock(node, dx, -1, dz);

        double cost = 1.0;

        if (!blockD.physical && !blockC.liquid) {
            return null; // would need to place a block to fill the gap -- can't dig/place
        }

        cost += safeOrBlocked(blockB);
        if (cost > BLOCKED) return null;
        cost += safeOrBlocked(blockC);
        if (cost > BLOCKED) return null;

        if (getBlock(node, 0, 0, 0).liquid) {
            cost += 1.0; // liquidCost
        }

        return new Move(blockC.x, blockC.y, blockC.z, cost);
    }

    public Move getMoveJumpUp(final Move node, final int dx, final int dz) {
        BlockInfo blockA = getBlock(node, 0, 2, 0);
        BlockInfo blockH = getBlock(node, dx, 2, dz);
        BlockInfo blockB = getBlock(node, dx, 1, dz);
        BlockInfo blockC = getBlock(node, dx, 0, dz);

        double cost = 2.0; // move + jump

        if (!blockC.physical) {
            return null; // would need to place a block to stand on -- can't place
        }

        BlockInfo block0 = getBlock(node, 0, -1, 0);
        if (blockC.height() - block0.height() > MAX_STEP_HEIGHT) {
            return null; // too high to jump
        }

        cost += safeOrBlocked(blockA);
        if (cost > BLOCKED) return null;
        cost += safeOrBlocked(blockH);
        if (cost > BLOCKED) return null;
        cost += safeOrBlocked(blockB);
        if (cost > BLOCKED) return null;

        return new Move(blockB.x, blockB.y, blockB.z, cost);
    }

    public Move getMoveDiagonal(final Move node, final int dx, final int dz) {
        double cost = Math.sqrt(2);

        BlockInfo blockC = getBlock(node, dx, 0, dz); // landing block, or the block we'd stand on if stepping up
        int y = blockC.physical ? 1 : 0;

        BlockInfo block0 = getBlock(node, 0, -1, 0);

        double cost1 = 0.0;
        BlockInfo blockB1 = getBlock(node, 0, y + 1, dz);
        BlockInfo blockC1 = getBlock(node, 0, y, dz);
        BlockInfo blockD1 = getBlock(node, 0, y - 1, dz);
        cost1 += safeOrBlocked(blockB1);
        cost1 += safeOrBlocked(blockC1);
        if (blockD1.height() - block0.height() > MAX_STEP_HEIGHT) {
            cost1 += safeOrBlocked(blockD1);
        }

        double cost2 = 0.0;
        BlockInfo blockB2 = getBlock(node, dx, y + 1, 0);
        BlockInfo blockC2 = getBlock(node, dx, y, 0);
        BlockInfo blockD2 = getBlock(node, dx, y - 1, 0);
        cost2 += safeOrBlocked(blockB2);
        cost2 += safeOrBlocked(blockC2);
        if (blockD2.height() - block0.height() > MAX_STEP_HEIGHT) {
            cost2 += safeOrBlocked(blockD2);
        }

        cost += Math.min(cost1, cost2);
        if (cost > BLOCKED) return null;

        cost += safeOrBlocked(getBlock(node, dx, y, dz));
        if (cost > BLOCKED) return null;
        cost += safeOrBlocked(getBlock(node, dx, y + 1, dz));
        if (cost > BLOCKED) return null;

        if (getBlock(node, 0, 0, 0).liquid) {
            cost += 1.0; // liquidCost
        }

        BlockInfo blockD = getBlock(node, dx, -1, dz);
        if (y == 1) { // stepping up by 1 while moving diagonally
            if (blockC.height() - block0.height() > MAX_STEP_HEIGHT) {
                return null;
            }
            cost += safeOrBlocked(getBlock(node, 0, 2, 0));
            if (cost > BLOCKED) return null;
            cost += 1.0;
            return new Move(blockC.x, blockC.y + 1, blockC.z, cost);
        } else if (blockD.physical || blockC.liquid) {
            return new Move(blockC.x, blockC.y, blockC.z, cost);
        } else if (getBlock(node, dx, -2, dz).physical || blockD.liquid) {
            if (!blockD.safe) {
                return null; // don't self-immolate (e.g. drop into lava)
            }
            return new Move(blockC.x, blockC.y - 1, blockC.z, cost);
        }
        return null;
    }

    private BlockInfo getLandingBlock(final Move node, final int dx, final int dz) {
        BlockInfo blockLand = getBlock(node, dx, -2, dz);
        // No dimension min_y tracking -- bound the fall search by a
        // generous number of blocks instead, same as the earlier Python
        // port, which naturally stops at the bottom of loaded chunk data
        // rather than looping forever over unknown blocks.
        for (int i = 0; i < 256; i++) {
            if (!blockLand.known) {
                return null;
            }
            if (blockLand.liquid && blockLand.safe) {
                return blockLand;
            }
            if (blockLand.physical) {
                return getBlock(blockLand, 0, 1, 0);
            }
            if (!blockLand.safe) {
                return null;
            }
            blockLand = getBlock(blockLand, 0, -1, 0);
        }
        return null;
    }

    public Move getMoveDropDown(final Move node, final int dx, final int dz) {
        BlockInfo blockB = getBlock(node, dx, 1, dz);
        BlockInfo blockC = getBlock(node, dx, 0, dz);
        BlockInfo blockD = getBlock(node, dx, -1, dz);

        double cost = 1.0;

        BlockInfo blockLand = getLandingBlock(node, dx, dz);
        if (blockLand == null) {
            return null;
        }

        cost += safeOrBlocked(blockB);
        if (cost > BLOCKED) return null;
        cost += safeOrBlocked(blockC);
        if (cost > BLOCKED) return null;
        cost += safeOrBlocked(blockD);
        if (cost > BLOCKED) return null;

        if (blockC.liquid) {
            return null; // don't go underwater
        }

        return new Move(blockLand.x, blockLand.y, blockLand.z, cost);
    }

    public Move getMoveDown(final Move node) {
        BlockInfo block0 = getBlock(node, 0, -1, 0);

        double cost = 1.0;

        BlockInfo blockLand = getLandingBlock(node, 0, 0);
        if (blockLand == null) {
            return null;
        }

        cost += safeOrBlocked(block0);
        if (cost > BLOCKED) return null;

        if (getBlock(node, 0, 0, 0).liquid) {
            return null; // don't go underwater
        }

        return new Move(blockLand.x, blockLand.y, blockLand.z, cost);
    }

    public Move getMoveUp(final Move node) {
        BlockInfo block1 = getBlock(node, 0, 0, 0);
        if (block1.liquid) {
            return null;
        }

        BlockInfo block2 = getBlock(node, 0, 2, 0);

        double cost = 1.0;
        cost += safeOrBlocked(block2);
        if (cost > BLOCKED) return null;

        if (!block1.climbable) {
            return null; // can only climb via a ladder/vine -- no 1x1 towering, which needs placing
        }

        return new Move(node.x, node.y + 1, node.z, cost);
    }

    public List<Move> getMoveParkourForward(final Move node, final int dx, final int dz) {
        List<Move> moves = new ArrayList<>();

        BlockInfo block0 = getBlock(node, 0, -1, 0);
        BlockInfo block1 = getBlock(node, dx, -1, dz);
        if ((block1.physical && block1.height() >= block0.height())
            || !getBlock(node, dx, 0, dz).safe
            || !getBlock(node, dx, 1, dz).safe) {
            return moves;
        }
        if (getBlock(node, 0, 0, 0).liquid) {
            return moves; // can't jump from water
        }

        double cost = 1.0;

        boolean ceilingClear = getBlock(node, 0, 2, 0).safe && getBlock(node, dx, 2, dz).safe;
        boolean floorCleared = !getBlock(node, dx, -2, dz).physical;

        int maxD = allowSprinting ? 4 : 2;

        for (int d = 2; d <= maxD; d++) {
            int ddx = dx * d;
            int ddz = dz * d;
            BlockInfo blockA = getBlock(node, ddx, 2, ddz);
            BlockInfo blockB = getBlock(node, ddx, 1, ddz);
            BlockInfo blockC = getBlock(node, ddx, 0, ddz);
            BlockInfo blockD = getBlock(node, ddx, -1, ddz);

            if (ceilingClear && blockB.safe && blockC.safe && blockD.physical) {
                moves.add(new Move(blockC.x, blockC.y, blockC.z, cost));
                break;
            } else if (ceilingClear && blockB.safe && blockC.physical) {
                if (blockA.safe && d != 4) { // 4-forward-1-up is very difficult and fails often
                    if (blockC.height() - block0.height() > MAX_STEP_HEIGHT) {
                        break; // too high to jump
                    }
                    moves.add(new Move(blockB.x, blockB.y, blockB.z, cost));
                    break;
                }
            } else if ((ceilingClear || d == 2) && blockB.safe && blockC.safe && blockD.safe && floorCleared) {
                BlockInfo blockE = getBlock(node, ddx, -2, ddz);
                if (blockE.physical) {
                    moves.add(new Move(blockD.x, blockD.y, blockD.z, cost));
                }
                floorCleared = floorCleared && !blockE.physical;
            } else if (!blockB.safe || !blockC.safe) {
                break;
            }

            ceilingClear = ceilingClear && blockA.safe;
        }

        return moves;
    }

    public List<Move> getNeighbors(final Move node) {
        List<Move> neighbors = new ArrayList<>();

        for (int[] dir : CARDINAL_DIRECTIONS) {
            Move forward = getMoveForward(node, dir[0], dir[1]);
            if (forward != null) neighbors.add(forward);
            Move jumpUp = getMoveJumpUp(node, dir[0], dir[1]);
            if (jumpUp != null) neighbors.add(jumpUp);
            Move dropDown = getMoveDropDown(node, dir[0], dir[1]);
            if (dropDown != null) neighbors.add(dropDown);
            if (allowParkour) {
                neighbors.addAll(getMoveParkourForward(node, dir[0], dir[1]));
            }
        }

        for (int[] dir : DIAGONAL_DIRECTIONS) {
            Move diagonal = getMoveDiagonal(node, dir[0], dir[1]);
            if (diagonal != null) neighbors.add(diagonal);
        }

        Move down = getMoveDown(node);
        if (down != null) neighbors.add(down);
        Move up = getMoveUp(node);
        if (up != null) neighbors.add(up);

        return neighbors;
    }
}
