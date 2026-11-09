package mekanism.common.inventory.warning;

import java.util.List;
import mekanism.common.MekanismLang;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WarningTrackerTest {

    @Test
    @DisplayName("Warnings after NOT_ENOUGH_ENERGY are still shown (mekanism/Mekanism#8467)")
    void laterWarningsShownWithNotEnoughEnergy() {
        WarningTracker tracker = new WarningTracker();
        tracker.trackWarning(WarningType.NOT_ENOUGH_ENERGY, () -> true);
        tracker.trackWarning(WarningType.REDSTONE_SIGNAL_ABSENT, () -> true);
        List<Component> warnings = tracker.getWarnings();
        Assertions.assertEquals(List.of(MekanismLang.ISSUE_NOT_ENOUGH_ENERGY.translate(), MekanismLang.ISSUE_REDSTONE_SIGNAL_ABSENT.translate()), warnings);
    }

    @Test
    @DisplayName("NOT_ENOUGH_ENERGY still suppresses NOT_ENOUGH_ENERGY_REDUCED_RATE")
    void reducedRateSuppressedByNotEnoughEnergy() {
        WarningTracker tracker = new WarningTracker();
        tracker.trackWarning(WarningType.NOT_ENOUGH_ENERGY, () -> true);
        tracker.trackWarning(WarningType.NOT_ENOUGH_ENERGY_REDUCED_RATE, () -> true);
        Assertions.assertEquals(List.of(MekanismLang.ISSUE_NOT_ENOUGH_ENERGY.translate()), tracker.getWarnings());
    }
}
