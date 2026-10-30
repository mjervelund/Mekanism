package mekanism.common.registries;

import mekanism.common.Mekanism;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class MekanismTicketTypes {

    private MekanismTicketTypes() {
    }

    public static final DeferredRegister<TicketType> TICKET_TYPES = DeferredRegister.create(BuiltInRegistries.TICKET_TYPE, Mekanism.MODID);

    //Note: The ticket needs to both load and simulate the chunk so that the robit gets ticked again and can teleport to its owner.
    // Without FLAG_LOADING and FLAG_SIMULATION the ticket does not keep the chunk loaded at all. This matches the flags vanilla uses for ender pearls
    public static final Holder<TicketType> ROBIT_CHUNK_UNLOAD = TICKET_TYPES.register("robit_chunk_unload", () -> new TicketType(SharedConstants.TICKS_PER_SECOND,
          TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
}