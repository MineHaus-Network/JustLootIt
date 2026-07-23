package me.lauriichan.spigot.justlootit.listener;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;

import me.lauriichan.laylib.localization.Key;
import me.lauriichan.minecraft.pluginbase.extension.Extension;
import me.lauriichan.minecraft.pluginbase.inventory.IGuiInventory;
import me.lauriichan.minecraft.pluginbase.listener.IListenerExtension;
import me.lauriichan.spigot.justlootit.JustLootItAccess;
import me.lauriichan.spigot.justlootit.JustLootItConstant;
import me.lauriichan.spigot.justlootit.JustLootItFlag;
import me.lauriichan.spigot.justlootit.JustLootItPermission;
import me.lauriichan.spigot.justlootit.JustLootItPlugin;
import me.lauriichan.spigot.justlootit.capability.ActorCapability;
import me.lauriichan.spigot.justlootit.capability.PlayerGUICapability;
import me.lauriichan.spigot.justlootit.capability.StorageCapability;
import me.lauriichan.spigot.justlootit.command.impl.LootItActor;
import me.lauriichan.spigot.justlootit.config.MainConfig;
import me.lauriichan.spigot.justlootit.config.world.WorldMultiConfig;
import me.lauriichan.spigot.justlootit.data.CacheLookupTable;
import me.lauriichan.spigot.justlootit.data.CachedInventory;
import me.lauriichan.spigot.justlootit.data.Container;
import me.lauriichan.spigot.justlootit.data.IInventoryContainer;
import me.lauriichan.spigot.justlootit.data.CacheLookupTable.WorldEntry;
import me.lauriichan.spigot.justlootit.inventory.handler.loot.BaseLootUIHandler;
import me.lauriichan.spigot.justlootit.inventory.handler.loot.CachedLootUIHandler;
import me.lauriichan.spigot.justlootit.inventory.handler.loot.GeneratedLootUIHandler;
import me.lauriichan.spigot.justlootit.message.Messages;
import me.lauriichan.spigot.justlootit.nms.LevelAdapter;
import me.lauriichan.spigot.justlootit.nms.PlayerAdapter;
import me.lauriichan.spigot.justlootit.nms.VersionHelper;
import me.lauriichan.spigot.justlootit.storage.IStorage;
import me.lauriichan.spigot.justlootit.storage.Stored;
import me.lauriichan.spigot.justlootit.util.BlockUtil;
import me.lauriichan.spigot.justlootit.util.DataHelper;
import me.lauriichan.spigot.justlootit.util.EntityUtil;
import me.lauriichan.spigot.justlootit.util.ExplosionType;
import me.lauriichan.spigot.justlootit.util.InventoryUtil;

@Extension
public class ContainerListener implements IListenerExtension {

    private final JustLootItPlugin plugin;
    private final MainConfig config;
    private final ConcurrentHashMap<PlayerInteractEvent, Long> interactionTraces = new ConcurrentHashMap<>();

