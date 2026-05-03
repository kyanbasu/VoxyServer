package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.LODBulkPayload;
import com.dripps.voxyserver.network.LODClearPayload;
import com.dripps.voxyserver.network.LODReadyPayload;
import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.network.LODServerSettingsPayload;
import com.dripps.voxyserver.network.PreSerializedLodPayload;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class ClientLodReceiver {

    public static void register() {
        // send ready handshake when joining a server
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientLodSettings.prepareForCurrentConnection();
            ClientPlayNetworking.send(new LODReadyPayload());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientLodSettings.reset();
        });

        ClientPlayNetworking.registerGlobalReceiver(LODServerSettingsPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> ClientLodSettings.applyServerSettings(
                    payload.maxLodStreamRadius(), payload.maxSectionsPerTick()));
        });

        ClientPlayNetworking.registerGlobalReceiver(PreSerializedLodPayload.TYPE, (payload, context) -> {
            ClientLevel level = context.client().level;
            if (level == null) return;
            var registryAccess = level.registryAccess();
            
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                LODBulkPayload bulk = payload.decodeBulk(registryAccess);
                
                context.client().execute(() -> {
                    ClientLevel mainLevel = context.client().level;
                    if (mainLevel == null) return;
                    
                    var instance = VoxyCommon.getInstance();
                    if (instance == null) return;

                    WorldIdentifier worldId = WorldIdentifier.of(mainLevel);
                    if (worldId == null) return;

                    WorldEngine engine = instance.getOrCreate(worldId);
                    Mapper mapper = engine.getMapper();
                    
                    java.util.List<SectionData> sectionDataList = new java.util.ArrayList<>();
                    for (LODSectionPayload section : bulk.sections()) {
                        long[] remappedLut = remapLut(section.lutBlockStateIds(), section.lutBiomeIds(),
                                section.lutLight(), mapper, mainLevel);
                        sectionDataList.add(new SectionData(section, remappedLut));
                    }
                    
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        java.util.List<VoxelizedSection> readySections = new java.util.ArrayList<>();
                        
                        for (SectionData data : sectionDataList) {
                            LODSectionPayload sectionPayload = data.payload();
                            int secX = WorldEngine.getX(sectionPayload.sectionKey());
                            int secY = WorldEngine.getY(sectionPayload.sectionKey());
                            int secZ = WorldEngine.getZ(sectionPayload.sectionKey());
                            short[] indexArray = sectionPayload.indexArray();
                            
                            for (int oy = 0; oy < 2; oy++) {
                                for (int oz = 0; oz < 2; oz++) {
                                    for (int ox = 0; ox < 2; ox++) {
                                        VoxelizedSection vs = VoxelizedSection.createEmpty();
                                        vs.setPosition(secX * 2 + ox, secY * 2 + oy, secZ * 2 + oz);

                                        int nonAirCount = 0;
                                        for (int vy = 0; vy < 16; vy++) {
                                            for (int vz = 0; vz < 16; vz++) {
                                                for (int vx = 0; vx < 16; vx++) {
                                                    int wsIdx = ((oy * 16 + vy) << 10) | ((oz * 16 + vz) << 5) | (ox * 16 + vx);
                                                    int vsIdx = (vy << 8) | (vz << 4) | vx;
                                                    long id = data.remappedLut()[indexArray[wsIdx] & 0xFFFF];
                                                    vs.section[vsIdx] = id;
                                                    if (!Mapper.isAir(id)) nonAirCount++;
                                                }
                                            }
                                        }
                                        vs.lvl0NonAirCount = nonAirCount;

                                        WorldConversionFactory.mipSection(vs, mapper);
                                        readySections.add(vs);
                                    }
                                }
                            }
                        }
                        
                        context.client().execute(() -> {
                            for (VoxelizedSection vs : readySections) {
                                WorldUpdater.insertUpdate(engine, vs);
                            }
                        });
                    });
                });
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(LODClearPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleClear(payload));
        });
    }

    private record SectionData(LODSectionPayload payload, long[] remappedLut) {}

    private static long[] remapLut(int[] blockStateIds, int[] biomeIds, byte[] light,
                                    Mapper mapper, ClientLevel level) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        long[] remapped = new long[blockStateIds.length];

        for (int i = 0; i < blockStateIds.length; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(blockStateIds[i]);
            int clientBlockId = (state != null) ? mapper.getIdForBlockState(state) : 0;

            Optional<Holder.Reference<Biome>> biomeHolder = biomeRegistry.getHolder(biomeIds[i]);
            int clientBiomeId = biomeHolder.map(mapper::getIdForBiome).orElse(0);

            remapped[i] = Mapper.composeMappingId(light[i], clientBlockId, clientBiomeId);
        }

        return remapped;
    }

    private static void handleClear(LODClearPayload payload) {
        // dimension change clear is handled by voxy itself when the world changes
        // this is a signal from the server to reset any cached state
    }
}
