package mekanism.common.content.transporter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathfinderOpenQueueTest {

    private record Entry(long node, double score, int order) {
    }

    @Test
    @DisplayName("Polls in ascending score order, equal scores in insertion order, with adds between polls")
    void matchesSortedReference() {
        Random random = new Random(42);
        PathfinderOpenQueue queue = new PathfinderOpenQueue();
        List<Entry> reference = new ArrayList<>();
        Comparator<Entry> byScoreThenOrder = Comparator.comparingDouble(Entry::score).thenComparingInt(Entry::order);
        int order = 0;
        for (int round = 0; round < 20_000; round++) {
            if (reference.isEmpty() || random.nextInt(3) != 0) {
                //Few distinct scores so that ties are common
                double score = random.nextInt(50) / 2.0;
                long node = random.nextLong();
                queue.add(node, score);
                reference.add(new Entry(node, score, order++));
            } else {
                reference.sort(byScoreThenOrder);
                Entry expected = reference.removeFirst();
                Assertions.assertFalse(queue.isEmpty());
                Assertions.assertEquals(expected.score(), queue.peekScore());
                Assertions.assertEquals(expected.node(), queue.poll());
            }
        }
        reference.sort(byScoreThenOrder);
        for (Entry expected : reference) {
            Assertions.assertEquals(expected.score(), queue.peekScore());
            Assertions.assertEquals(expected.node(), queue.poll());
        }
        Assertions.assertTrue(queue.isEmpty());
    }
}
