package mekanism.common.util;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.util.RandomSource;

public class StatUtils {

    private StatUtils() {
    }

    private static final RandomSource rand = RandomSource.create();
    private static final double STIRLING_COEFF = 1 / Math.sqrt(2 * Math.PI);

    //TODO: Re-evaluate the need for this
    public static int inversePoisson(double mean) {
        return inversePoisson(mean, rand.nextDouble());
    }

    /// Same as [#inversePoisson(double)] for a given uniform draw `u` in [0, 1).
    ///
    /// The cumulative sums of the loop in [#inversePoissonLoop(double, double)] only depend on the mean, which only changes
    /// with upgrades. They are computed once per mean (with exactly the same floating point operations, in the same order)
    /// and the loop is replaced by a binary search for the first sum that is not below `u * exp(mean)`. The result is
    /// therefore identical to the loop for every `u`, not just the same distribution.
    static int inversePoisson(double mean, double u) {
        if (!(mean > 0) || mean > MAX_TABLE_MEAN) {
            //Non positive or NaN means return 0 without iterating; very large means would need a huge table: use the loop
            return inversePoissonLoop(mean, u);
        }
        return getTable(mean).sample(u);
    }

    /// The original routine: kept as the fallback for means above [#MAX_TABLE_MEAN] and as the reference the tables are
    /// built from and tested against.
    static int inversePoissonLoop(double mean, double u) {
        double r = u * Math.exp(mean);
        int m = 0;
        double p = 1;
        double stirlingValue = mean * Math.E;
        double mBound = 3 * Math.ceil(mean);
        while ((p < r) && (m < mBound)) {
            m++;
            p += STIRLING_COEFF / Math.sqrt(m) * Math.pow(stirlingValue / m, m);
        }
        return m;
    }

    /// Means up to this get a cached table of at most `3 * MAX_TABLE_MEAN + 1` doubles (24 KiB). With the default config
    /// the mean is between 0.1 and 100 (`maxUpgradeMultiplier` 10).
    static final double MAX_TABLE_MEAN = 1_024;
    /// Number of cache slots (power of two). A slot holds one mean; a collision replaces the entry and only costs a rebuild.
    private static final int CACHE_SLOTS = 64;
    private static final PoissonTable[] TABLES = new PoissonTable[CACHE_SLOTS];

    static PoissonTable getTable(double mean) {
        long bits = Double.doubleToLongBits(mean);
        int slot = (int) (HashCommon.mix(bits) & (CACHE_SLOTS - 1));
        //Entries are immutable (final fields), so a racy read sees either null, an old entry or a fully built one
        PoissonTable table = TABLES[slot];
        if (table == null || table.meanBits != bits) {
            table = new PoissonTable(mean);
            TABLES[slot] = table;
        }
        return table;
    }

    static final class PoissonTable {

        private final long meanBits;
        private final double expMean;
        /// cumulative[m] is the value of `p` in the loop after `m` iterations, for m in [0, mBound]
        private final double[] cumulative;

        PoissonTable(double mean) {
            this.meanBits = Double.doubleToLongBits(mean);
            this.expMean = Math.exp(mean);
            double stirlingValue = mean * Math.E;
            double mBound = 3 * Math.ceil(mean);
            //The loop iterates while m < mBound, an integer valued double (>= 3 here as 0 < mean <= MAX_TABLE_MEAN)
            int size = (int) mBound + 1;
            double[] sums = new double[size];
            double p = 1;
            sums[0] = p;
            for (int m = 1; m < size; m++) {
                p += STIRLING_COEFF / Math.sqrt(m) * Math.pow(stirlingValue / m, m);
                sums[m] = p;
            }
            this.cumulative = sums;
        }

        /// @return the first m with `!(cumulative[m] < r)`, or the last index if there is none; the loop's exit condition
        int sample(double u) {
            double r = u * expMean;
            double[] sums = cumulative;
            //Predicate !(sums[m] < r) is monotone in m (the sums never decrease); for a NaN r it holds at m = 0, as in the loop
            int lo = 0;
            int hi = sums.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (sums[mid] < r) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    public static double min(double... vals) {
        double min = vals[0];
        for (int i = 1; i < vals.length; i++) {
            min = Math.min(min, vals[i]);
        }
        return min;
    }

    public static double max(double... vals) {
        double max = vals[0];
        for (int i = 1; i < vals.length; i++) {
            max = Math.max(max, vals[i]);
        }
        return max;
    }

    public static float wrapDegrees(float angle) {
        angle = angle % 360;
        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }
}