package com.wave.xp3.data;

import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.install.Receipts;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.sources.Compat;
import com.wave.xp3.core.sources.SourceStore;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.State;
import com.xp3.common.Check;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CatalogModel: how an index of sources and a read of the phone become the lists the
 * screens show. The sources are real files on disk read by a real SourceStore (the
 * layout the app keeps), the phone is what Modules and Plan.Installed would report.
 */
public final class DataTests {
    private DataTests() {}

    private static final String ONE = "https://one.test/source", TWO = "https://two.test/source", HEAD = "a".repeat(40);
    private static final Compat.Phone PHONE = new Compat.Phone("XP3800", "XP3800", 27, "fingerprint", 1);

    public static void run(Check check) throws Exception {
        check.run("CatalogModel: states, updates and the unfit", () -> states(check));
        check.run("CatalogModel: order, sections and featured", () -> shelves(check));
        check.run("CatalogModel: what stays after a source is gone", () -> kept(check));
        check.run("CatalogModel: Wave's own update", () -> waveUpdate(check));
        check.run("CatalogModel: search", () -> search(check));
        check.run("CatalogModel: nothing at all", () -> empty(check));
    }

    // ---------------------------------------------------------------- the fixture

    private static String sha(String text) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String pkg(String id, String type, String name, String summary, String version, String requires, String extra) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"summary\":\"" + summary + "\","
            + "\"description\":\"Describes " + name + " in detail.\",\"author\":\"someone\",\"version\":\"" + version + "\","
            + "\"download\":{\"url\":\"https://example.invalid/" + id + ".apk\",\"size\":2000,\"sha256\":\"" + "0".repeat(64) + "\"},"
            + "\"requires\":" + requires + (extra.isEmpty() ? "" : "," + extra)
            + ",\"changes\":[{\"version\":\"" + version + "\",\"date\":\"2026-11-12\",\"text\":\"New things.\"}]}";
    }

    /** Two sources: One (format 1) with apps and mods, Two (format 2, which this Wave can't read in full). */
    private static SourceStore seed(Path files) throws Exception {
        write(files.resolve("sources.json"), "[{\"url\":\"" + ONE + "\",\"name\":\"One\",\"commit\":\"" + HEAD + "\",\"lastRefresh\":1700000000000},"
            + "{\"url\":\"" + TWO + "\",\"name\":\"Two\",\"commit\":\"" + HEAD + "\",\"lastRefresh\":1700000000000}]");
        Path one = files.resolve("sources").resolve(sha(ONE)).resolve("tree").resolve(HEAD);
        write(one.resolve("wave.json"), "{\"wave\":1,\"name\":\"One\",\"maintainer\":\"thooton\","
            + "\"sections\":{\"applications\":\"Applications\",\"system\":\"System\"},\"featured\":[\"messages_improved\",\"missing.id\",\"com.list.xp3\"]}");
        String ok = "{\"sdk\":27}";
        write(one.resolve("applications/list/package.json"), pkg("com.list.xp3", "apk", "List", "Lists for a small screen", "1.0.0", ok, ""));
        write(one.resolve("applications/books/package.json"), pkg("com.books.xp3", "apk", "Books", "EPUBs and audiobooks", "2.0.0", ok, ""));
        write(one.resolve("applications/apple/package.json"), pkg("com.apple.xp3", "apk", "apple", "Fruit facts", "1.0.0", ok, ""));
        write(one.resolve("applications/camera/package.json"), pkg("com.camera.xp3", "apk", "Camera", "Takes pictures", "3.0.0", "{\"sdk\":30}", ""));
        write(one.resolve("applications/zebra/package.json"), pkg("com.zebra.xp3", "apk", "Zebra", "Stripes", "1.1.0", "{\"sdk\":27,\"wave\":2}", ""));
        write(one.resolve("applications/both/package.json"), pkg("com.both.xp3", "apk", "Both", "Too new in two ways", "1.0.0", "{\"sdk\":30,\"wave\":2}", ""));
        write(one.resolve("applications/mail/package.json"), pkg("com.mail.xp3", "apk", "Mail", "Gmail on the keypad", "1.0.0", ok,
            "\"depends\":[{\"id\":\"com.list.xp3\",\"min\":\"1.0.0\"},\"messages_improved\"],\"conflicts\":[\"com.rival.xp3\"]"));
        write(one.resolve("system/messages-improved/package.json"), pkg("messages_improved", "module", "Messages Improved", "Group MMS in the right thread", "1.2.0",
            "{\"device\":[\"XP3800\"],\"sdk\":27,\"firmware\":[\"another\"]}", "\"replaces\":[\"messages_argon\"]"));
        write(one.resolve("system/old-ui/package.json"), pkg("old_ui", "module", "Old UI", "The old look", "1.0.0", ok, ""));
        write(one.resolve("system/fresh/package.json"), pkg("fresh_mod", "module", "Fresh Mod", "Just added", "1.0.0", ok, ""));
        Path two = files.resolve("sources").resolve(sha(TWO)).resolve("tree").resolve(HEAD);
        write(two.resolve("wave.json"), "{\"wave\":2,\"name\":\"Two\",\"sections\":{\"games\":\"Games\"},\"featured\":[]}");
        write(two.resolve("games/snake/package.json"), pkg("com.snake.xp3", "apk", "Snake", "The snake game", "1.0.0", ok, ""));
        return new SourceStore(files.toFile());
    }

    private static Map<String, Plan.Installed> apps(String... idsAndVersions) {
        Map<String, Plan.Installed> out = new LinkedHashMap<>();
        for (int i = 0; i < idsAndVersions.length; i += 2) out.put(idsAndVersions[i], new Plan.Installed(idsAndVersions[i], Plan.Kind.APK, idsAndVersions[i + 1], false));
        return out;
    }

    private static Map<String, Modules.Module> modules(String... entries) {
        StringBuilder text = new StringBuilder();
        for (String entry : entries) text.append(entry).append('\n');
        Map<String, Modules.Module> out = new LinkedHashMap<>();
        for (Modules.Module m : Modules.parse(text.toString())) out.put(m.id, m);
        return out;
    }

    private static String mod(String id, String flags, String name, String version) {
        return "@modules " + id + (flags.isEmpty() ? "" : " " + flags) + "\n|name=" + name + "\n|version=" + version + "\n|versionCode=1\n@end";
    }

    private static String update(String id, String name, String version) {
        return mod(id, "", name, version).replace("@modules", "@modules_update");
    }

    private static Receipts receipts(Path dir, String... idKindVersionSource) throws IOException {
        Receipts receipts = Receipts.load(dir.resolve("receipts.json").toFile());
        for (int i = 0; i < idKindVersionSource.length; i += 4) {
            receipts.record(new Receipts.Receipt(idKindVersionSource[i], "apk".equals(idKindVersionSource[i + 1]) ? Plan.Kind.APK : Plan.Kind.MODULE,
                idKindVersionSource[i + 2], idKindVersionSource[i + 3], "0".repeat(64), 1700000000000L));
        }
        return receipts;
    }

    private static List<String> ids(List<Pkg> rows) {
        List<String> ids = new ArrayList<>();
        for (Pkg p : rows) ids.add(p.id);
        return ids;
    }

    private static CatalogModel model(Path dir, Map<String, Plan.Installed> apps, Map<String, Modules.Module> modules, Receipts receipts,
                                      Map<String, String> labels) throws Exception {
        return new CatalogModel(seed(dir.resolve("files")).index(), PHONE, apps, modules, receipts, labels);
    }

    private static Path temp() throws IOException {
        return Files.createTempDirectory("wave-catalog-test");
    }

    // ---------------------------------------------------------------- tests

    private static void states(Check check) throws Exception {
        Path dir = temp();
        // List is older on the phone (an update); Books is current; Apple is newer than the source; Camera is old but needs Android 11;
        // Zebra is old but needs a newer Wave; Messages Improved is older (a module update); Old UI is turned off.
        CatalogModel m = model(dir, apps("com.list.xp3", "0.9.0", "com.books.xp3", "2.0.0", "com.apple.xp3", "9.0.0", "com.camera.xp3", "2.0.0",
                "com.zebra.xp3", "1.0.0"),
            modules(mod("messages_improved", "", "Messages Improved", "1.1.0"), mod("old_ui", "disable", "Old UI", "1.0.0")),
            receipts(dir), Collections.emptyMap());
        check.same("an app that is behind", State.UPDATE, m.get("com.list.xp3").state);
        check.same("it keeps the version on the phone", "0.9.0", m.get("com.list.xp3").installedVersion);
        check.same("and the version on offer", "1.0.0", m.get("com.list.xp3").version);
        check.same("a current app", State.INSTALLED, m.get("com.books.xp3").state);
        check.same("an app newer than the source", State.NEWER, m.get("com.apple.xp3").state);
        check.same("it says why it isn't offered", "A newer version is installed", m.get("com.apple.xp3").unfitReason);
        check.same("one not on the phone", State.NONE, m.get("com.mail.xp3").state);
        check.same("with no version on the phone", null, m.get("com.mail.xp3").installedVersion);
        check.same("a module that is behind", State.UPDATE, m.get("messages_improved").state);
        check.ok("and is a module", m.get("messages_improved").isModule && !m.get("com.list.xp3").isModule);
        check.same("a module that is disabled", State.OFF, m.get("old_ui").state);
        check.same("not on the phone, a module", State.NONE, m.get("fresh_mod").state);

        // What doesn't fit this phone.
        check.same("needs a later Android", "Needs Android 11 or later", m.get("com.camera.xp3").unfitReason);
        check.ok("but not a newer Wave", !m.get("com.camera.xp3").needsNewerWave);
        check.same("it is behind", State.UPDATE, m.get("com.camera.xp3").state);
        check.same("needs a newer Wave", "Needs a newer Wave", m.get("com.zebra.xp3").unfitReason);
        check.ok("and says so as a flag", m.get("com.zebra.xp3").needsNewerWave);
        check.same("a newer Wave comes before a newer Android", "Needs a newer Wave", m.get("com.both.xp3").unfitReason);
        check.same("a source in a newer format: its packages need a newer Wave", "Needs a newer Wave", m.get("com.snake.xp3").unfitReason);
        check.ok("as a flag", m.get("com.snake.xp3").needsNewerWave);
        check.same("a firmware the mod wasn't tested on is a note, not a refusal", null, m.get("messages_improved").unfitReason);
        check.ok("the note is kept for the page", m.all.detail("messages_improved").firmwareNote);
        check.ok("no note when nothing asks for firmware", !m.all.detail("com.list.xp3").firmwareNote);

        // The update list holds only updates this phone can take; the unfit ones are as good as up to date.
        check.same("updates: only those that can be installed", Arrays.asList("com.list.xp3", "messages_improved"), ids(m.updates));
        check.ok("an unfit update isn't an update", !ids(m.updates).contains("com.camera.xp3") && !ids(m.updates).contains("com.zebra.xp3"));
        check.ok("but is listed as installed, still carrying its state", ids(m.upToDate).contains("com.camera.xp3")
            && ids(m.upToDate).contains("com.zebra.xp3"));
        check.same("up to date: what is on the phone", Arrays.asList("apple", "Books", "Camera", "Old UI", "Zebra"), names(m.upToDate));
        check.ok("nothing not installed is listed", !ids(m.upToDate).contains("com.mail.xp3"));
        check.ok("no package is in both", Collections.disjoint(ids(m.updates), ids(m.upToDate)));

        // The rows say where they came from.
        Pkg list = m.get("com.list.xp3");
        check.same("source name", "One", list.sourceName);
        check.same("section name, as the source labels it", "Applications", list.sectionName);
        check.same("newest change", "1.0.0", list.newestChange.version);
        check.same("and its date", "2026-11-12", list.newestChange.date);
        check.same("a module's section", "System", m.get("messages_improved").sectionName);
        check.same("its summary", "Lists for a small screen", list.summary);

        // The page of one package.
        WaveData.Detail mail = m.all.detail("com.mail.xp3");
        check.same("depends say names, with the minimum", Arrays.asList("List ≥ 1.0.0", "Messages Improved"), mail.depends);
        check.same("conflicts that aren't known are shown by id", Arrays.asList("com.rival.xp3"), mail.conflicts);
        check.same("replaces that aren't known are shown by id", Arrays.asList("messages_argon"), m.all.detail("messages_improved").replaces);
        check.same("author", "someone", mail.author);
        check.same("size", 2000L, mail.size);
        check.same("changes", 1, mail.changes.size());
    }

    private static List<String> names(List<Pkg> rows) {
        List<String> names = new ArrayList<>();
        for (Pkg p : rows) names.add(p.name);
        return names;
    }

    private static void shelves(Check check) throws Exception {
        Path dir = temp();
        CatalogModel m = model(dir, apps(), modules(), receipts(dir), Collections.emptyMap());
        String one = sha(ONE), two = sha(TWO);
        WaveData.Shelf shelf = m.shelves.get(one);
        check.same("a shelf per source, the built-in one too", 3, m.shelves.size());
        check.same("packages in the source's own order (by name, ignoring case)",
            Arrays.asList("apple", "Books", "Both", "Camera", "Fresh Mod", "List", "Mail", "Messages Improved", "Old UI", "Zebra"), names(shelf.packages));
        check.same("featured, in the order the source gives, skipping what it doesn't have", Arrays.asList("messages_improved", "com.list.xp3"), ids(shelf.featured));
        check.same("sections, in the source's order", "applications,system", folders(shelf.sections));
        check.same("section counts", Arrays.asList(7, 3), counts(shelf.sections));
        check.same("a section lists its packages", Arrays.asList("apple", "Books", "Both", "Camera", "List", "Mail", "Zebra"), names(shelf.packages("applications")));
        check.same("the other", Arrays.asList("Fresh Mod", "Messages Improved", "Old UI"), names(shelf.packages("system")));
        check.same("a section with nothing", Collections.emptyList(), shelf.packages("nope"));
        check.same("a source's header label", "Applications", shelf.sections.get(0).name);
        check.same("the other source has its own", Arrays.asList("com.snake.xp3"), ids(m.shelves.get(two).packages));
        check.same("featured of a source that has none", Collections.emptyList(), m.shelves.get(two).featured);

        check.same("all sources: every package once, by name", 11, m.all.packages.size());
        check.same("all sources: sections merged", "applications,games,system", sortedFolders(m.all.sections));
        check.same("all sources: counts of a merged section", 7, m.all.sections.stream().filter(s -> s.folder.equals("applications")).findFirst().get().count);
        check.ok("all sources: no featured of its own", m.all.featured.isEmpty());
        check.same("a row is the same object on every page", m.get("com.list.xp3"), m.all.detail("com.list.xp3").pkg);
        check.ok("get of what isn't there", m.get("nothing") == null);
        check.fails("a list can't be changed from a screen", () -> shelf.packages.add(null));
    }

    private static String folders(List<WaveData.Section> sections) {
        StringBuilder out = new StringBuilder();
        for (WaveData.Section s : sections) out.append(out.length() == 0 ? "" : ",").append(s.folder);
        return out.toString();
    }

    private static String sortedFolders(List<WaveData.Section> sections) {
        List<String> names = new ArrayList<>();
        for (WaveData.Section s : sections) names.add(s.folder);
        Collections.sort(names);
        return String.join(",", names);
    }

    private static List<Integer> counts(List<WaveData.Section> sections) {
        List<Integer> out = new ArrayList<>();
        for (WaveData.Section s : sections) out.add(s.count);
        return out;
    }

    /** What the phone has that no source lists (the source was removed, or the package was never in one). */
    private static void kept(Check check) throws Exception {
        Path dir = temp();
        Receipts receipts = receipts(dir, "com.weather.xp3", "apk", "1.0.3", "Old Source", "gone_mod", "module", "0.3", "", "com.books.xp3", "apk", "1.0.0", "One");
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("com.wave.xp3", "Wave");
        labels.put("com.weather.xp3", "Weather");
        labels.put("com.stranger", "Stranger");
        CatalogModel m = model(dir,
            apps("com.wave.xp3", "0.7.0", "com.weather.xp3", "1.0.3", "com.stranger", "5", "com.unknown.app", "1", "com.books.xp3", "2.0.0"),
            modules(mod("gone_mod", "remove", "Gone Mod", "0.3"), mod("fresh_mod", "", "Fresh Mod", "1.0.0"), update("fresh_mod", "Fresh Mod", "1.0.0"),
                mod("unrelated", "", "Unrelated", "1"), mod("old_ui", "disable", "Old UI", "1.0.0")),
            receipts, labels);
        check.same("Wave itself, with no source, is kept", State.INSTALLED, m.get("com.wave.xp3").state);
        check.same("by the name the phone gives it", "Wave", m.get("com.wave.xp3").name);
        check.ok("a kept package has no source id", m.get("com.wave.xp3").sourceId == null);
        check.same("its source name is empty when there is no receipt", "", m.get("com.wave.xp3").sourceName);
        check.same("a receipt's source name is kept", "Old Source", m.get("com.weather.xp3").sourceName);
        check.same("no section", "", m.get("com.weather.xp3").sectionName);
        check.same("no summary", "", m.get("com.weather.xp3").summary);
        check.ok("its detail is empty, not missing", m.all.detail("com.weather.xp3") != null && m.all.detail("com.weather.xp3").description.isEmpty()
            && m.all.detail("com.weather.xp3").author.isEmpty() && m.all.detail("com.weather.xp3").size == 0);
        check.same("it shows the version it has", "1.0.3", m.get("com.weather.xp3").installedVersion);
        check.same("an app with a label but no receipt is kept", State.INSTALLED, m.get("com.stranger").state);
        check.ok("an app with neither is the phone's own business", m.get("com.unknown.app") == null);
        check.same("a removed module waits for its reboot", State.PENDING_REMOVAL, m.get("gone_mod").state);
        check.ok("kept and a module", m.get("gone_mod").isModule);
        check.same("a module of the source that is only waiting to be installed", State.PENDING_INSTALL, m.get("fresh_mod").state);
        check.ok("one installed with no receipt and no source is left alone", m.get("unrelated") == null);
        check.ok("a source's package isn't doubled by a receipt", ids(m.upToDate).stream().filter(id -> id.equals("com.books.xp3")).count() == 1);
        check.same("and keeps its source", "One", m.get("com.books.xp3").sourceName);
        check.same("the reboot list names what waits", Arrays.asList("Fresh Mod", "Gone Mod"), m.rebootPending.stream().sorted().collect(java.util.stream.Collectors.toList()));
        check.ok("a kept package is in no source's shelf", m.shelves.values().stream().noneMatch(s -> s.detail("com.weather.xp3") != null));
        check.ok("nor among all sources' packages", !ids(m.all.packages).contains("com.weather.xp3"));
        check.ok("but its page can be opened", m.all.detail("com.weather.xp3") != null);
        check.same("up to date lists them all, by name", "Books", names(m.upToDate).get(0));
        check.ok("kept packages are never updates", ids(m.updates).stream().noneMatch(id -> id.equals("com.weather.xp3") || id.equals("com.wave.xp3")));
    }

    /** Wave's own update must be one this phone can install, or "Update Wave" would lead to a refusal. */
    private static void waveUpdate(Check check) throws Exception {
        Path dir = temp();
        Path one = dir.resolve("files/sources").resolve(sha(ONE)).resolve("tree").resolve(HEAD);
        CatalogModel fits = model(dir, apps("com.wave.xp3", "0.5.0"), modules(), receipts(dir), Collections.singletonMap("com.wave.xp3", "Wave"));
        check.same("Wave is behind but the source doesn't list it: kept, not an update", State.INSTALLED, fits.get("com.wave.xp3").state);
        write(one.resolve("applications/wave/package.json"), pkg("com.wave.xp3", "apk", "Wave", "Apps and mods", "0.8.0", "{\"sdk\":27}", ""));
        CatalogModel offered = new CatalogModel(new SourceStore(dir.resolve("files").toFile()).index(), PHONE, apps("com.wave.xp3", "0.5.0"), modules(),
            receipts(dir), Collections.emptyMap());
        check.same("a source that lists a newer Wave offers it", Arrays.asList("com.wave.xp3"), ids(offered.updates));
        write(one.resolve("applications/wave/package.json"), pkg("com.wave.xp3", "apk", "Wave", "Apps and mods", "0.8.0", "{\"sdk\":30}", ""));
        CatalogModel tooNew = new CatalogModel(new SourceStore(dir.resolve("files").toFile()).index(), PHONE, apps("com.wave.xp3", "0.5.0"), modules(),
            receipts(dir), Collections.emptyMap());
        check.ok("one this phone can't take is not offered", tooNew.updates.isEmpty());
        check.same("it stays as installed, marked as an update it can't take", State.UPDATE, tooNew.get("com.wave.xp3").state);
    }

    private static void search(Check check) throws Exception {
        Path dir = temp();
        CatalogModel m = model(dir, apps("com.list.xp3", "0.9.0"), modules(), receipts(dir), Collections.emptyMap());
        check.same("a name", Arrays.asList("com.list.xp3"), ids(m.search("list")));
        check.same("ignoring case and padding", Arrays.asList("com.list.xp3"), ids(m.search("  LIST ")));
        check.same("nothing typed", Collections.emptyList(), m.search(""));
        check.same("null", Collections.emptyList(), m.search(null));
        check.same("no match", Collections.emptyList(), m.search("zzzz"));
        // "mail" is in Mail's name; "list" is in Mail's dependencies only, which aren't searched.
        check.same("a summary", Arrays.asList("com.mail.xp3"), ids(m.search("keypad")));
        check.same("a description", Arrays.asList("com.books.xp3"), ids(m.search("describes books")));
        // Names first, then summaries, then descriptions, each group in name order.
        check.same("names, then summaries, then descriptions", Arrays.asList("com.books.xp3", "com.both.xp3", "fresh_mod", "messages_improved", "old_ui", "com.list.xp3",
            "com.mail.xp3"), ids(m.search("o")));
        check.same("a word only descriptions have comes in name order", Arrays.asList("com.apple.xp3", "com.books.xp3"), ids(m.search("in detail")).subList(0, 2));
        List<String> result = ids(m.search("e"));
        check.ok("every result is a row of the catalog", result.stream().allMatch(id -> m.get(id) != null));
        check.same("a result is the same row the lists show", m.get("com.list.xp3"), m.search("list").get(0));
        check.same("with its state", State.UPDATE, m.search("list").get(0).state);
        check.same("packages of a source in a newer format can be found too", Arrays.asList("com.snake.xp3"), ids(m.search("snake")));
        check.fails("results can't be changed", () -> m.search("list").add(null));
    }

    private static void empty(Check check) throws Exception {
        Path dir = temp();
        SourceStore store = new SourceStore(dir.resolve("files").toFile());
        // A fresh install: the default source with nothing fetched yet.
        CatalogModel m = new CatalogModel(store.index(), PHONE, apps(), modules(), receipts(dir), Collections.emptyMap());
        check.ok("no updates", m.updates.isEmpty());
        check.ok("nothing installed", m.upToDate.isEmpty());
        check.ok("nothing waiting", m.rebootPending.isEmpty());
        check.ok("no packages", m.all.packages.isEmpty() && m.all.sections.isEmpty());
        check.same("one shelf for the default source, empty", 1, m.shelves.size());
        check.ok("which has nothing", m.shelves.values().iterator().next().packages.isEmpty() && m.shelves.values().iterator().next().featured.isEmpty());
        check.same("search finds nothing", Collections.emptyList(), m.search("a"));
        check.ok("no package", m.get("a") == null);
        File none = dir.toFile();
        check.ok("temp dir exists", none.isDirectory());
    }
}
