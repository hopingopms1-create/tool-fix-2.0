package com.metalsolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetalSolver {

    // TODO: action bar ka exact text milne par is regex ko badlo.
    private static final Pattern DIST =
            Pattern.compile("TREASURE:\\s*([0-9]+(?:\\.[0-9]+)?)m");

    private static final long SETTLE_MS = 400;       // rukne ke itne ms baad reading; galat aaye to badhao
    private static final double STILL_EPS = 0.03;   // isse kam hilna = ruka hua
    private static final double MIN_MOVE = 4.0;     // do readings ke beech minimum movement
    private static final double FOUND_RANGE = 3.0;  // chest ke itne paas aane par reset
    private static final double LOCK_RMSE = 1.5;    // readings is error ke andar agree karein tabhi lock
    private static final double MIN_SPREAD = 3.0;   // readings seedhi line me na ho
    private static final int MAX_SAMPLES = 8;

    private static class Sample {
        final double x, z, r;
        Sample(double x, double z, double r) { this.x = x; this.z = z; this.r = r; }
    }

    private final List<Sample> samples = new ArrayList<Sample>();
    private final List<double[]> candidates = new ArrayList<double[]>();
    private double[] locked = null;
    private boolean enabled = true;

    // movement tracking
    private double lastX, lastZ;
    private boolean hasLast = false;
    private boolean moving = true;
    private long stillSince = 0;
    private boolean takenThisStop = false;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        double px = mc.thePlayer.posX, pz = mc.thePlayer.posZ;
        boolean nowMoving = !hasLast || Math.hypot(px - lastX, pz - lastZ) > STILL_EPS;
        if (nowMoving) {
            stillSince = System.currentTimeMillis();
            takenThisStop = false;   // chalna shuru = agli reading ke liye taiyaar
        }
        moving = nowMoving;
        lastX = px; lastZ = pz; hasLast = true;
    }

    @SubscribeEvent
    public void onActionBar(ClientChatReceivedEvent e) {
        if (!enabled || locked != null || e.type != 2) return; // 2 = action bar
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (moving || takenThisStop) return;                              // chal raha hai ya reading le li
        if (System.currentTimeMillis() - stillSince < SETTLE_MS) return;  // abhi ruka hi hai

        String text = StringUtils.stripControlCodes(e.message.getUnformattedText());
        Matcher m = DIST.matcher(text);
        if (!m.find()) return;

        double r = Double.parseDouble(m.group(1));
        double px = mc.thePlayer.posX, pz = mc.thePlayer.posZ;

        if (!samples.isEmpty()) {
            Sample last = samples.get(samples.size() - 1);
            if (Math.hypot(px - last.x, pz - last.z) < MIN_MOVE) {
                takenThisStop = true;
                mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7c[Solver] Thoda aur door jao"));
                return;
            }
        }

        takenThisStop = true;
        samples.add(new Sample(px, pz, r));
        mc.thePlayer.addChatMessage(new ChatComponentText(
                "\u00a7e[Solver] Reading " + samples.size() + ": " + r + "m"));
        solve();
    }

    private void solve() {
        candidates.clear();
        if (samples.size() < 2) return;

        // 2 readings: 2 possible points dikhao
        if (samples.size() == 2) {
            List<double[]> pts = intersect(samples.get(0), samples.get(1));
            if (pts.isEmpty()) {
                // dono readings match nahi hui -> purani wali galat thi, hata do
                samples.remove(0);
                msg("\u00a7c[Solver] Reading match nahi hui, pehli hata di");
                return;
            }
            candidates.addAll(pts);
            return;
        }

        // 3+ readings: galat (outlier) readings hatate hue best-fit nikalo
        List<Sample> active = new ArrayList<Sample>(samples);
        double[] best;
        double rmse;
        while (true) {
            best = fit(active);
            rmse = Math.sqrt(best[2] / active.size());
            if (rmse <= LOCK_RMSE || active.size() <= 3) break;
            active.remove(worstIndex(active, best[0], best[1]));
        }

        // jo readings outlier nikli unhe permanently hata do
        if (active.size() < samples.size()) samples.retainAll(active);

        candidates.add(new double[]{best[0], best[1]});

        if (rmse <= LOCK_RMSE && active.size() >= 3) {
            if (spread(active) >= MIN_SPREAD) {
                locked = new double[]{best[0], best[1]};
            } else {
                msg("\u00a7c[Solver] Readings seedhi line me hain, thoda side me bhi jao");
            }
        } else if (samples.size() >= MAX_SAMPLES) {
            // bahut readings ho gayi par fit achha nahi: sabse galat wali hata do
            samples.remove(worstIndex(samples, best[0], best[1]));
        }
    }

    private void msg(String s) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(s));
    }

    // sabse zyada galat reading ka index
    private int worstIndex(List<Sample> list, double x, double z) {
        int idx = 0;
        double worst = -1;
        for (int i = 0; i < list.size(); i++) {
            Sample s = list.get(i);
            double err = Math.abs(Math.hypot(x - s.x, z - s.z) - s.r);
            if (err > worst) { worst = err; idx = i; }
        }
        return idx;
    }

    // readings ki jagah kitni "side me" faili hai (seedhi line = 0)
    private double spread(List<Sample> list) {
        Sample a = null, b = null;
        double maxD = -1;
        for (Sample p : list) {
            for (Sample q : list) {
                double d = Math.hypot(p.x - q.x, p.z - q.z);
                if (d > maxD) { maxD = d; a = p; b = q; }
            }
        }
        if (a == null || maxD < 0.001) return 0;
        double maxPerp = 0;
        for (Sample s : list) {
            double perp = Math.abs((b.x - a.x) * (a.z - s.z) - (a.x - s.x) * (b.z - a.z)) / maxD;
            if (perp > maxPerp) maxPerp = perp;
        }
        return maxPerp;
    }

    // best-fit: readings ke beech me search karke sabse kam error wala point
    private double[] fit(List<Sample> list) {
        double cx = 0, cz = 0, maxR = 0;
        for (Sample s : list) { cx += s.x; cz += s.z; maxR = Math.max(maxR, s.r); }
        cx /= list.size();
        cz /= list.size();
        double range = maxR + 15;
        double[] best = bestFit(list, cx - range, cx + range, cz - range, cz + range, 0.5);
        return bestFit(list, best[0] - 1, best[0] + 1, best[1] - 1, best[1] + 1, 0.05);
    }

    private double[] bestFit(List<Sample> list, double x0, double x1, double z0, double z1, double step) {
        double bestErr = Double.MAX_VALUE, bx = x0, bz = z0;
        for (double x = x0; x <= x1; x += step) {
            for (double z = z0; z <= z1; z += step) {
                double err = 0;
                for (Sample s : list) {
                    double d = Math.hypot(x - s.x, z - s.z) - s.r;
                    err += d * d;
                }
                if (err < bestErr) { bestErr = err; bx = x; bz = z; }
            }
        }
        return new double[]{bx, bz, bestErr};
    }

    private static List<double[]> intersect(Sample a, Sample b) {
        List<double[]> out = new ArrayList<double[]>();
        double dx = b.x - a.x, dz = b.z - a.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double tol = 1.5;
        if (d == 0 || d > a.r + b.r + tol || d < Math.abs(a.r - b.r) - tol) return out;

        double l = (a.r * a.r - b.r * b.r + d * d) / (2 * d);
        double h = Math.sqrt(Math.max(0, a.r * a.r - l * l));
        double mx = a.x + l * dx / d, mz = a.z + l * dz / d;

        out.add(new double[]{mx + h * dz / d, mz - h * dx / d});
        if (h > 0.01) out.add(new double[]{mx - h * dz / d, mz + h * dx / d});
        return out;
    }

    private static void fillBox(AxisAlignedBB b, int r, int g, int bl, int a) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // neeche
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        // upar
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        // sides
        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();

        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();

        w.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();

        w.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        w.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        t.draw();
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (!enabled || candidates.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view == null || mc.thePlayer == null) return;

        if (locked != null
                && Math.hypot(mc.thePlayer.posX - locked[0], mc.thePlayer.posZ - locked[1]) < FOUND_RANGE) {
            reset();
            return;
        }

        float pt = e.partialTicks;
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glLineWidth(5f);

        for (double[] c : candidates) {
            AxisAlignedBB box = new AxisAlignedBB(c[0] - 0.5, 0, c[1] - 0.5, c[0] + 0.5, 255, c[1] + 0.5)
                    .offset(-vx, -vy, -vz);
            if (locked != null) {
                fillBox(box, 0, 255, 0, 90);                                  // green fill
                RenderGlobal.drawOutlinedBoundingBox(box, 0, 255, 0, 255);    // green outline
            } else {
                fillBox(box, 255, 255, 0, 90);                                // yellow fill
                RenderGlobal.drawOutlinedBoundingBox(box, 255, 255, 0, 255);  // yellow outline
            }
        }

        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (MetalSolverMod.resetKey.isPressed()) {
            reset();
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[Solver] Reset"));
        }
        if (MetalSolverMod.toggleKey.isPressed()) {
            enabled = !enabled;
            if (!enabled) reset();
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7e[Solver] " + (enabled ? "ON" : "OFF")));
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load e) {
        reset();
    }

    private void reset() {
        samples.clear();
        candidates.clear();
        locked = null;
        takenThisStop = false;
        hasLast = false;
    }
}
