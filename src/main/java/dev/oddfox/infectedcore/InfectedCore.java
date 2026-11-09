package dev.oddfox.infectedcore;
import dev.letitwalk.ModContent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
@Mod(InfectedCore.MOD_ID)
public final class InfectedCore {
  public static final String MOD_ID="infected_core";
  private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(MOD_ID);
  private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(MOD_ID);
  private static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,MOD_ID);
  public static final DeferredBlock<InfectedWalkerCoreBlock> INFECTED_BLOCK=BLOCKS.registerBlock(
      "infected_core",InfectedWalkerCoreBlock::new,
      BlockBehaviour.Properties.of().strength(5.0f,6.0f).requiresCorrectToolForDrops().sound(SoundType.METAL));
  public static final DeferredItem<BlockItem> INFECTED_ITEM=ITEMS.registerItem(
      "infected_core",p->new BlockItem(INFECTED_BLOCK.get(),p),new Item.Properties());
  public static final DeferredHolder<CreativeModeTab,CreativeModeTab> CREATIVE_TAB=TABS.register("main",
      ()->CreativeModeTab.builder().title(Component.translatable("itemGroup.infected_core"))
          .icon(()->new ItemStack(INFECTED_ITEM.get()))
          .displayItems((parameters,output)->output.accept(INFECTED_ITEM.get())).build());
  public InfectedCore(IEventBus modBus) {
    BLOCKS.register(modBus); ITEMS.register(modBus); TABS.register(modBus);
    modBus.addListener(this::onBlockEntityTypeAddBlocks);
  }
  private void onBlockEntityTypeAddBlocks(BlockEntityTypeAddBlocksEvent event) {
    event.modify(ModContent.WALKER_CORE_BE.get(),INFECTED_BLOCK.get());
  }
}
