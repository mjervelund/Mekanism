package mekanism.common.content.filter;

import mekanism.common.content.transporter.SorterFilter;
import mekanism.common.content.transporter.SorterTagFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FilterManagerClientLimitsTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static FilterManager<SorterFilter<?>> manager() {
        return new FilterManager<SorterFilter<?>>((Class) SorterFilter.class, () -> {}, () -> null);
    }

    private static SorterTagFilter tagFilter(String tag) {
        SorterTagFilter filter = new SorterTagFilter();
        filter.setTagName(tag);
        return filter;
    }

    @Test
    @DisplayName("Test filter text up to the GUI's length is accepted and longer text is rejected")
    void filterTextLength() {
        FilterManager<SorterFilter<?>> manager = manager();
        String max = "c:" + "a".repeat(SorterFilter.MAX_LENGTH - 2);
        manager.tryAddFilter(tagFilter(max), false);
        manager.tryAddFilter(tagFilter(max + "a"), false);
        Assertions.assertEquals(1, manager.count());

        SorterTagFilter current = (SorterTagFilter) manager.getFilters().get(0);
        manager.tryEditFilter(current, tagFilter(max + "a"));
        Assertions.assertEquals(max, ((SorterTagFilter) manager.getFilters().get(0)).getTagName());
    }

    @Test
    @DisplayName("Test filters from clients are capped")
    void filterCount() {
        FilterManager<SorterFilter<?>> manager = manager();
        for (int i = 0; i <= FilterManager.MAX_CLIENT_FILTERS; i++) {
            manager.tryAddFilter(tagFilter("c:tag_" + i), false);
        }
        Assertions.assertEquals(FilterManager.MAX_CLIENT_FILTERS, manager.count());
    }
}
