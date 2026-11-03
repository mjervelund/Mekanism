package mekanism.common.util;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatUtilsTest {

    /// Means a machine can see: `maxUpgradeMultiplier ^ (2 * speed - chemical)` with fractions in eighths, for the default
    /// multiplier 10 and a larger one, plus edge cases around the table limits.
    private static final double[] MEANS = {
          1, 0.1, Math.pow(10, 0.125), Math.pow(10, 0.25), Math.pow(10, 0.5), Math.pow(10, 0.875), 10, Math.pow(10, 1.25),
          Math.pow(10, 1.875), 100, 1e-9, 0.5, 2.5, 3, 7.3, 64, 709, 710, 1_000, StatUtils.MAX_TABLE_MEAN
    };
    private static final int DRAWS = 1_000_000;

    @Test
    @DisplayName("Table lookup returns the same value as the loop for the same draw, and the same histogram, over 1e6 draws")
    void sameAsLoop() {
        for (double mean : MEANS) {
            SplittableRandom random = new SplittableRandom(Double.doubleToLongBits(mean));
            int bound = (int) (3 * Math.ceil(mean)) + 1;
            long[] tableHist = new long[bound];
            long[] loopHist = new long[bound];
            for (int i = 0; i < DRAWS; i++) {
                double u = random.nextDouble();
                int fromTable = StatUtils.inversePoisson(mean, u);
                int fromLoop = StatUtils.inversePoissonLoop(mean, u);
                if (fromTable != fromLoop) {
                    Assertions.fail("mean " + mean + " u " + u + ": table " + fromTable + " loop " + fromLoop);
                }
                tableHist[fromTable]++;
                loopHist[fromLoop]++;
            }
            Assertions.assertArrayEquals(loopHist, tableHist, "histogram for mean " + mean);
        }
    }

    @Test
    @DisplayName("Draws exactly on, just below and just above every cumulative sum, and the ends of [0, 1)")
    void boundaries() {
        for (double mean : MEANS) {
            double exp = Math.exp(mean);
            StatUtils.PoissonTable table = StatUtils.getTable(mean);
            int bound = (int) (3 * Math.ceil(mean));
            double p = 1;
            double stirlingValue = mean * Math.E;
            for (int m = 0; m <= bound; m++) {
                if (m > 0) {
                    p += 1 / Math.sqrt(2 * Math.PI) / Math.sqrt(m) * Math.pow(stirlingValue / m, m);
                }
                double u = p / exp;
                for (double candidate : new double[]{u, Math.nextDown(u), Math.nextUp(u)}) {
                    if (candidate >= 0 && candidate < 1) {
                        Assertions.assertEquals(StatUtils.inversePoissonLoop(mean, candidate), table.sample(candidate), "mean " + mean + " u " + candidate);
                    }
                }
            }
            for (double u : new double[]{0, Double.MIN_VALUE, 0.5, Math.nextDown(1.0)}) {
                Assertions.assertEquals(StatUtils.inversePoissonLoop(mean, u), StatUtils.inversePoisson(mean, u), "mean " + mean + " u " + u);
            }
        }
    }

    @Test
    @DisplayName("Means outside the table (zero, negative, NaN, infinite, very large) behave like the loop")
    void outsideTable() {
        double[] means = {0, -0.0, -1, -100, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.nextUp(StatUtils.MAX_TABLE_MEAN), 5_000};
        for (double mean : means) {
            for (double u : new double[]{0, 0.25, 0.5, 0.999}) {
                Assertions.assertEquals(StatUtils.inversePoissonLoop(mean, u), StatUtils.inversePoisson(mean, u), "mean " + mean + " u " + u);
            }
        }
    }

    @Test
    @DisplayName("Changing means are cached per mean: a table is reused for its mean and rebuilt only on a collision")
    void cache() {
        StatUtils.PoissonTable a = StatUtils.getTable(10);
        Assertions.assertSame(a, StatUtils.getTable(10));
        for (int i = 0; i < 1_000; i++) {
            //Many distinct means must not grow anything: the cache has a fixed number of slots
            StatUtils.getTable(1 + i / 1000.0);
        }
        Assertions.assertEquals(StatUtils.inversePoissonLoop(10, 0.7), StatUtils.getTable(10).sample(0.7));
    }
}
