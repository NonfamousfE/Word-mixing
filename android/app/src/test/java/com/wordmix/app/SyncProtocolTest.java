package com.wordmix.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安卓端同步逻辑自检 —— 对**真实服务器**跑。
 *
 * 为什么必须对真服务器测：安卓端是同步协议的第二份实现，
 * 两端理解只要有一点偏差（字段名、版本号语义、比较规则）数据就会错乱，
 * 纯逻辑测试发现不了。这些是普通 JVM 测试，不需要模拟器。
 *
 * 为什么用 Java 写而不是 Kotlin：
 *   实测 AGP 8.9 + Kotlin 2.0.21 下，Kotlin 单元测试类能编译出来
 *   （build/tmp/kotlin-classes/debugUnitTest 里有 .class），
 *   但不会被放进测试的运行时 classpath，报
 *   ClassNotFoundException: com.wordmix.app.SyncProtocolTest。
 *   显式声明 sourceSets 也没用。改用 Java 源码集就正常 ——
 *   Kotlin 的 object/class 本来就能被 Java 直接调用，
 *   所以测试用 Java 写没有损失，只是绕开这个坑。
 */
public class SyncProtocolTest {

    private static SyncConfig cfg() {
        String url = System.getenv("WM_URL");
        if (url == null || url.isEmpty()) url = "http://127.0.0.1:18080";
        String token = System.getenv("WM_TOKEN");
        if (token == null) token = "";
        if (token.isEmpty()) {
            File[] candidates = new File[] {
                new File("../build/server/client.env"),
                new File("../../build/server/client.env"),
                new File(System.getProperty("user.home"), "Desktop/软件/单词混记/build/server/client.env")
            };
            for (File f : candidates) {
                if (f.exists()) {
                    try {
                        for (String line : Files.readAllLines(f.toPath())) {
                            if (line.startsWith("WM_TOKEN=")) {
                                token = line.substring("WM_TOKEN=".length()).trim();
                            }
                            if ((url == null || url.isEmpty() || url.equals("http://127.0.0.1:18080")) && line.startsWith("WM_URL=")) {
                                url = line.substring("WM_URL=".length()).trim();
                            }
                        }
                        if (!token.isEmpty()) break;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return new SyncConfig(url, token);
    }

    private static boolean reachable(SyncConfig c) {
        return AppJson.INSTANCE.bool(ServerApi.INSTANCE.ping(c), "ok", false);
    }

    private static Store tmpStore(String name) throws Exception {
        File d = Files.createTempDirectory("wordmix-" + name).toFile();
        return new Store(d);
    }

    private static Map<String, Object> op(String id, String dev, long seq,
                                          String at, String kind, LinkedHashMap<String, Object> payload) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("dev", dev);
        m.put("seq", seq);
        m.put("at", at);
        m.put("kind", kind);
        m.put("payload", payload);
        return m;
    }

    // ------------------------------------------------------------------
    // 纯逻辑
    // ------------------------------------------------------------------

    @Test
    public void jsonRoundTrip() {
        Map<String, Object> src = new HashMap<>();
        src.put("s", "中文 with \"quotes\" and \n newline");
        src.put("n", 42L);
        src.put("b", true);
        src.put("nil", null);
        List<Object> arr = new ArrayList<>();
        arr.add(1L);
        arr.add(2L);
        src.put("arr", arr);
        Map<String, Object> obj = new HashMap<>();
        obj.put("k", "v");
        src.put("obj", obj);

        String text = AppJson.INSTANCE.write(src);
        Map<String, Object> back = AppJson.INSTANCE.parseObject(text);
        assertEquals("中文 with \"quotes\" and \n newline", AppJson.INSTANCE.str(back, "s", ""));
        assertEquals(42L, AppJson.INSTANCE.num(back, "n", 0L));
        assertEquals("v", AppJson.INSTANCE.str(AppJson.INSTANCE.obj(back, "obj"), "k", ""));
        assertEquals(2, AppJson.INSTANCE.list(back, "arr").size());
    }

    @Test
    public void tombstoneNeverResurrects() {
        Library lib = new Library();
        LinkedHashMap<String, Object> g = Items.INSTANCE.newGroup("组");
        lib.getGroups().add(g);
        LinkedHashMap<String, Object> e = Items.INSTANCE.newEntry(AppJson.INSTANCE.str(g, "id", ""), "plague", "瘟疫");
        e.put("createdAt", "2026-01-01T10:00:00.000000+00:00");
        e.put("updatedAt", "2026-01-01T10:00:00.000000+00:00");
        lib.getEntries().add(e);

        Map<String, Object> addOp = op("devA-1", "devA", 1L,
                "2026-01-01T10:00:00.000000+00:00", "entry.add", OpApplier.INSTANCE.deepCopy(e));
        OpApplier.INSTANCE.apply(lib, addOp);
        assertEquals(1, lib.aliveEntries().size());

        LinkedHashMap<String, Object> del = OpApplier.INSTANCE.deepCopy(e);
        del.put("deleted", true);
        del.put("deletedAt", "2026-01-02T10:00:00.000000+00:00");
        del.put("updatedAt", "2026-01-02T10:00:00.000000+00:00");
        OpApplier.INSTANCE.apply(lib, op("devA-2", "devA", 2L,
                "2026-01-02T10:00:00.000000+00:00", "entry.remove", del));
        assertEquals("删除后不该还活着", 0, lib.aliveEntries().size());
        assertTrue(Items.INSTANCE.isDeleted(lib.findEntry(AppJson.INSTANCE.str(e, "id", ""))));

        // 关键：把旧的 add 再重放一遍，不能把墓碑冲掉
        OpApplier.INSTANCE.apply(lib, addOp);
        assertEquals("重放旧的 add 不该复活已删除的词", 0, lib.aliveEntries().size());
    }

    @Test
    public void tieBreakIsOrderIndependent() {
        final String same = "2026-01-01T10:00:00.000000+00:00";

        LinkedHashMap<String, Object> a = newItem("e_tie", same, "甲");
        LinkedHashMap<String, Object> b = newItem("e_tie", same, "乙");
        Map<String, Object> opA = op("aaa-1", "aaa", 1L, same, "entry.add", a);
        Map<String, Object> opB = op("zzz-1", "zzz", 1L, same, "entry.add", b);

        Library lib1 = new Library();
        List<Map<String, Object>> l1 = new ArrayList<>();
        l1.add(opA);
        l1.add(opB);
        OpApplier.INSTANCE.applyAll(lib1, l1, new HashSet<String>(), null);

        Library lib2 = new Library();
        List<Map<String, Object>> l2 = new ArrayList<>();
        l2.add(opB);
        l2.add(opA);
        OpApplier.INSTANCE.applyAll(lib2, l2, new HashSet<String>(), null);

        String m1 = Items.INSTANCE.meaning(lib1.findEntry("e_tie"));
        String m2 = Items.INSTANCE.meaning(lib2.findEntry("e_tie"));
        assertEquals("时间相同时重放顺序不该影响结果", m1, m2);
        assertEquals("应取设备号较大的那次", "乙", m1);
    }

    @Test
    public void concurrentAddSameWordDeduplication() {
        // 场景：两端初始都有服务器数据 a
        // 客户端 1 和客户端 2 随后分别独立添加了词 c ("cherry")
        // 客户端 1 推送 c，服务端为 a + c
        // 客户端 2 拉取并应用远端数据后，待推队列净化，词库绝不产生 a + c + c
        Library lib = new Library();
        LinkedHashMap<String, Object> g = Items.INSTANCE.newGroup("常用组");
        lib.getGroups().add(g);
        String gid = AppJson.INSTANCE.str(g, "id", "");

        // 基础数据 a
        LinkedHashMap<String, Object> entryA = Items.INSTANCE.newEntry(gid, "apple", "苹果");
        lib.getEntries().add(entryA);

        // 客户端 2 本地独立添加了词 c (cherry, 车厘子)
        LinkedHashMap<String, Object> entryC2 = Items.INSTANCE.newEntry(gid, "cherry", "车厘子");
        lib.getEntries().add(entryC2);
        Map<String, Object> opC2 = op("devB-1", "devB", 1L,
                "2026-01-01T10:00:00.000000+00:00", "entry.add", OpApplier.INSTANCE.deepCopy(entryC2));
        List<Map<String, Object>> pending = new ArrayList<>();
        pending.add(opC2);

        // 客户端 1 推到服务端的词 c (cherry, 樱桃)，被客户端 2 拉取并应用
        LinkedHashMap<String, Object> entryC1 = Items.INSTANCE.newEntry(gid, "cherry", "樱桃");
        Map<String, Object> opC1 = op("devA-1", "devA", 1L,
                "2026-01-01T10:05:00.000000+00:00", "entry.add", OpApplier.INSTANCE.deepCopy(entryC1));

        Map<String, String> gAliases = new HashMap<>();
        Map<String, String> eAliases = new HashMap<>();
        List<Map<String, Object>> remoteOps = new ArrayList<>();
        remoteOps.add(opC1);
        OpApplier.INSTANCE.applyAll(lib, remoteOps, new HashSet<String>(), null, gAliases, eAliases);

        // 1. 验证词库内 cherry 只有 1 个存活条目，绝非 2 个
        int cherryCount = 0;
        String meaning = "";
        for (Map<String, ?> e : lib.aliveEntries()) {
            if ("cherry".equals(Items.INSTANCE.word(e))) {
                cherryCount++;
                meaning = Items.INSTANCE.meaning(e);
            }
        }
        assertEquals("词库内 cherry 只能有 1 个存活条目（非 a+c+c）", 1, cherryCount);
        assertTrue("释义包含有效内容", meaning.contains("樱桃") || meaning.contains("车厘子"));

        // 2. 验证 reconcilePending 清洗掉冗余的 entry.add
        List<Map<String, Object>> cleaned = OpApplier.INSTANCE.reconcilePending(lib, pending, gAliases, eAliases);
        boolean hasEntryAdd = false;
        for (Map<String, Object> opItem : cleaned) {
            if ("entry.add".equals(opItem.get("kind"))) {
                hasEntryAdd = true;
            }
        }
        assertFalse("待推队列中重复的 entry.add 应被完全清洗掉，避免二次推送导致 a+c+c", hasEntryAdd);

        // 3. 同名分组并发新增合并
        LinkedHashMap<String, Object> gDup1 = Items.INSTANCE.newGroup("水果组");
        LinkedHashMap<String, Object> gDup2 = Items.INSTANCE.newGroup("水果组"); // 不同 ID
        LinkedHashMap<String, Object> durian = Items.INSTANCE.newEntry(AppJson.INSTANCE.str(gDup2, "id", ""), "durian", "榴莲");

        Map<String, Object> opG1 = op("devA-2", "devA", 2L, "2026-01-01T10:00:00.000000+00:00", "group.add", gDup1);
        Map<String, Object> opG2 = op("devB-2", "devB", 2L, "2026-01-01T10:01:00.000000+00:00", "group.add", gDup2);
        Map<String, Object> opDurian = op("devB-3", "devB", 3L, "2026-01-01T10:02:00.000000+00:00", "entry.add", durian);

        Library libG = new Library();
        List<Map<String, Object>> gOps = new ArrayList<>();
        gOps.add(opG1);
        gOps.add(opG2);
        gOps.add(opDurian);
        Map<String, String> gMap = new HashMap<>();
        Map<String, String> eMap = new HashMap<>();
        OpApplier.INSTANCE.applyAll(libG, gOps, new HashSet<String>(), null, gMap, eMap);

        assertEquals("同名分组并发新增应合并为一个存活分组", 1, libG.aliveGroups().size());
        assertEquals("组内词条应正常存在", 1, libG.aliveEntries().size());
        assertEquals("词条所属分组 ID 自动重定向归入存活分组",
                AppJson.INSTANCE.str(libG.aliveGroups().get(0), "id", ""),
                AppJson.INSTANCE.str(libG.aliveEntries().get(0), "groupId", ""));
    }

    private static LinkedHashMap<String, Object> newItem(String id, String stamp, String meaning) {
        LinkedHashMap<String, Object> it = new LinkedHashMap<>();
        it.put("id", id);
        it.put("groupId", "g1");
        Map<String, Object> f = new HashMap<>();
        f.put(LibraryKt.F_WORD, "cliff");
        f.put(LibraryKt.F_MEANING, meaning);
        it.put("fields", f);
        it.put("createdAt", stamp);
        it.put("updatedAt", stamp);
        return it;
    }

    // ------------------------------------------------------------------
    // 真实服务器往返
    // ------------------------------------------------------------------

    @Test
    public void serverRoundTrip() throws Exception {
        // 开发与单元测试严禁污染生产服务器环境！仅在显式指定 TEST_LIVE_SERVER=true 时允许连接
        assumeTrue("禁止在日常构建和测试中触碰生产环境，仅在 TEST_LIVE_SERVER=true 时运行",
                "true".equalsIgnoreCase(System.getenv("TEST_LIVE_SERVER")));
        SyncConfig cfg = cfg();
        assumeTrue("服务器不可达，跳过", reachable(cfg));

        String tag = "java" + (System.currentTimeMillis() % 100000);
        Store storeA = tmpStore("A");
        Store storeB = tmpStore("B");

        SyncState stA = storeA.loadState();
        stA.setDeviceId(tag + "-A");
        SyncState stB = storeB.loadState();
        stB.setDeviceId(tag + "-B");
        Library libA = storeA.loadLibrary();
        Library libB = storeB.loadLibrary();

        // ---- A 建组 + 加词 ----
        LinkedHashMap<String, Object> g = Items.INSTANCE.newGroup("联调组-" + tag);
        libA.getGroups().add(g);
        Syncer.INSTANCE.record(stA, "group.add", g);
        LinkedHashMap<String, Object> e1 = Items.INSTANCE.newEntry(AppJson.INSTANCE.str(g, "id", ""), "pc" + tag, "电脑加的词");
        libA.getEntries().add(e1);
        Syncer.INSTANCE.record(stA, "entry.add", e1);
        storeA.saveLibrary(libA);

        SyncResult r = Syncer.INSTANCE.syncOnce(cfg, stA, libA);
        assertTrue("A 首次同步应成功：" + r.getError(), r.getOk());
        assertEquals(2, r.getPushed());
        assertEquals(0, stA.getPending().size());
        assertTrue(stA.getLastVersion() > 0);

        // ---- B 拉取，应看到 ----
        r = Syncer.INSTANCE.syncOnce(cfg, stB, libB);
        assertTrue("B 同步应成功：" + r.getError(), r.getOk());
        assertTrue("B 应看到 A 加的词", hasWord(libB, "pc" + tag));

        // ---- B 加词，A 拉取 ----
        LinkedHashMap<String, Object> e2 = Items.INSTANCE.newEntry(AppJson.INSTANCE.str(g, "id", ""), "ph" + tag, "手机加的词");
        libB.getEntries().add(e2);
        Syncer.INSTANCE.record(stB, "entry.add", e2);
        storeB.saveLibrary(libB);
        assertTrue(Syncer.INSTANCE.syncOnce(cfg, stB, libB).getOk());

        assertTrue(Syncer.INSTANCE.syncOnce(cfg, stA, libA).getOk());
        assertTrue("A 应看到 B 加的词", hasWord(libA, "ph" + tag));

        // ---- A 删词，B 同步后也该删掉 ----
        LinkedHashMap<String, Object> target = libA.findEntry(AppJson.INSTANCE.str(e1, "id", ""));
        target.put("deleted", true);
        target.put("deletedAt", Clock.INSTANCE.nowIso());
        target.put("updatedAt", target.get("deletedAt"));
        Syncer.INSTANCE.record(stA, "entry.remove", target);
        assertTrue(Syncer.INSTANCE.syncOnce(cfg, stA, libA).getOk());
        assertFalse("A 本地应已删除", hasWord(libA, "pc" + tag));

        assertTrue("B 同步前应还有该词", hasWord(libB, "pc" + tag));
        assertTrue(Syncer.INSTANCE.syncOnce(cfg, stB, libB).getOk());
        assertFalse("B 同步后也应删除（墓碑生效）", hasWord(libB, "pc" + tag));

        for (int i = 0; i < 2; i++) {
            Syncer.INSTANCE.syncOnce(cfg, stB, libB);
            Syncer.INSTANCE.syncOnce(cfg, stA, libA);
        }
        assertFalse("来回同步后仍不该复活",
                hasWord(libA, "pc" + tag) || hasWord(libB, "pc" + tag));

        // ---- 离线 → 补推不重复 ----
        long before = AppJson.INSTANCE.num(ServerApi.INSTANCE.ping(cfg), "ops", 0L);
        LinkedHashMap<String, Object> e3 = Items.INSTANCE.newEntry(AppJson.INSTANCE.str(g, "id", ""), "off" + tag, "离线记的词");
        libA.getEntries().add(e3);
        Syncer.INSTANCE.record(stA, "entry.add", e3);

        SyncConfig bad = new SyncConfig(cfg.getUrl(), "wrong-token");
        SyncResult rBad = Syncer.INSTANCE.syncOnce(bad, stA, libA);
        assertFalse("错 token 应失败", rBad.getOk());
        assertTrue("失败后改动应留在队列里", stA.getPending().size() > 0);

        assertTrue("恢复后补推应成功", Syncer.INSTANCE.syncOnce(cfg, stA, libA).getOk());
        assertEquals("补推后队列应清空", 0, stA.getPending().size());
        long after = AppJson.INSTANCE.num(ServerApi.INSTANCE.ping(cfg), "ops", 0L);
        assertEquals("补推不该产生重复", before + 1, after);

        assertTrue(Syncer.INSTANCE.syncOnce(cfg, stB, libB).getOk());
        assertTrue("B 应拿到离线期间记的词", hasWord(libB, "off" + tag));

        storeA.getDir().delete();
        storeB.getDir().delete();
    }

    @Test
    public void appUpdateEndpointReachable() {
        SyncConfig cfg = cfg();
        assumeTrue("服务器不可达，跳过", reachable(cfg));
        // 服务器上可能还没上传 APK；两种情况都算正常，只要不抛异常
        UpdateInfo info = Updater.INSTANCE.check(cfg);
        if (info != null) {
            assertTrue(info.getUrl().endsWith(".apk"));
        }
    }

    @Test
    public void similarityRecommendation() {
        Library lib = new Library();
        LinkedHashMap<String, Object> g = Items.INSTANCE.newGroup("garage组");
        lib.getGroups().add(g);
        String gid = AppJson.INSTANCE.str(g, "id", "");
        lib.getEntries().add(Items.INSTANCE.newEntry(gid, "garage", "车库"));
        lib.getEntries().add(Items.INSTANCE.newEntry(gid, "garbage", "垃圾"));
        lib.getEntries().add(Items.INSTANCE.newEntry(gid, "snack", "点心"));

        List<Similarity.Candidate> cands = Similarity.INSTANCE.similarEntries(lib, "garment", 8, 0.34);
        assertTrue("应推荐相似词", cands.size() >= 2);
        List<String> words = new ArrayList<>();
        for (Similarity.Candidate c : cands) {
            words.add(Items.INSTANCE.word(c.getEntry()));
        }
        assertTrue("应推荐 garage", words.contains("garage"));
        assertTrue("应推荐 garbage", words.contains("garbage"));
        assertFalse("不应推荐 snack", words.contains("snack"));
    }

    @Test
    public void phoneticsLoadAndLookup() {
        Phonetics.INSTANCE.load(() -> new java.io.ByteArrayInputStream(
            "obstruct\tAH0 B S T R AH1 K T\nconsiderable\tk AH0 N S IH1 D ER0 AH0 B AH0 L\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
        assertTrue("词典应当成功加载", Phonetics.INSTANCE.isLoaded());
        String ipa = Phonetics.INSTANCE.ipa("obstruct", "uk");
        assertFalse("obstruct 应有音标", ipa.isEmpty());
        assertTrue("obstruct 音标格式正确", ipa.startsWith("/") && ipa.endsWith("/"));
    }

    private static boolean hasWord(Library lib, String word) {
        for (Map<String, ?> e : lib.aliveEntries()) {
            if (Items.INSTANCE.word(e).equals(word)) return true;
        }
        return false;
    }
}
