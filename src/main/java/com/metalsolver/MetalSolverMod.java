package com.metalsolver;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "metalsolver", name = "Metal Solver", version = "1.0.0",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class MetalSolverMod {

    public static final KeyBinding resetKey =
            new KeyBinding("Reset Solver", Keyboard.KEY_R, "Metal Solver");
    public static final KeyBinding toggleKey =
            new KeyBinding("Toggle Solver", Keyboard.KEY_K, "Metal Solver");

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(resetKey);
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(new MetalSolver());
    }
}
