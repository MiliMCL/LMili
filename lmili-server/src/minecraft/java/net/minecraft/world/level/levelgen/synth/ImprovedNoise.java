package net.minecraft.world.level.levelgen.synth;

import com.google.common.annotations.VisibleForTesting;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class ImprovedNoise {
    private static final float SHIFT_UP_EPSILON = 1.0E-7F;
    private final byte[] p;
    public final double xo;
    public final double yo;
    public final double zo;

    // Gale start - C2ME - optimize noise generation
    private static final double[] FLAT_SIMPLEX_GRAD = new double[]{
        1, 1, 0, 0,
        -1, 1, 0, 0,
        1, -1, 0, 0,
        -1, -1, 0, 0,
        1, 0, 1, 0,
        -1, 0, 1, 0,
        1, 0, -1, 0,
        -1, 0, -1, 0,
        0, 1, 1, 0,
        0, -1, 1, 0,
        0, 1, -1, 0,
        0, -1, -1, 0,
        1, 1, 0, 0,
        0, -1, 1, 0,
        -1, 1, 0, 0,
        0, -1, -1, 0,
    };
    // Gale end - C2ME - optimize noise generation

    public ImprovedNoise(final RandomSource random) {
        this.xo = random.nextDouble() * 256.0;
        this.yo = random.nextDouble() * 256.0;
        this.zo = random.nextDouble() * 256.0;
        this.p = new byte[256];

        for (int i = 0; i < 256; i++) {
            this.p[i] = (byte)i;
        }

        for (int i = 0; i < 256; i++) {
            int offset = random.nextInt(256 - i);
            byte tmp = this.p[i];
            this.p[i] = this.p[i + offset];
            this.p[i + offset] = tmp;
        }
    }

    public double noise(final double _x, final double _y, final double _z) {
        return this.noise(_x, _y, _z, 0.0, 0.0);
    }

    @Deprecated
    public double noise(final double _x, final double _y, final double _z, final double yScale, final double yFudge) {
        double x = _x + this.xo;
        double y = _y + this.yo;
        double z = _z + this.zo;
        // Gale start - C2ME - optimize noise generation - optimize: remove frequent type conversions
        double xf = Math.floor(x);
        double yf = Math.floor(y);
        double zf = Math.floor(z);
        // Gale end - C2ME - optimize noise generation - optimize: remove frequent type conversions
        double xr = x - xf;
        double yr = y - yf;
        double zr = z - zf;
        double yrFudge;
        if (yScale != 0.0) {
            double fudgeLimit;
            if (yFudge >= 0.0 && yFudge < yr) {
                fudgeLimit = yFudge;
            } else {
                fudgeLimit = yr;
            }

            yrFudge = Math.floor(fudgeLimit / yScale + 1.0E-7F) * yScale; // Gale - C2ME - optimize noise generation - optimize: remove frequent type conversions
        } else {
            yrFudge = 0.0;
        }

        return this.sampleAndLerp((int) xf, (int) yf, (int) zf, xr, yr - yrFudge, zr, yr); // Gale - C2ME - optimize noise generation - optimize: remove frequent type conversions
    }

    public double noiseWithDerivative(final double _x, final double _y, final double _z, final double[] derivativeOut) {
        double x = _x + this.xo;
        double y = _y + this.yo;
        double z = _z + this.zo;
        // Gale start - C2ME - optimize noise generation - optimize: remove frequent type conversions
        double xf = Math.floor(x);
        double yf = Math.floor(y);
        double zf = Math.floor(z);
        // Gale end - C2ME - optimize noise generation - optimize: remove frequent type conversions
        double xr = x - xf;
        double yr = y - yf;
        double zr = z - zf;
        return this.sampleWithDerivative((int) xf, (int) yf, (int) zf, xr, yr, zr, derivativeOut); // Gale - C2ME - optimize noise generation - optimize: remove frequent type conversions
    }

    private static double gradDot(final int hash, final double x, final double y, final double z) {
        return SimplexNoise.dot(SimplexNoise.GRADIENT[hash & 15], x, y, z);
    }

    private int p(final int x) {
        return this.p[x & 0xFF] & 0xFF;
    }

    private double sampleAndLerp(final int x, final int y, final int z, final double xr, final double yr, final double zr, final double yrOriginal) {
        // Gale start - C2ME - optimize noise generation - inline math & small optimization: remove frequent type conversions and redundant ops
        final int var0 = x & 0xFF;
        final int var1 = (x + 1) & 0xFF;
        final int var2 = this.p[var0] & 0xFF;
        final int var3 = this.p[var1] & 0xFF;
        final int var4 = (var2 + y) & 0xFF;
        final int var5 = (var3 + y) & 0xFF;
        final int var6 = (var2 + y + 1) & 0xFF;
        final int var7 = (var3 + y + 1) & 0xFF;
        final int var8 = this.p[var4] & 0xFF;
        final int var9 = this.p[var5] & 0xFF;
        final int var10 = this.p[var6] & 0xFF;
        final int var11 = this.p[var7] & 0xFF;

        final int var12 = (var8 + z) & 0xFF;
        final int var13 = (var9 + z) & 0xFF;
        final int var14 = (var10 + z) & 0xFF;
        final int var15 = (var11 + z) & 0xFF;
        final int var16 = (var8 + z + 1) & 0xFF;
        final int var17 = (var9 + z + 1) & 0xFF;
        final int var18 = (var10 + z + 1) & 0xFF;
        final int var19 = (var11 + z + 1) & 0xFF;
        final int var20 = (this.p[var12] & 15) << 2;
        final int var21 = (this.p[var13] & 15) << 2;
        final int var22 = (this.p[var14] & 15) << 2;
        final int var23 = (this.p[var15] & 15) << 2;
        final int var24 = (this.p[var16] & 15) << 2;
        final int var25 = (this.p[var17] & 15) << 2;
        final int var26 = (this.p[var18] & 15) << 2;
        final int var27 = (this.p[var19] & 15) << 2;
        final double var60 = xr - 1.0;
        final double var61 = yr - 1.0;
        final double var62 = zr - 1.0;
        final double var87 = FLAT_SIMPLEX_GRAD[(var20) | 0] * xr + FLAT_SIMPLEX_GRAD[(var20) | 1] * yr + FLAT_SIMPLEX_GRAD[(var20) | 2] * zr;
        final double var88 = FLAT_SIMPLEX_GRAD[(var21) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var21) | 1] * yr + FLAT_SIMPLEX_GRAD[(var21) | 2] * zr;
        final double var89 = FLAT_SIMPLEX_GRAD[(var22) | 0] * xr + FLAT_SIMPLEX_GRAD[(var22) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var22) | 2] * zr;
        final double var90 = FLAT_SIMPLEX_GRAD[(var23) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var23) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var23) | 2] * zr;
        final double var91 = FLAT_SIMPLEX_GRAD[(var24) | 0] * xr + FLAT_SIMPLEX_GRAD[(var24) | 1] * yr + FLAT_SIMPLEX_GRAD[(var24) | 2] * var62;
        final double var92 = FLAT_SIMPLEX_GRAD[(var25) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var25) | 1] * yr + FLAT_SIMPLEX_GRAD[(var25) | 2] * var62;
        final double var93 = FLAT_SIMPLEX_GRAD[(var26) | 0] * xr + FLAT_SIMPLEX_GRAD[(var26) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var26) | 2] * var62;
        final double var94 = FLAT_SIMPLEX_GRAD[(var27) | 0] * var60 + FLAT_SIMPLEX_GRAD[(var27) | 1] * var61 + FLAT_SIMPLEX_GRAD[(var27) | 2] * var62;

        final double var95 = xr * 6.0 - 15.0;
        final double var96 = yrOriginal * 6.0 - 15.0;
        final double var97 = zr * 6.0 - 15.0;
        final double var98 = xr * var95 + 10.0;
        final double var99 = yrOriginal * var96 + 10.0;
        final double var100 = zr * var97 + 10.0;
        final double var101 = xr * xr * xr * var98;
        final double var102 = yrOriginal * yrOriginal * yrOriginal * var99;
        final double var103 = zr * zr * zr * var100;

        final double var113 = var87 + var101 * (var88 - var87);
        final double var114 = var93 + var101 * (var94 - var93);
        final double var115 = var91 + var101 * (var92 - var91);
        final double var116 = var89 + var101 * (var90 - var89);
        final double var117 = var114 - var115;
        final double var118 = var102 * (var116 - var113);
        final double var119 = var102 * var117;
        final double var120 = var113 + var118;
        final double var121 = var115 + var119;
        return var120 + (var103 * (var121 - var120));
        // Gale end - C2ME - optimize noise generation - inline math & small optimization: remove frequent type conversions and redundant ops
    }

    private double sampleWithDerivative(final int x, final int y, final int z, final double xr, final double yr, final double zr, final double[] derivativeOut) {
        int x0 = this.p(x);
        int x1 = this.p(x + 1);
        int xy00 = this.p(x0 + y);
        int xy01 = this.p(x0 + y + 1);
        int xy10 = this.p(x1 + y);
        int xy11 = this.p(x1 + y + 1);
        int p000 = this.p(xy00 + z);
        int p100 = this.p(xy10 + z);
        int p010 = this.p(xy01 + z);
        int p110 = this.p(xy11 + z);
        int p001 = this.p(xy00 + z + 1);
        int p101 = this.p(xy10 + z + 1);
        int p011 = this.p(xy01 + z + 1);
        int p111 = this.p(xy11 + z + 1);
        int[] g000 = SimplexNoise.GRADIENT[p000 & 15];
        int[] g100 = SimplexNoise.GRADIENT[p100 & 15];
        int[] g010 = SimplexNoise.GRADIENT[p010 & 15];
        int[] g110 = SimplexNoise.GRADIENT[p110 & 15];
        int[] g001 = SimplexNoise.GRADIENT[p001 & 15];
        int[] g101 = SimplexNoise.GRADIENT[p101 & 15];
        int[] g011 = SimplexNoise.GRADIENT[p011 & 15];
        int[] g111 = SimplexNoise.GRADIENT[p111 & 15];
        double d000 = SimplexNoise.dot(g000, xr, yr, zr);
        double d100 = SimplexNoise.dot(g100, xr - 1.0, yr, zr);
        double d010 = SimplexNoise.dot(g010, xr, yr - 1.0, zr);
        double d110 = SimplexNoise.dot(g110, xr - 1.0, yr - 1.0, zr);
        double d001 = SimplexNoise.dot(g001, xr, yr, zr - 1.0);
        double d101 = SimplexNoise.dot(g101, xr - 1.0, yr, zr - 1.0);
        double d011 = SimplexNoise.dot(g011, xr, yr - 1.0, zr - 1.0);
        double d111 = SimplexNoise.dot(g111, xr - 1.0, yr - 1.0, zr - 1.0);
        double xAlpha = Mth.smoothstep(xr);
        double yAlpha = Mth.smoothstep(yr);
        double zAlpha = Mth.smoothstep(zr);
        double d1x = Mth.lerp3(xAlpha, yAlpha, zAlpha, g000[0], g100[0], g010[0], g110[0], g001[0], g101[0], g011[0], g111[0]);
        double d1y = Mth.lerp3(xAlpha, yAlpha, zAlpha, g000[1], g100[1], g010[1], g110[1], g001[1], g101[1], g011[1], g111[1]);
        double d1z = Mth.lerp3(xAlpha, yAlpha, zAlpha, g000[2], g100[2], g010[2], g110[2], g001[2], g101[2], g011[2], g111[2]);
        double d2x = Mth.lerp2(yAlpha, zAlpha, d100 - d000, d110 - d010, d101 - d001, d111 - d011);
        double d2y = Mth.lerp2(zAlpha, xAlpha, d010 - d000, d011 - d001, d110 - d100, d111 - d101);
        double d2z = Mth.lerp2(xAlpha, yAlpha, d001 - d000, d101 - d100, d011 - d010, d111 - d110);
        double xSD = Mth.smoothstepDerivative(xr);
        double ySD = Mth.smoothstepDerivative(yr);
        double zSD = Mth.smoothstepDerivative(zr);
        double dX = d1x + xSD * d2x;
        double dY = d1y + ySD * d2y;
        double dZ = d1z + zSD * d2z;
        derivativeOut[0] += dX;
        derivativeOut[1] += dY;
        derivativeOut[2] += dZ;
        return Mth.lerp3(xAlpha, yAlpha, zAlpha, d000, d100, d010, d110, d001, d101, d011, d111);
    }

    @VisibleForTesting
    public void parityConfigString(final StringBuilder sb) {
        NoiseUtils.parityNoiseOctaveConfigString(sb, this.xo, this.yo, this.zo, this.p);
    }
}
