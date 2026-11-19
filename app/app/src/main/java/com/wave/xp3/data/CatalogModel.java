package com.wave.xp3.data;

import com.wave.xp3.data.WaveData.*;
import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.install.Receipts;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.sources.Compat;
import com.wave.xp3.core.sources.Index;
import com.wave.xp3.core.sources.PackageManifest;
import com.wave.xp3.core.sources.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projection of an immutable catalog and a read of the phone into screen rows. */
final class CatalogModel {
    private final Index index;
    private final Compat.Phone facts;
    private final Map<String, Plan.Installed> apps;
    private final Map<String, Modules.Module> modules;
    private final Receipts receipts;
    private final Map<String, String> appLabels;
    private final Map<String, Pkg> catalog = new LinkedHashMap<>();
    private final Map<String, Detail> installedDetails = new LinkedHashMap<>();
    final List<Pkg> updates = new ArrayList<>(), upToDate = new ArrayList<>();
    final List<String> rebootPending = new ArrayList<>();
    final Map<String, Shelf> shelves = new LinkedHashMap<>();
    final Shelf all;

    CatalogModel(Index index, Compat.Phone facts, Map<String, Plan.Installed> apps,
                 Map<String, Modules.Module> modules, Receipts receipts, Map<String, String> appLabels) {
        this.index = index; this.facts = facts; this.apps = apps; this.modules = modules; this.receipts = receipts;
        this.appLabels = appLabels;
        for (Index.Entry entry : index.all()) catalog.put(entry.manifest.id, row(entry, label(index.sections(), entry.section)));
        for (Pkg row : catalog.values()) {
            if (row.state == State.UPDATE && row.unfitReason == null) updates.add(row);
            else if (row.state != State.NONE) upToDate.add(row);
        }
        // Only adopt APKs in a source, Wave itself, and packages with receipts. A removed source's receipt
        // supplies the identity, but the phone decides whether it is still installed.
        for (Plan.Installed app : apps.values()) {
            if (receipts.get(app.id) != null || appLabels.containsKey(app.id)) addOrphan(app.id, false, app.version, null);
        }
        for (Modules.Module module : modules.values()) {
            if (module.state.pending()) rebootPending.add(module.name);
            if (receipts.get(module.id) != null || module.state != Modules.State.INSTALLED) {
                addOrphan(module.id, true, module.version, module.name);
            }
        }
        Comparator<Pkg> byName = Comparator.comparing((Pkg row) -> row.name, String.CASE_INSENSITIVE_ORDER).thenComparing(row -> row.id);
        updates.sort(byName); upToDate.sort(byName);
        for (Index.Source source : index.sources()) {
            List<String> featured = source.manifest == null ? Collections.emptyList() : source.manifest.featured;
            shelves.put(source.info.id, shelf(source.packages, source.sections, featured, false));
        }
        all = shelf(index.all(), index.sections(), Collections.emptyList(), true);
    }

    Pkg get(String id) { return all.detail(id) == null ? null : all.detail(id).pkg; }

    List<Pkg> search(String query) {
        List<Pkg> result = new ArrayList<>();
        for (Index.Entry item : index.search(query)) result.add(catalog.get(item.manifest.id));
        return WaveData.frozen(result);
    }