    public ContainerListener(final JustLootItPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().config(MainConfig.class);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBlockPlaceEvent(final BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Container container) || !(container.getBlockData() instanceof Chest chest)
            || JustLootItAccess.hasAnyOffset(container.getPersistentDataContainer()) || chest.getType() == Type.SINGLE) {
            return;
        }
        org.bukkit.block.Container otherContainer = BlockUtil.findChestAround(block.getWorld(), block.getLocation(), chest.getType(),
            chest.getFacing());
        if (otherContainer == null || !JustLootItAccess.hasIdentity(otherContainer.getPersistentDataContainer())) {
            return;
        }
        Chest cloned = (Chest) chest.clone();
        cloned.setType(Type.SINGLE);
        container.setBlockData(cloned);
        container.update(false, false);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBlockBreakEvent(final BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        PersistentDataContainer dataContainer = container.getPersistentDataContainer();
        if (!JustLootItAccess.hasIdentity(dataContainer) && !JustLootItAccess.hasAnyOffset(dataContainer)) {
            return;
        }
        org.bukkit.block.Container otherContainer = BlockUtil.getContainerByOffset(container);
        if (!JustLootItAccess.hasIdentity(dataContainer)) {
            if (otherContainer == null) {
                return;
            }
            PersistentDataContainer otherDataContainer = otherContainer.getPersistentDataContainer();
            if (!JustLootItAccess.hasIdentity(otherDataContainer)) {
                JustLootItAccess.removeOffset(otherDataContainer);
                JustLootItAccess.removeOffset(dataContainer);
                otherContainer.update(false, false);
                container.update(false, false);
                return;
            }
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        LootItActor<?> actor = ActorCapability.actor(plugin, player);
        if (!player.hasPermission(JustLootItPermission.ACTION_REMOVE_CONTAINER_BLOCK)) {
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_UNPERMITTED_BLOCK);
            return;
        }
        if (!player.isSneaking()) {
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_PERMITTED_BLOCK);
            return;
        }
        if (!DataHelper.canBreakContainer(dataContainer, actor.getId())) {
            container.update(false, false);
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_CONFIRMATION_BLOCK);
            return;
        }
        actor.versionHandler().getLevel(player.getWorld()).getCapability(StorageCapability.class).ifPresent(capability -> {
            if (capability.hasBulkOperationRunning()) {
                actor.sendTranslatedMessage(Messages.CONTAINER_ACCESS_STORAGE_BUSY);
                return;
            }
            if (otherContainer != null) {
                event.setCancelled(false);
                if (!JustLootItAccess.hasIdentity(dataContainer)) {
                    JustLootItAccess.removeOffset(otherContainer.getPersistentDataContainer());
                    JustLootItAccess.removeOffset(dataContainer);
                    container.update(false, false);
                    Chest chest = (Chest) otherContainer.getBlockData();
                    chest.setType(Type.SINGLE);
                    otherContainer.setBlockData(chest);
                    otherContainer.update(true, false);
                    actor.sendTranslatedBarMessage(Messages.CONTAINER_BREAK_DOUBLE_CHEST);
                    return;
                }
                final long id = JustLootItAccess.getIdentity(dataContainer);
                JustLootItAccess.removeOffset(dataContainer);
                JustLootItAccess.removeIdentity(dataContainer);
                container.update(false, false);
                PersistentDataContainer otherDataContainer = otherContainer.getPersistentDataContainer();
                JustLootItAccess.removeOffset(otherDataContainer);
                JustLootItAccess.setIdentity(otherDataContainer, id);
                Chest chest = (Chest) otherContainer.getBlockData();
                chest.setType(Type.SINGLE);
                otherContainer.setBlockData(chest);
                otherContainer.update(true, false);
                actor.sendTranslatedBarMessage(Messages.CONTAINER_BREAK_DOUBLE_CHEST);
                return;
            }
            final long id = JustLootItAccess.getIdentity(dataContainer);
            JustLootItAccess.removeIdentity(dataContainer);
            container.update(true, false);
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_REMOVED_BLOCK, Key.of("id", id));
            if (!config.deleteOnBreak()) {
                return;
            }
            if (!capability.storage().delete(id)) {
                actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_NO_CONTAINER, Key.of("id", id));
            }
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityChange(final EntityChangeBlockEvent event) {
        if (!(event.getBlock().getState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        PersistentDataContainer dataContainer = container.getPersistentDataContainer();
        event.setCancelled(JustLootItAccess.hasIdentity(dataContainer) || JustLootItAccess.hasAnyOffset(dataContainer));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(final EntityExplodeEvent event) {
        filterExplosion(event.getLocation().getWorld(), ExplosionType.fromEntity(event.getEntityType()), event.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.getBlock().getWorld(), ExplosionType.fromBlock(event.getBlock().getBlockData().getMaterial()),
            event.blockList());
    }

    private void filterExplosion(World world, ExplosionType type, List<Block> blockList) {
        boolean allowed = plugin.configManager().multiConfig(WorldMultiConfig.class, world).isExplosionAllowed(type);
        Iterator<Block> iterator = blockList.iterator();
        LevelAdapter level = plugin.versionHandler().getLevel(world);
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (!(block.getState() instanceof org.bukkit.block.Container container)) {
                continue;
            }
            PersistentDataContainer dataContainer = container.getPersistentDataContainer();
            if (JustLootItAccess.hasIdentity(dataContainer) || JustLootItAccess.hasAnyOffset(dataContainer)) {
                if (!allowed) {
                    iterator.remove();
                    continue;
                }
                breakContainerNaturally(level, container, dataContainer);
            }
        }
    }

    private void breakContainerNaturally(LevelAdapter level, org.bukkit.block.Container container, PersistentDataContainer dataContainer) {
        org.bukkit.block.Container otherContainer = BlockUtil.getContainerByOffset(container);
        if (!JustLootItAccess.hasIdentity(dataContainer)) {
            if (otherContainer == null) {
                return;
            }
            PersistentDataContainer otherDataContainer = otherContainer.getPersistentDataContainer();
            if (!JustLootItAccess.hasIdentity(otherDataContainer)) {
                JustLootItAccess.removeOffset(otherDataContainer);
                JustLootItAccess.removeOffset(dataContainer);
                otherContainer.update(false, false);
                container.update(false, false);
                return;
            }
        }
        level.getCapability(StorageCapability.class).ifPresent(capability -> {
            if (capability.hasBulkOperationRunning()) {
                return;
            }
            if (otherContainer != null) {
                if (!JustLootItAccess.hasIdentity(dataContainer)) {
                    JustLootItAccess.removeOffset(otherContainer.getPersistentDataContainer());
                    JustLootItAccess.removeOffset(dataContainer);
                    container.update(false, false);
                    Chest chest = (Chest) otherContainer.getBlockData();
                    chest.setType(Type.SINGLE);
                    otherContainer.setBlockData(chest);
                    otherContainer.update(true, false);
                    return;
                }
                final long id = JustLootItAccess.getIdentity(dataContainer);
                JustLootItAccess.removeOffset(dataContainer);
                JustLootItAccess.removeIdentity(dataContainer);
                container.update(false, false);
                PersistentDataContainer otherDataContainer = otherContainer.getPersistentDataContainer();
                JustLootItAccess.removeOffset(otherDataContainer);
                JustLootItAccess.setIdentity(otherDataContainer, id);
                Chest chest = (Chest) otherContainer.getBlockData();
                chest.setType(Type.SINGLE);
                otherContainer.setBlockData(chest);
                otherContainer.update(true, false);
                return;
            }
            final long id = JustLootItAccess.getIdentity(dataContainer);
            JustLootItAccess.removeIdentity(dataContainer);
            container.update(true, false);
            if (config.deleteOnBreak()) {
                capability.storage().delete(id);
            }
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onStructureGrowEvent(final StructureGrowEvent event) {
        Iterator<BlockState> iterator = event.getBlocks().iterator();
        World world = event.getWorld();
        while (iterator.hasNext()) {
            if (!(world.getBlockState(iterator.next().getLocation()) instanceof org.bukkit.block.Container container)) {
                continue;
            }
            PersistentDataContainer dataContainer = container.getPersistentDataContainer();
            if (JustLootItAccess.hasIdentity(dataContainer) || JustLootItAccess.hasAnyOffset(dataContainer)) {
                iterator.remove();
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onItemTransfer(final InventoryMoveItemEvent event) {
        event.setCancelled(InventoryUtil.isLootContainer(event.getDestination()) || InventoryUtil.isLootContainer(event.getSource()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onVehicleDamage(final VehicleDamageEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (!EntityUtil.isSupportedEntity(vehicle)) {
            return;
        }
        PersistentDataContainer dataContainer = vehicle.getPersistentDataContainer();
        if (!JustLootItAccess.hasIdentity(dataContainer)) {
            return;
        }
        event.setCancelled(true);
        Entity attacker = event.getAttacker();
        if (attacker == null || attacker.getType() != EntityType.PLAYER) {
            return;
        }
        Player player = (Player) attacker;
        LootItActor<?> actor = ActorCapability.actor(plugin, player);
        if (!player.hasPermission(JustLootItPermission.ACTION_REMOVE_CONTAINER_ENTITY)) {
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_UNPERMITTED_ENTITY);
            return;
        }
        if (!player.isSneaking()) {
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_PERMITTED_ENTITY);
            return;
        }
        if (!DataHelper.canBreakContainer(dataContainer, actor.getId())) {
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_CONFIRMATION_ENTITY);
            return;
        }
        actor.versionHandler().getLevel(player.getWorld()).getCapability(StorageCapability.class).ifPresent(capability -> {
            if (capability.hasBulkOperationRunning()) {
                actor.sendTranslatedMessage(Messages.CONTAINER_ACCESS_STORAGE_BUSY);
                return;
            }
            final long id = JustLootItAccess.getIdentity(dataContainer);
            JustLootItAccess.removeIdentity(dataContainer);
            actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_REMOVED_ENTITY, Key.of("id", id));
            if (!config.deleteOnBreak()) {
                return;
            }
            if (!capability.storage().delete(id)) {
                actor.sendTranslatedMessage(Messages.CONTAINER_BREAK_NO_CONTAINER, Key.of("id", id));
            }
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        final long traceId = plugin.containerAccessDiagnostics() ? plugin.nextContainerAccessTraceId() : 0;
        if (traceId > 0) {
            interactionTraces.put(event, traceId);
            plugin.logContainerAccess(traceId,
                "LOWEST received player=%s uuid=%s hand=%s block=%s location=%s event=%s identity=%s offset=%s"
                    .formatted(event.getPlayer().getName(), event.getPlayer().getUniqueId(), event.getHand(), block.getType(),
                        formatLocation(block.getLocation()), formatEventState(event),
                        JustLootItAccess.hasIdentity(container.getPersistentDataContainer()),
                        JustLootItAccess.hasAnyOffset(container.getPersistentDataContainer())));
        }
        final Player player = event.getPlayer();
        if (player.isSneaking()) {
            final EntityEquipment equipment = player.getEquipment();
            if (!equipment.getItem(EquipmentSlot.HAND).getType().isAir() || !equipment.getItem(EquipmentSlot.OFF_HAND).getType().isAir()) {
                plugin.logContainerAccess(traceId, "stopped: player is sneaking while holding an item");
                return;
            }
        }
        final PersistentDataContainer dataContainer = container.getPersistentDataContainer();
        if (!JustLootItAccess.hasIdentity(dataContainer)) {
            org.bukkit.block.Container otherContainer = BlockUtil.getContainerByOffset(container);
            if (otherContainer == null) {
                plugin.logContainerAccess(traceId, "stopped: clicked container has no identity and no linked container was found");
                return;
            }
            PersistentDataContainer otherDataContainer = otherContainer.getPersistentDataContainer();
            if (!JustLootItAccess.hasIdentity(otherDataContainer)) {
                plugin.logContainerAccess(traceId,
                    "stopped: linked container has no identity; removing stale offsets from both container halves");
                JustLootItAccess.removeOffset(otherDataContainer);
                JustLootItAccess.removeOffset(dataContainer);
                otherContainer.update(false, false);
                container.update(false, false);
                return;
            }
            final long identity = JustLootItAccess.getIdentity(otherDataContainer);
            plugin.logContainerAccess(traceId,
                "using linked container identity=%d location=%s".formatted(identity, formatLocation(otherContainer.getLocation())));
            accessContainer(otherContainer.getLocation(), otherContainer, otherDataContainer, event, event.getPlayer(),
                identity, traceId);
            if (JustLootItAccess.hasIdentity(otherDataContainer)) {
                return;
            }
            plugin.logContainerAccess(traceId, "linked container identity was removed; clearing offsets from both container halves");
            JustLootItAccess.removeOffset(otherDataContainer);
            JustLootItAccess.removeOffset(dataContainer);
            otherContainer.update(false, false);
            container.update(false, false);
            return;
        }
        if (!JustLootItFlag.TILE_ENTITY_CONTAINERS.isSet()
            && JustLootItConstant.UNSUPPORTED_CONTAINER_TYPES.contains(container.getInventory().getType())) {
            plugin.logContainerAccess(traceId,
                "stopped: inventory type %s is unsupported by the active version handler".formatted(container.getInventory().getType()));
            return;
        }
        boolean isBlockCancelled = event.useInteractedBlock() == Result.DENY;
        final long identity = JustLootItAccess.getIdentity(dataContainer);
        plugin.logContainerAccess(traceId, "accessing identity=%d inventoryType=%s".formatted(identity, container.getInventory().getType()));
        accessContainer(block.getLocation(), container, dataContainer, event, event.getPlayer(),
            identity, traceId);
        if (!isBlockCancelled && event.useItemInHand() == Result.DENY) {
            VersionHelper helper = plugin.versionHelper();
            helper.triggerItemUsedCriteria(player, block.getLocation(), event.getItem());
            helper.triggerPiglins(player);
        }
        if (JustLootItAccess.hasIdentity(dataContainer)) {
            return;
        }
        plugin.logContainerAccess(traceId, "container identity was removed; persisting the updated block state");
        container.update(false, false);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.MONITOR)
    public void onInteractMonitor(final PlayerInteractEvent event) {
        if (!plugin.containerAccessDiagnostics() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof org.bukkit.block.Container)) {
            return;
        }
        Long traceId = interactionTraces.remove(event);
        if (traceId == null) {
            traceId = plugin.nextContainerAccessTraceId();
            plugin.logContainerAccess(traceId,
                "MONITOR observed a container click that did not reach JustLootIt at LOWEST; another plugin may have cancelled it first");
        }
        plugin.logContainerAccess(traceId,
            "MONITOR final player=%s block=%s location=%s event=%s".formatted(event.getPlayer().getName(), block.getType(),
                formatLocation(block.getLocation()), formatEventState(event)));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Entity entity = event.getRightClicked();
        if (!EntityUtil.isSupportedEntity(entity)) {
            return;
        }
        final PersistentDataContainer dataContainer = entity.getPersistentDataContainer();
        if (!JustLootItAccess.hasIdentity(dataContainer)) {
            return;
        }
        accessContainer(entity.getLocation(), (InventoryHolder) entity, dataContainer, event, event.getPlayer(),
            JustLootItAccess.getIdentity(dataContainer));
        if (event.isCancelled()) {
            plugin.versionHelper().triggerPiglins(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
        final Entity entity = event.getRightClicked();
        if (!EntityUtil.isSupportedEntity(entity)) {
            return;
        }
        final PersistentDataContainer dataContainer = entity.getPersistentDataContainer();
        if (!JustLootItAccess.hasIdentity(dataContainer)) {
            return;
        }
        accessContainer(entity.getLocation(), (InventoryHolder) entity, dataContainer, event, event.getPlayer(),
            JustLootItAccess.getIdentity(dataContainer));
        if (event.isCancelled()) {
            plugin.versionHelper().triggerPiglins(event.getPlayer());
        }
    }

    private void accessContainer(final Location location, final InventoryHolder inventoryHolder, final PersistentDataContainer data,
        final Cancellable event, final Player bukkitPlayer, final long id) {
        final long traceId = plugin.containerAccessDiagnostics() ? plugin.nextContainerAccessTraceId() : 0;
        plugin.logContainerAccess(traceId,
            "access requested from non-block interaction player=%s identity=%d holder=%s location=%s"
                .formatted(bukkitPlayer.getName(), id, inventoryHolder.getClass().getName(), formatLocation(location)));
        accessContainer(location, inventoryHolder, data, event, bukkitPlayer, id, traceId);
    }

    private void accessContainer(final Location location, final InventoryHolder inventoryHolder, final PersistentDataContainer data,
        final Cancellable event, final Player bukkitPlayer, final long id, final long traceId) {
        final PlayerAdapter player = plugin.versionHandler().getPlayer(bukkitPlayer);
        final LootItActor<?> actor = ActorCapability.actor(player);
        plugin.logContainerAccess(traceId,
            "resolved adapters playerAdapter=%s actor=%s".formatted(player.getClass().getName(), actor.getClass().getName()));
        if (player.hasData(BaseLootUIHandler.PLAYER_DATA_LOOTING)) {
            int value = player.getData(BaseLootUIHandler.PLAYER_DATA_LOOTING, Number.class).intValue();
            if (value != 0) {
                plugin.logContainerAccess(traceId, "stopped: access cooldown is active value=%d".formatted(value));
                event.setCancelled(true);
                player.setData(BaseLootUIHandler.PLAYER_DATA_LOOTING, value - 1);
                actor.sendTranslatedMessage(Messages.CONTAINER_ACCESS_WAIT_FOR_ACCESS);
                return;
            }
            // Force close loot ui handler if open after second access
            player.getCapability(PlayerGUICapability.class).ifPresentOrElse(guiCapability -> {
                if (guiCapability.gui().getHandler() instanceof BaseLootUIHandler handler) {
                    plugin.logContainerAccess(traceId,
                        "closing previously open loot handler=%s".formatted(handler.getClass().getName()));
                    handler.onEventClose(bukkitPlayer, guiCapability.gui());
                }
            }, () -> plugin.logContainerAccess(traceId, "warning: player has no GUI capability while closing previous access"));
        }
        final World world = location.getWorld();
        final WorldEntry entryId = new WorldEntry(world, id);
        final UUID playerId = bukkitPlayer.getUniqueId();
        final LevelAdapter level = actor.versionHandler().getLevel(world);
        level.getCapability(StorageCapability.class).ifPresentOrElse(capability -> {
            plugin.logContainerAccess(traceId,
                "level storage capability=%s storage=%s".formatted(capability.getClass().getName(),
                    capability.storage().getClass().getName()));
            if (capability.hasBulkOperationRunning()) {
                plugin.logContainerAccess(traceId, "stopped: level storage has a bulk operation running");
                actor.sendTranslatedMessage(Messages.CONTAINER_ACCESS_STORAGE_BUSY);
                player.removeData(BaseLootUIHandler.PLAYER_DATA_LOOTING);
                return;
            }
            final Stored<Container> dataContainer = capability.storage().read(id);
            if (dataContainer == null) {
                plugin.logContainerAccess(traceId,
                    "stopped: identity=%d was not found in level storage; removing identity from the holder".formatted(id));
                JustLootItAccess.removeIdentity(data);
                return;
            }
            plugin.logContainerAccess(traceId,
                "level container found storedId=%d type=%s; cancelling vanilla interaction"
                    .formatted(dataContainer.id(), dataContainer.value().getClass().getName()));
            event.setCancelled(true);
            player.getCapability(StorageCapability.class).ifPresentOrElse(playerCapability -> {
                final IStorage playerStorage = playerCapability.storage();
                plugin.logContainerAccess(traceId,
                    "player storage capability=%s storage=%s".formatted(playerCapability.getClass().getName(),
                        playerStorage.getClass().getName()));
                final CacheLookupTable lookupTable = CacheLookupTable.retrieve(actor.plugin(), playerStorage);
                final boolean canAccess = dataContainer.value().access(world, playerId);
                plugin.logContainerAccess(traceId, "per-player access result=%s".formatted(canAccess));
                if (!canAccess) {
                    if (lookupTable.access(entryId)) {
                        plugin.logContainerAccess(traceId,
                            "cached lookup hit mappedId=%d".formatted(lookupTable.getEntryIdByMapped(entryId)));
                        final Stored<CachedInventory> storedCachedInventory = playerStorage.read(lookupTable.getEntryIdByMapped(entryId));
                        if (storedCachedInventory == null) {
                            plugin.logContainerAccess(traceId, "cached lookup target is missing; dropping the stale mapping");
                            lookupTable.drop(entryId);
                            actor.logger().warning("Dropped loot of container {0} for player {1} in world {2}", entryId.containerId(),
                                playerId, entryId.worldId());
                        } else {
                            final CachedInventory cachedInventory = storedCachedInventory.value();
                            final int columnAmount = IGuiInventory.getColumnAmount(cachedInventory.getType());
                            if (cachedInventory.size() % columnAmount == 0) {
                                final int rowAmount = cachedInventory.size() / columnAmount;
                                if (((columnAmount != 9) || ((rowAmount <= 6) && (rowAmount >= 1)))) {
                                    player.getCapability(PlayerGUICapability.class).ifPresentOrElse(guiCapability -> {
                                        final IGuiInventory inventory = guiCapability.gui();
                                        player.setData(BaseLootUIHandler.PLAYER_DATA_LOOTING, BaseLootUIHandler.PLAYER_DATA_LOOTING_VALUE);
                                        inventory.attrSet(BaseLootUIHandler.ATTR_ID, storedCachedInventory.id());
                                        inventory.attrSet(CachedLootUIHandler.ATTR_CACHED_INVENTORY, cachedInventory);
                                        inventory.setHandler(CachedLootUIHandler.LOOT_HANDLER);
                                        plugin.logContainerAccess(traceId,
                                            "opening cached loot UI cacheId=%d type=%s size=%d"
                                                .formatted(storedCachedInventory.id(), cachedInventory.getType(), cachedInventory.size()));
                                        inventory.open(bukkitPlayer);
                                        if (inventoryHolder instanceof DoubleChest || inventoryHolder instanceof Lidded) {
                                            inventory.attrSet(BaseLootUIHandler.ATTR_LIDDED_LOCATION, location);
                                            BlockUtil.sendBlockOpen(level, bukkitPlayer, location);
                                        }
                                    }, () -> plugin.logContainerAccess(traceId,
                                        "stopped: player has no GUI capability for cached loot"));
                                    return;
                                }
                            }
                            plugin.logContainerAccess(traceId,
                                "cached inventory is invalid type=%s size=%d columnAmount=%d; deleting cacheId=%d"
                                    .formatted(cachedInventory.getType(), cachedInventory.size(), columnAmount, storedCachedInventory.id()));
                            playerStorage.delete(storedCachedInventory.id());
                        }
                    } else {
                        plugin.logContainerAccess(traceId, "cached lookup miss");
                    }
                    final Duration duration = dataContainer.value().durationUntilNextAccess(world, playerId);
                    if (duration.isNegative()) {
                        plugin.logContainerAccess(traceId, "stopped: container is not repeatable for this player");
                        actor.sendTranslatedBarMessage(Messages.CONTAINER_ACCESS_NOT_REPEATABLE);
                        return;
                    }
                    plugin.logContainerAccess(traceId, "stopped: next access is in %s".formatted(duration));
                    actor.sendTranslatedBarMessage(Messages.CONTAINER_ACCESS_NOT_ACCESSIBLE,
                        Key.of("time", DataHelper.formTimeString(actor, duration)));
                    return;
                }
                player.getCapability(PlayerGUICapability.class).ifPresentOrElse(guiCapability -> {
                    final IGuiInventory inventory = guiCapability.gui();
                    if (!(dataContainer.value() instanceof IInventoryContainer)) {
                        // Do nothing, no need to allocate anything if we have no inventory
                        plugin.logContainerAccess(traceId,
                            "stopped: stored container type has no inventory implementation type=%s"
                                .formatted(dataContainer.value().getClass().getName()));
                        return;
                    }
                    inventory.setHandler(GeneratedLootUIHandler.LOOT_HANDLER);
                    player.setData(BaseLootUIHandler.PLAYER_DATA_LOOTING, CachedLootUIHandler.PLAYER_DATA_LOOTING_VALUE);
                    final long cacheId = lookupTable.acquire(entryId);
                    inventory.attrSet(BaseLootUIHandler.ATTR_ID, cacheId);
                    inventory.attrSet(GeneratedLootUIHandler.ATTR_CONTAINER, dataContainer.value());
                    inventory.attrSet(GeneratedLootUIHandler.ATTR_INVENTORY_HOLDER, inventoryHolder);
                    inventory.attrSet(GeneratedLootUIHandler.ATTR_LOCATION, location);
                    plugin.logContainerAccess(traceId,
                        "opening generated loot UI cacheId=%d containerType=%s holder=%s".formatted(cacheId,
                            dataContainer.value().getClass().getName(), inventoryHolder.getClass().getName()));
                    inventory.open(bukkitPlayer);
                    if (inventoryHolder instanceof DoubleChest || inventoryHolder instanceof Lidded) {
                        inventory.attrSet(CachedLootUIHandler.ATTR_LIDDED_LOCATION, location);
                        BlockUtil.sendBlockOpen(level, bukkitPlayer, location);
                    }
                }, () -> plugin.logContainerAccess(traceId, "stopped: player has no GUI capability for generated loot"));
            }, () -> plugin.logContainerAccess(traceId, "stopped: player has no storage capability"));
        }, () -> {
            plugin.logContainerAccess(traceId, "stopped: level has no storage capability; cancelling vanilla interaction");
            event.setCancelled(true);
        });
    }

    private String formatLocation(final Location location) {
        if (location == null || location.getWorld() == null) {
            return "unknown";
        }
        return "%s:%d,%d,%d".formatted(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private String formatEventState(final PlayerInteractEvent event) {
        return "cancelled=%s,useBlock=%s,useItem=%s".formatted(event.isCancelled(), event.useInteractedBlock(), event.useItemInHand());
    }

}
