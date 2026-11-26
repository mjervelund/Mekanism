package mekanism.common.content.transporter;

import java.util.Arrays;

/// Binary min-heap of (fScore, node) with lazy deletion: a node whose fScore drops is added again and its older entry is skipped
/// when polled. Equal scores come out in insertion order.
final class PathfinderOpenQueue {

    private double[] scores = new double[64];
    private long[] order = new long[64];
    private long[] nodes = new long[64];
    private int size;
    private long nextOrder;

    boolean isEmpty() {
        return size == 0;
    }

    double peekScore() {
        return scores[0];
    }

    void add(long node, double score) {
        if (size == scores.length) {
            int newLength = size * 2;
            scores = Arrays.copyOf(scores, newLength);
            order = Arrays.copyOf(order, newLength);
            nodes = Arrays.copyOf(nodes, newLength);
        }
        long entryOrder = nextOrder++;
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!less(score, entryOrder, scores[parent], order[parent])) {
                break;
            }
            set(i, parent);
            i = parent;
        }
        scores[i] = score;
        order[i] = entryOrder;
        nodes[i] = node;
    }

    long poll() {
        long result = nodes[0];
        int last = --size;
        if (last > 0) {
            double score = scores[last];
            long entryOrder = order[last];
            long node = nodes[last];
            int i = 0;
            int half = last >>> 1;
            while (i < half) {
                int child = 2 * i + 1;
                int right = child + 1;
                if (right < last && less(scores[right], order[right], scores[child], order[child])) {
                    child = right;
                }
                if (!less(scores[child], order[child], score, entryOrder)) {
                    break;
                }
                set(i, child);
                i = child;
            }
            scores[i] = score;
            order[i] = entryOrder;
            nodes[i] = node;
        }
        return result;
    }

    private void set(int to, int from) {
        scores[to] = scores[from];
        order[to] = order[from];
        nodes[to] = nodes[from];
    }

    private static boolean less(double scoreA, long orderA, double scoreB, long orderB) {
        return scoreA < scoreB || (scoreA == scoreB && orderA < orderB);
    }
}
