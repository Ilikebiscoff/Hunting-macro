package com.nablzz.addons.mudworm.path;

import com.nablzz.addons.mudworm.Config;
import com.nablzz.addons.mudworm.path.Cell;
import com.nablzz.addons.mudworm.path.Cells;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldCache {
    private static final int LAYER = 256;
    private final ConcurrentHashMap<Long, Column> cols = new ConcurrentHashMap();
    private final ArrayDeque<Long> todo = new ArrayDeque();
    private ClientLevel level;
    private long lastSchedule;
    private short[] wip;
    private long wipKey;
    private int wipMinY;
    private int wipH;
    private int wipLayer;

    static long key(int cx, int cz) {
        return (long)cx << 32 ^ (long)cz & 0xFFFFFFFFL;
    }

    public void clear() {
        this.cols.clear();
        this.todo.clear();
        this.wip = null;
    }

    public boolean has(BlockPos p) {
        return this.cols.containsKey(WorldCache.key(p.getX() >> 4, p.getZ() >> 4));
    }

    public int columns() {
        return this.cols.size();
    }

    public Reader reader() {
        return new Reader(this);
    }

    public void tick(Minecraft mc, long budgetNs) {
        ClientLevel lv = mc.level;
        LocalPlayer p = mc.player;
        if (lv == null || p == null) {
            return;
        }
        if (lv != this.level) {
            this.clear();
            this.level = lv;
        }
        long now = System.currentTimeMillis();
        int pcx = p.getBlockX() >> 4;
        int pcz = p.getBlockZ() >> 4;
        if (now - this.lastSchedule > 300L) {
            this.lastSchedule = now;
            this.schedule(lv, pcx, pcz, now);
        }
        long deadline = System.nanoTime() + budgetNs;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (System.nanoTime() < deadline && (this.wip != null || this.begin(lv))) {
            this.fillLayer(lv, m, now);
        }
    }

    public void warm(Minecraft mc, long ms) {
        this.lastSchedule = 0L;
        this.tick(mc, ms * 1000000L);
    }

    private void schedule(ClientLevel lv, int pcx, int pcz, long now) {
        int r = Math.max(2, Config.cacheChunks);
        ArrayList<long[]> want = new ArrayList<long[]>();
        for (int dx = -r; dx <= r; ++dx) {
            for (int dz = -r; dz <= r; ++dz) {
                long prio;
                int cz;
                int cx;
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r || !lv.hasChunk(cx = pcx + dx, cz = pcz + dz)) continue;
                long k2 = WorldCache.key(cx, cz);
                Column c = this.cols.get(k2);
                if (c == null) {
                    prio = (long)d2 - 100000L;
                } else {
                    long stale;
                    long l = stale = d2 <= 9 ? 8000L : 45000L;
                    if (now - c.builtAt < stale) continue;
                    prio = d2;
                }
                want.add(new long[]{prio, k2});
            }
        }
        want.sort((a, b) -> Long.compare(a[0], b[0]));
        this.todo.clear();
        for (long[] w : want) {
            this.todo.add(w[1]);
        }
        int far = r + 2;
        this.cols.keySet().removeIf(k -> Math.abs((int)(k >> 32) - pcx) > far || Math.abs((int)k.longValue() - pcz) > far);
    }

    private boolean begin(ClientLevel lv) {
        int cz;
        Long k = this.todo.poll();
        if (k == null) {
            return false;
        }
        int cx = (int)(k >> 32);
        if (!lv.hasChunk(cx, cz = (int)k.longValue())) {
            return true;
        }
        this.wipKey = k;
        this.wipMinY = Config.scanMinY;
        this.wipH = Math.max(16, Config.scanMaxY - Config.scanMinY);
        this.wip = new short[this.wipH * 256];
        this.wipLayer = 0;
        return true;
    }

    private void fillLayer(ClientLevel lv, BlockPos.MutableBlockPos m, long now) {
        int cx = (int)(this.wipKey >> 32);
        int cz = (int)this.wipKey;
        int y = this.wipMinY + this.wipLayer;
        int base = this.wipLayer * 256;
        int x0 = cx << 4;
        int z0 = cz << 4;
        for (int lz = 0; lz < 16; ++lz) {
            for (int lx = 0; lx < 16; ++lx) {
                m.set(x0 + lx, y, z0 + lz);
                if (lv.isOutsideBuildHeight((BlockPos)m)) continue;
                BlockState s = lv.getBlockState((BlockPos)m);
                this.wip[base + (lz << 4) + lx] = Cell.classify((Level)lv, (BlockPos)m, s);
            }
        }
        if (++this.wipLayer >= this.wipH) {
            this.cols.put(this.wipKey, new Column(this.wipMinY, this.wipH, this.wip, now));
            this.wip = null;
        }
    }

    public final class Reader
    implements Cells {
        private Column last;
        private int lcx;
        private int lcz;
        final /* synthetic */ WorldCache this$0;

        public Reader(WorldCache this$0) {
            WorldCache worldCache = this$0;
            Objects.requireNonNull(worldCache);
            this.this$0 = worldCache;
            this.lcx = Integer.MIN_VALUE;
        }

        @Override
        public short at(int x, int y, int z) {
            Column c;
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != this.lcx || cz != this.lcz) {
                this.last = this.this$0.cols.get(WorldCache.key(cx, cz));
                this.lcx = this.last == null ? Integer.MIN_VALUE : cx;
                this.lcz = cz;
            }
            if ((c = this.last) == null) {
                return 0;
            }
            int ly = y - c.minY;
            if (ly < 0) {
                return 0;
            }
            if (ly >= c.h) {
                return 7;
            }
            return c.cells[ly << 8 | (z & 0xF) << 4 | x & 0xF];
        }

        public long[] targets(int cx, int cz) {
            Column c = this.this$0.cols.get(WorldCache.key(cx, cz));
            if (c == null) {
                return null;
            }
            long[] t = c.targets;
            if (t != null) {
                return t;
            }
            long[] out = new long[256];
            int n = 0;
            for (int ly = 0; ly < c.h; ++ly) {
                for (int i = 0; i < 256; ++i) {
                    boolean a2;
                    int idx = ly * 256 + i;
                    if ((c.cells[idx] & 8) == 0) continue;
                    boolean a1 = ly + 1 >= c.h || (c.cells[idx + 256] & 2) != 0;
                    boolean bl = a2 = ly + 2 >= c.h || (c.cells[idx + 512] & 2) != 0;
                    if (!a1 || !a2) continue;
                    if (n == out.length) {
                        out = Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = BlockPos.asLong((int)((cx << 4) + (i & 0xF)), (int)(c.minY + ly), (int)((cz << 4) + (i >> 4)));
                }
            }
            c.targets = t = Arrays.copyOf(out, n);
            return t;
        }
    }

    static final class Column {
        final int minY;
        final int h;
        final short[] cells;
        final long builtAt;
        volatile long[] targets;

        Column(int minY, int h, short[] cells, long builtAt) {
            this.minY = minY;
            this.h = h;
            this.cells = cells;
            this.builtAt = builtAt;
        }
    }
}

