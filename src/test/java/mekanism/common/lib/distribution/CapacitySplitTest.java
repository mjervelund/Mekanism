package mekanism.common.lib.distribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import mekanism.common.util.EmitUtils;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CapacitySplit hands out exactly what Target/EmitUtils.sendToAcceptors does for save shares")
class CapacitySplitTest {

    /// Same accept rule as UniversalCable.SaveShareJournal, journaled like it.
    private static class ShareHandler extends SnapshotJournal<Long> {

        private final long capacity;
        private long share;

        ShareHandler(long capacity) {
            this.capacity = capacity;
        }

        long accept(long amount, TransactionContext transaction) {
            if (amount == 0) {
                return 0;
            }
            long toAccept = Math.min(amount, capacity - share);
            if (toAccept > 0) {
                updateSnapshots(transaction);
                share += toAccept;
            }
            return toAccept;
        }

        @Override
        protected Long createSnapshot() {
            return share;
        }

        @Override
        protected void revertToSnapshot(Long snapshot) {
            share = snapshot;
        }
    }

    private static class ShareTarget extends Target<ShareHandler, @Nullable Void> {

        ShareTarget(int expectedSize) {
            super(expectedSize);
        }

        @Override
        protected long accept(ShareHandler handler, @Nullable Void unused, long amount, TransactionContext transaction) {
            return handler.accept(amount, transaction);
        }
    }

    private static long[] viaTarget(long[] capacities, long amount) {
        ShareTarget target = new ShareTarget(capacities.length);
        List<ShareHandler> handlers = new ArrayList<>();
        for (long capacity : capacities) {
            ShareHandler handler = new ShareHandler(capacity);
            handlers.add(handler);
            target.addHandler(handler);
        }
        try (Transaction transaction = Transaction.openRoot()) {
            EmitUtils.sendToAcceptors(target, amount, null, transaction);
            transaction.commit();
        }
        return handlers.stream().mapToLong(h -> h.share).toArray();
    }

    private static void check(long[] capacities, long amount) {
        Assertions.assertArrayEquals(viaTarget(capacities, amount), CapacitySplit.split(capacities, amount),
              () -> "capacities " + java.util.Arrays.toString(capacities) + " amount " + amount);
    }

    @Test
    @DisplayName("Edge cases")
    void edgeCases() {
        check(new long[0], 10);
        check(new long[]{5}, 0);
        check(new long[]{5}, 3);
        check(new long[]{5}, 9);
        check(new long[]{5, 5, 5}, 15);
        check(new long[]{5, 5, 5}, 16);
        check(new long[]{5, 5, 5}, 14);
        check(new long[]{1, 100, 1, 100}, 150);
        check(new long[]{0, 10, 0}, 7);
    }

    @Test
    @DisplayName("Random networks of cable tiers")
    void randomCableNetworks() {
        Random random = new Random(42);
        //Universal cable tiers (basic, advanced, elite, ultimate) and odd values
        long[] tiers = {8_000, 128_000, 1_024_000, 8_192_000, 1, 3, 7, 1_000};
        for (int round = 0; round < 3000; round++) {
            int count = 1 + random.nextInt(round < 2000 ? 12 : 400);
            int tierCount = 1 + random.nextInt(tiers.length);
            long[] capacities = new long[count];
            long total = 0;
            for (int i = 0; i < count; i++) {
                capacities[i] = tiers[random.nextInt(tierCount)];
                total += capacities[i];
            }
            //Network buffers never exceed the summed capacity, but also check overfull amounts
            long amount = switch (random.nextInt(4)) {
                case 0 -> random.nextLong(Math.min(total, 50) + 1);
                case 1 -> total;
                case 2 -> total + 1 + random.nextInt(1000);
                default -> random.nextLong(total + 1);
            };
            check(capacities, amount);
        }
    }
}
