package net.minecraft.world.level.biome;

import com.google.common.hash.Hashing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;

public class BiomeManager {
    public static final int CHUNK_CENTER_QUART = QuartPos.fromBlock(8);
    private static final int ZOOM_BITS = 2;
    private static final int ZOOM = 4;
    private static final int ZOOM_MASK = 3;
    private final BiomeManager.NoiseBiomeSource noiseBiomeSource;
    private final long biomeZoomSeed;
    private static final double maxOffset = 0.4500000001D; // Leaf - Carpet-Fixes - Optimized getBiome method

    public BiomeManager(final BiomeManager.NoiseBiomeSource noiseBiomeSource, final long seed) {
        this.noiseBiomeSource = noiseBiomeSource;
        this.biomeZoomSeed = seed;
    }

    public static long obfuscateSeed(final long seed) {
        return Hashing.sha256().hashLong(seed).asLong();
    }

    public BiomeManager withDifferentSource(final BiomeManager.NoiseBiomeSource biomeSource) {
        return new BiomeManager(biomeSource, this.biomeZoomSeed);
    }

    public Holder<Biome> getBiome(final BlockPos pos) {
        // Leaf start - Carpet-Fixes - Optimized getBiome method
        int xMinus2 = pos.getX() - 2;
        int yMinus2 = pos.getY() - 2;
        int zMinus2 = pos.getZ() - 2;
        int x = xMinus2 >> 2; // BlockPos to BiomePos
        int y = yMinus2 >> 2;
        int z = zMinus2 >> 2;
        double quartX = (double) (xMinus2 & 3) / 4.0; // quartLocal divided by 4
        double quartY = (double) (yMinus2 & 3) / 4.0; // 0/4, 1/4, 2/4, 3/4
        double quartZ = (double) (zMinus2 & 3) / 4.0; // [0, 0.25, 0.5, 0.75]
        int smallestX = 0;
        double smallestDist = Double.POSITIVE_INFINITY;
        for (int biomeX = 0; biomeX < 8; ++biomeX) {
            boolean everyOtherQuad = (biomeX & 4) == 0; // 1 1 1 1 0 0 0 0
            boolean everyOtherPair = (biomeX & 2) == 0; // 1 1 0 0 1 1 0 0
            boolean everyOther = (biomeX & 1) == 0; // 1 0 1 0 1 0 1 0
            double quartXX = everyOtherQuad ? quartX : quartX - 1.0; //[-1.0,-0.75,-0.5,-0.25,0.0,0.25,0.5,0.75]
            double quartYY = everyOtherPair ? quartY : quartY - 1.0;
            double quartZZ = everyOther ? quartZ : quartZ - 1.0;

            //This code block is new
            double maxQuartYY = 0.0, maxQuartZZ = 0.0;
            if (biomeX != 0) {
                maxQuartYY = Mth.square(Math.max(quartYY + maxOffset, Math.abs(quartYY - maxOffset)));
                maxQuartZZ = Mth.square(Math.max(quartZZ + maxOffset, Math.abs(quartZZ - maxOffset)));
                double maxQuartXX = Mth.square(Math.max(quartXX + maxOffset, Math.abs(quartXX - maxOffset)));
                if (smallestDist < maxQuartXX + maxQuartYY + maxQuartZZ) continue;
            }
            int xx = everyOtherQuad ? x : x + 1;
            int yy = everyOtherPair ? y : y + 1;
            int zz = everyOther ? z : z + 1;

            //I transferred the code from method_38106 to here, so I could call continue halfway through
            long seed = LinearCongruentialGenerator.next(this.biomeZoomSeed, xx);
            seed = LinearCongruentialGenerator.next(seed, yy);
            seed = LinearCongruentialGenerator.next(seed, zz);
            seed = LinearCongruentialGenerator.next(seed, xx);
            seed = LinearCongruentialGenerator.next(seed, yy);
            seed = LinearCongruentialGenerator.next(seed, zz);
            double offsetX = getFiddle(seed);
            double sqrX = Mth.square(quartXX + offsetX);
            if (biomeX != 0 && smallestDist < sqrX + maxQuartYY + maxQuartZZ) continue; //skip the rest of the loop
            seed = LinearCongruentialGenerator.next(seed, this.biomeZoomSeed);
            double offsetY = getFiddle(seed);
            double sqrY = Mth.square(quartYY + offsetY);
            if (biomeX != 0 && smallestDist < sqrX + sqrY + maxQuartZZ) continue; // skip the rest of the loop
            seed = LinearCongruentialGenerator.next(seed, this.biomeZoomSeed);
            double offsetZ = getFiddle(seed);
            double biomeDist = sqrX + sqrY + Mth.square(quartZZ + offsetZ);

            if (smallestDist > biomeDist) {
                smallestX = biomeX;
                smallestDist = biomeDist;
            }
        }
        return this.noiseBiomeSource.getNoiseBiome(
            (smallestX & 4) == 0 ? x : x + 1,
            (smallestX & 2) == 0 ? y : y + 1,
            (smallestX & 1) == 0 ? z : z + 1
        );
        // Leaf end - Carpet-Fixes - Optimized getBiome method
    }

    public Holder<Biome> getNoiseBiomeAtPosition(final double x, final double y, final double z) {
        int quartX = QuartPos.fromBlock(Mth.floor(x));
        int quartY = QuartPos.fromBlock(Mth.floor(y));
        int quartZ = QuartPos.fromBlock(Mth.floor(z));
        return this.getNoiseBiomeAtQuart(quartX, quartY, quartZ);
    }

    public Holder<Biome> getNoiseBiomeAtPosition(final BlockPos blockPos) {
        int quartX = QuartPos.fromBlock(blockPos.getX());
        int quartY = QuartPos.fromBlock(blockPos.getY());
        int quartZ = QuartPos.fromBlock(blockPos.getZ());
        return this.getNoiseBiomeAtQuart(quartX, quartY, quartZ);
    }

    public Holder<Biome> getNoiseBiomeAtQuart(final int quartX, final int quartY, final int quartZ) {
        return this.noiseBiomeSource.getNoiseBiome(quartX, quartY, quartZ);
    }

    private static double getFiddledDistance(
        final long seed, final int xRandom, final int yRandom, final int zRandom, final double distanceX, final double distanceY, final double distanceZ
    ) {
        long rval = seed;
        rval = LinearCongruentialGenerator.next(rval, xRandom);
        rval = LinearCongruentialGenerator.next(rval, yRandom);
        rval = LinearCongruentialGenerator.next(rval, zRandom);
        rval = LinearCongruentialGenerator.next(rval, xRandom);
        rval = LinearCongruentialGenerator.next(rval, yRandom);
        rval = LinearCongruentialGenerator.next(rval, zRandom);
        double fiddleX = getFiddle(rval);
        rval = LinearCongruentialGenerator.next(rval, seed);
        double fiddleY = getFiddle(rval);
        rval = LinearCongruentialGenerator.next(rval, seed);
        double fiddleZ = getFiddle(rval);
        return Mth.square(distanceZ + fiddleZ) + Mth.square(distanceY + fiddleY) + Mth.square(distanceX + fiddleX);
    }

    private static double getFiddle(final long rval) {
        return (double)(((rval >> 24) & (1024 - 1)) - (1024/2)) * (0.9 / 1024.0); // Paper - avoid floorMod, fp division, and fp subtraction
    }

    public interface NoiseBiomeSource {
        Holder<Biome> getNoiseBiome(final int quartX, final int quartY, final int quartZ);
    }
}
