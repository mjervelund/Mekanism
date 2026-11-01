package mekanism.common.lib.distribution;

/// Transaction-free twin of `EmitUtils.sendToAcceptors`
/// for the one case where every handler is a plain counter that starts at zero and accepts `min(offer, capacity - accepted)`: the save shares of a buffered
/// network. It follows [Target#sendToAcceptors] step by step (send possible, shift needed, remaining split, final pass) with the same [SplitInfo], so it hands out
/// exactly the same amounts, but without a nested transaction and a snapshot journal per handler.
public final class CapacitySplit {

    private CapacitySplit() {
    }

    /// @param capacities The capacity of each handler, in handler order.
    /// @param amount     The amount to split.
    ///
    /// @return The amount each handler accepted, in handler order.
    public static long[] split(long[] capacities, long amount) {
        int count = capacities.length;
        long[] accepted = new long[count];
        if (amount == 0 || count == 0) {
            return accepted;
        } else if (count == 1) {
            accept(capacities, accepted, 0, amount);
            return accepted;
        }
        SplitInfo splitInfo = new SplitInfo(amount, count);
        //Handlers that want more than the current share, in handler order (Target's linked "needed" map)
        int[] needed = new int[count];
        long[] neededAmount = new long[count];
        boolean[] removed = new boolean[count];
        int neededCount = 0;
        int remaining = 0;
        //Send possible: an offer that is not committed is rolled back, so the handler is still empty afterward
        for (int i = 0; i < count; i++) {
            long amountNeeded = offer(capacities[i], 0, splitInfo.getUnsent());
            if (amountNeeded <= splitInfo.getShareAmount()) {
                accepted[i] = amountNeeded;
                splitInfo.send(amountNeeded, true);
            } else {
                needed[neededCount] = i;
                neededAmount[neededCount++] = amountNeeded;
                remaining++;
            }
        }
        //Shift needed
        while (splitInfo.amountPerChanged) {
            splitInfo.amountPerChanged = false;
            if (remaining == 0 || splitInfo.getShareAmount() == 0) {
                continue;
            }
            for (int k = 0; k < neededCount; k++) {
                if (!removed[k] && neededAmount[k] <= splitInfo.getShareAmount()) {
                    splitInfo.send(accept(capacities, accepted, needed[k], neededAmount[k]), true);
                    removed[k] = true;
                    remaining--;
                }
            }
        }
        //Send remaining split
        if (remaining > 0 && splitInfo.getUnsent() > 0) {
            if (remaining == 1) {
                for (int k = 0; k < neededCount; k++) {
                    if (!removed[k]) {
                        splitInfo.send(accept(capacities, accepted, needed[k], splitInfo.getUnsent()), false);
                        return accepted;
                    }
                }
            }
            for (int k = 0; k < neededCount; k++) {
                if (removed[k]) {
                    continue;
                }
                long remainderAmount = splitInfo.getRemainderAmount();
                if (remainderAmount == 0) {
                    return accepted;
                }
                if (neededAmount[k] <= remainderAmount) {
                    splitInfo.send(accept(capacities, accepted, needed[k], neededAmount[k]), true);
                    removed[k] = true;
                } else {
                    splitInfo.send(accept(capacities, accepted, needed[k], remainderAmount), false);
                }
            }
            long unsent = splitInfo.getUnsent();
            if (unsent > 0) {
                for (int k = 0; k < neededCount; k++) {
                    if (!removed[k]) {
                        long took = accept(capacities, accepted, needed[k], unsent);
                        splitInfo.send(took, false);
                        unsent -= took;
                        if (unsent == 0) {
                            return accepted;
                        }
                    }
                }
            }
        }
        return accepted;
    }

    private static long offer(long capacity, long current, long amount) {
        return amount == 0 ? 0 : Math.max(0, Math.min(amount, capacity - current));
    }

    private static long accept(long[] capacities, long[] accepted, int index, long amount) {
        long toAccept = offer(capacities[index], accepted[index], amount);
        accepted[index] += toAccept;
        return toAccept;
    }
}
