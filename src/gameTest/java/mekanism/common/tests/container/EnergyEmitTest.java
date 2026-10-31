package mekanism.common.tests.container;

import java.util.ArrayList;
import java.util.List;
import mekanism.common.capabilities.energy.BasicEnergyContainer;
import mekanism.common.tests.helpers.MekGameTestHelper;
import mekanism.common.util.EnergyUtils;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

/// Checks the amounts [EnergyUtils#emit(List, mekanism.api.energy.IEnergyContainer, int, net.neoforged.neoforge.transfer.transaction.TransactionContext)] moves
/// when the targets take all, part or none of the offered energy, both with and without a parent transaction.
@ForEachTest(groups = "container.energy_emit")
public class EnergyEmitTest {

    private static final int STORED = 1_000;

    private static BasicEnergyContainer container(long capacity, long stored) {
        BasicEnergyContainer container = BasicEnergyContainer.create(capacity, null);
        container.setEnergy(stored, null);
        return container;
    }

    /// Runs the emit once without a parent transaction, and once inside a root transaction that is committed, and checks both give the same amounts.
    private static void check(MekGameTestHelper helper, String name, int maxOutput, long[] targetCapacities, long[] targetStored, int expectedSent,
          long[] expectedTargets) {
        for (boolean withParent : new boolean[]{false, true}) {
            BasicEnergyContainer source = container(STORED, STORED);
            List<EnergyHandler> targets = new ArrayList<>();
            for (int i = 0; i < targetCapacities.length; i++) {
                targets.add(container(targetCapacities[i], targetStored[i]));
            }
            int sent;
            if (withParent) {
                try (Transaction transaction = Transaction.openRoot()) {
                    sent = EnergyUtils.emit(targets, source, maxOutput, transaction);
                    transaction.commit();
                }
            } else {
                sent = EnergyUtils.emit(targets, source, maxOutput, null);
            }
            String context = name + (withParent ? " (in a parent transaction)" : " (root)");
            helper.assertValueEqual(sent, expectedSent, context + ": sent");
            helper.assertValueEqual(source.getAmountAsLong(), (long) (STORED - expectedSent), context + ": energy left in the source");
            for (int i = 0; i < expectedTargets.length; i++) {
                helper.assertValueEqual(targets.get(i).getAmountAsLong(), expectedTargets[i], context + ": energy in target " + i);
            }
        }
    }

    private static long[] longs(long... values) {
        return values;
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Emitting energy moves the same amounts whether the targets take all, part or none of it.")
    public static void testEmitAmounts(final MekGameTestHelper helper) {
        helper.startSequence()
              .thenExecute(() -> {
                  check(helper, "single target takes all", STORED, longs(5_000), longs(0), STORED, longs(STORED));
                  check(helper, "single target limited by max output", 400, longs(5_000), longs(0), 400, longs(400));
                  check(helper, "single target takes part", STORED, longs(300), longs(0), 300, longs(300));
                  check(helper, "single target almost full", STORED, longs(300), longs(250), 50, longs(300));
                  check(helper, "single target full", STORED, longs(300), longs(300), 0, longs(300));
                  check(helper, "two targets take all", STORED, longs(5_000, 5_000), longs(0, 0), STORED, longs(500, 500));
                  check(helper, "two targets, one takes part", STORED, longs(300, 5_000), longs(0, 0), STORED, longs(300, 700));
                  check(helper, "two targets take part", STORED, longs(300, 200), longs(0, 0), 500, longs(300, 200));
                  check(helper, "two targets full", STORED, longs(300, 200), longs(300, 200), 0, longs(300, 200));
                  check(helper, "no targets", STORED, longs(), longs(), 0, longs());
                  //An output-only container (like a generator's) does not accept energy from outside
                  BasicEnergyContainer source = container(STORED, STORED);
                  BasicEnergyContainer outputOnly = BasicEnergyContainer.output(STORED, null);
                  helper.assertValueEqual(EnergyUtils.emit(List.<EnergyHandler>of(outputOnly), source, STORED, null), 0, "output-only target: sent");
                  helper.assertValueEqual(source.getAmountAsLong(), (long) STORED, "output-only target: energy left in the source");
                  //An empty source sends nothing
                  BasicEnergyContainer empty = container(STORED, 0);
                  BasicEnergyContainer target = container(STORED, 0);
                  helper.assertValueEqual(EnergyUtils.emit(List.<EnergyHandler>of(target), empty, STORED, null), 0, "empty source: sent");
              })
              .thenSucceed();
    }
}