    private void addOrphan(String id, boolean module, String version, String moduleName) {
        Pkg known = catalog.get(id);
        if (known != null || installedDetails.containsKey(id)) return;
        Receipts.Receipt receipt = receipts.get(id);
        String source = receipt == null ? "" : receipt.source;
        String name = moduleName != null ? moduleName : appLabels.getOrDefault(id, id);
        Pkg row = new Pkg(id, name, "", null, version, version, state(id, module, null), null,
            false, module, null, null, source, "");
        upToDate.add(row);
        installedDetails.put(id, new Detail(row, "", "", 0, Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), false));
    }

    private Shelf shelf(List<Index.Entry> entries, List<Index.Section> sections, List<String> featuredIds, boolean merged) {
        Map<String, Detail> details = new LinkedHashMap<>();
        Map<String, List<Pkg>> groups = new LinkedHashMap<>();
        List<Section> sectionRows = new ArrayList<>();
        List<Pkg> rows = new ArrayList<>(), featured = new ArrayList<>();
        for (Index.Section section : sections) sectionRows.add(new Section(section.folder, section.name, section.count()));
        for (Index.Entry entry : entries) {
            PackageManifest m = entry.manifest;
            Pkg row = merged ? catalog.get(m.id) : row(entry, label(sections, entry.section));
            rows.add(row);
            groups.computeIfAbsent(entry.section, key -> new ArrayList<>()).add(row);
            List<Change> changes = new ArrayList<>();
            for (PackageManifest.Change note : m.changes) changes.add(change(note));
            List<String> depends = new ArrayList<>();
            for (PackageManifest.Dependency dep : m.depends) depends.add(name(dep.id) + (dep.min.isEmpty() ? "" : " ≥ " + dep.min));
            details.put(row.id, new Detail(row, m.description, m.author, m.download.size, changes, depends,
                names(m.conflicts), names(m.replaces), Compat.of(entry, facts).note != null));
        }
        for (String id : featuredIds) if (details.containsKey(id)) featured.add(details.get(id).pkg);
        if (merged) details.putAll(installedDetails);
        return new Shelf(featured, rows, sectionRows, groups, details);
    }

    private Pkg row(Index.Entry entry, String section) {
        PackageManifest p = entry.manifest;
        Compat compat = Compat.of(entry, facts);
        boolean module = "module".equals(p.type);
        String installed = module ? (modules.containsKey(p.id) ? modules.get(p.id).version : null) :
            (apps.containsKey(p.id) ? apps.get(p.id).version : null);
        State state = p.supportedType() ? state(p.id, module, p) : State.NONE;
        String reason = compat.reason;
        if (state == State.NEWER && reason == null) reason = "Already on a newer version";
        return new Pkg(p.id, p.name, p.summary, entry.icon, p.version, installed, state, reason,
            "Needs a newer Wave".equals(compat.reason), module, p.changes.isEmpty() ? null : change(p.changes.get(0)),
            entry.source.id, entry.source.name, section);
    }

    private State state(String id, boolean module, PackageManifest p) {
        Modules.Module mod = module ? modules.get(id) : null;
        if (mod != null && mod.state == Modules.State.DISABLED) return State.OFF;
        if (p == null) {
            if (mod != null && mod.state == Modules.State.PENDING_REMOVAL) return State.PENDING_REMOVAL;
            if (mod != null && mod.state == Modules.State.PENDING_INSTALL) return State.PENDING_INSTALL;
            return State.INSTALLED;
        }
        Status here = Status.of(p, (pkg, type) -> {
            if (module) return mod == null ? null : new Status.Installed(mod.version,
                mod.state == Modules.State.PENDING_INSTALL, mod.state == Modules.State.PENDING_REMOVAL);
            Plan.Installed app = apps.get(pkg);
            return app == null ? null : new Status.Installed(app.version, false, false);
        });
        return here == Status.NOT_INSTALLED ? State.NONE : State.valueOf(here.name());
    }

    private static Change change(PackageManifest.Change note) { return new Change(note.version, note.date, note.text); }
    private static String label(List<Index.Section> sections, String folder) {
        for (Index.Section section : sections) if (section.folder.equals(folder)) return section.name;
        return folder;
    }
    private String name(String id) {
        Index.Entry entry = index.get(id);
        if (entry != null) return entry.manifest.name;
        if (modules.containsKey(id)) return modules.get(id).name;
        return appLabels.getOrDefault(id, id);
    }
    private List<String> names(List<String> ids) {
        List<String> result = new ArrayList<>();
        for (String id : ids) result.add(name(id));
        return result;
    }
}
