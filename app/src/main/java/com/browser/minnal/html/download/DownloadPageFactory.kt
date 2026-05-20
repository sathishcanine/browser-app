package com.browser.minnal.html.download

import com.browser.minnal.R
import com.browser.minnal.browser.theme.ThemeProvider
import com.browser.minnal.constant.FILE
import com.browser.minnal.html.HtmlPageFactory
import android.app.Application
import io.reactivex.rxjava3.core.Single
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

/**
 * Renders the in-app **Downloads** tab as a self-contained Material-3 page.
 *
 * Architectural choices:
 *
 * 1. The page is a single static HTML file generated once per visit (no server-rendered list).
 *    All download data is fetched at runtime from `window.MinnalDownloads.list()` (see
 *    [com.browser.minnal.download.manager.DownloadsBridge]) and re-polled every ~750 ms so
 *    progress, speed and ETA update live without a tab reload.
 *
 * 2. The page injects the current theme palette into CSS custom properties (light/dark, primary
 *    color, surfaces) so it visually matches the rest of the app and reacts to theme changes
 *    on the next visit.
 *
 * 3. All actions (Pause / Resume / Cancel / Retry / Open / Share / Delete) round-trip through
 *    the JS bridge so SQLite stays the source of truth and the system notification + WorkManager
 *    state stay in sync with what the user sees on screen.
 */
class DownloadPageFactory @Inject constructor(
    private val application: Application,
    private val themeProvider: ThemeProvider
) : HtmlPageFactory {

    override fun buildPage(): Single<String> = Single.fromCallable {
        val file = createDownloadsPageFile()
        FileWriter(file, false).use { it.write(buildHtml()) }
        "$FILE${file.absolutePath}"
    }

    private fun createDownloadsPageFile(): File {
        val generatedHtml = File(application.filesDir, "generated-html")
        generatedHtml.mkdirs()
        return File(generatedHtml, FILENAME)
    }

    private fun Int.toCssColor(): String {
        // Android color is AARRGGBB; CSS expects #RRGGBBAA.
        val argb = this.toLong() and 0xFFFFFFFFL
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return "#%02x%02x%02x%02x".format(r, g, b, a)
    }

    private fun palette(): Map<String, String> {
        val surface = themeProvider.color(R.attr.colorPrimary)
        val divider = themeProvider.color(R.attr.autoCompleteBackgroundColor)
        val title = themeProvider.color(R.attr.autoCompleteTitleColor)
        val subtitle = themeProvider.color(R.attr.autoCompleteUrlColor)
        return mapOf(
            "--surface" to surface.toCssColor(),
            "--surface-variant" to divider.toCssColor(),
            "--on-surface" to title.toCssColor(),
            "--on-surface-variant" to subtitle.toCssColor(),
            // Slightly transparent overlays for hover / press feedback. We mix with the title
            // color since that gives a sensible value in both light and dark themes.
            "--state-hover" to title.withAlpha(0x14).toCssColor(),
            "--state-press" to title.withAlpha(0x29).toCssColor(),
            // Brand-ish accent for primary buttons and progress bars. We deliberately use a
            // fixed teal so the page stays recognizable regardless of the user's theme.
            "--primary" to "#1ea7a7ff",
            "--on-primary" to "#ffffffff",
            "--primary-soft" to "#1ea7a72e",
            "--success" to "#23a55aff",
            "--warning" to "#f0b232ff",
            "--error" to "#e54848ff",
            "--error-soft" to "#e548482e"
        )
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return (this and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    private fun buildHtml(): String {
        val cssVars = palette().entries.joinToString(separator = "\n            ") { (k, v) ->
            "$k: $v;"
        }
        val title = application.getString(R.string.action_downloads)
        val emptyTitle = application.getString(R.string.downloads_empty_title)
        val emptyBody = application.getString(R.string.downloads_empty_body)
        val noScript = application.getString(R.string.downloads_no_script)
        val labels = downloadLabelsJson()

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>$title</title>
<style>
:root {
    $cssVars
    --radius-card: 16px;
    --radius-pill: 999px;
    --shadow-card: 0 1px 2px rgba(0,0,0,0.08), 0 1px 6px rgba(0,0,0,0.06);
    --space-1: 4px;
    --space-2: 8px;
    --space-3: 12px;
    --space-4: 16px;
    --space-5: 20px;
    --space-6: 24px;
    color-scheme: light dark;
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: var(--surface); color: var(--on-surface);
    font-family: -apple-system, "Roboto", "Segoe UI", system-ui, sans-serif; }
body { padding-bottom: 96px; -webkit-tap-highlight-color: transparent; }

.app-bar { position: sticky; top: 0; z-index: 10; background: var(--surface);
    padding: var(--space-5) var(--space-4) var(--space-3);
    border-bottom: 1px solid var(--surface-variant); }
.app-bar h1 { margin: 0 0 var(--space-1); font-size: 22px; font-weight: 600; letter-spacing: -0.01em; }
.app-bar .summary { color: var(--on-surface-variant); font-size: 13px; }

.controls { display: flex; gap: var(--space-2); align-items: center;
    margin-top: var(--space-4); }
.search { position: relative; flex: 1 1 auto; }
.search input { width: 100%; padding: 10px 14px 10px 40px;
    border-radius: var(--radius-pill); border: 1px solid var(--surface-variant);
    background: var(--surface-variant); color: var(--on-surface);
    font-size: 14px; outline: none; transition: border-color 120ms ease; }
.search input:focus { border-color: var(--primary); }
.search input::placeholder { color: var(--on-surface-variant); }
.search svg { position: absolute; left: 12px; top: 50%; transform: translateY(-50%);
    width: 18px; height: 18px; color: var(--on-surface-variant); pointer-events: none; }
.icon-btn { display: inline-flex; align-items: center; justify-content: center;
    width: 40px; height: 40px; border-radius: var(--radius-pill); border: 0;
    background: transparent; color: var(--on-surface); cursor: pointer; padding: 0; }
.icon-btn:hover { background: var(--state-hover); }
.icon-btn:active { background: var(--state-press); }
.icon-btn svg { width: 22px; height: 22px; }

.chips { display: flex; gap: var(--space-2); overflow-x: auto;
    margin-top: var(--space-4); padding-bottom: 2px; scrollbar-width: none; }
.chips::-webkit-scrollbar { display: none; }
.chip { flex: 0 0 auto; padding: 8px 14px; border-radius: var(--radius-pill);
    border: 1px solid var(--surface-variant); background: transparent;
    color: var(--on-surface); font-size: 13px; cursor: pointer;
    transition: background 120ms ease, color 120ms ease, border-color 120ms ease; }
.chip:hover { background: var(--state-hover); }
.chip.active { background: var(--primary-soft); color: var(--on-surface);
    border-color: var(--primary); }
.chip .count { color: var(--on-surface-variant); margin-left: 6px; font-variant-numeric: tabular-nums; }
.chip.active .count { color: var(--on-surface); }

.list { padding: var(--space-3) var(--space-3) 0; display: flex; flex-direction: column; gap: var(--space-3); }

.card { background: var(--surface-variant); border-radius: var(--radius-card);
    padding: var(--space-4); display: flex; gap: var(--space-3);
    box-shadow: var(--shadow-card); position: relative;
    transition: transform 120ms ease, background 120ms ease; }
.card.selected { background: var(--primary-soft); }
.card .file-icon { flex: 0 0 auto; width: 44px; height: 44px; border-radius: 12px;
    display: flex; align-items: center; justify-content: center; background: var(--surface);
    color: var(--primary); }
.card .file-icon svg { width: 26px; height: 26px; }
.card .body { flex: 1 1 auto; min-width: 0; }
.card .title-row { display: flex; align-items: baseline; gap: var(--space-2); }
.card .title { font-size: 15px; font-weight: 600; color: var(--on-surface);
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1 1 auto; }
.card .menu-btn { flex: 0 0 auto; width: 32px; height: 32px; border-radius: var(--radius-pill);
    border: 0; background: transparent; color: var(--on-surface-variant); cursor: pointer;
    margin: -6px -6px -6px 0; }
.card .menu-btn:hover { background: var(--state-hover); color: var(--on-surface); }
.card .menu-btn svg { width: 18px; height: 18px; vertical-align: middle; }

.card .meta { font-size: 12px; color: var(--on-surface-variant); margin-top: 2px;
    display: flex; flex-wrap: wrap; gap: 0 var(--space-2); }
.card .meta .sep::before { content: "·"; padding-right: var(--space-2); }
.card .meta .err { color: var(--error); }
.card .meta .ok { color: var(--success); }

.progress { margin-top: 10px; height: 6px; border-radius: 3px; overflow: hidden;
    background: var(--surface); position: relative; }
.progress > .bar { position: absolute; left: 0; top: 0; bottom: 0;
    background: linear-gradient(90deg, var(--primary), color-mix(in srgb, var(--primary) 60%, white));
    border-radius: inherit; transition: width 240ms ease; }
.progress.indeterminate > .bar { width: 35%; left: -35%; animation: ind 1.4s linear infinite; }
@keyframes ind { 0% { left: -35%; } 100% { left: 100%; } }

.actions { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-top: 12px; }
.btn { -webkit-appearance: none; appearance: none; border: 1px solid var(--surface);
    background: var(--surface); color: var(--on-surface); padding: 8px 14px;
    font-size: 13px; font-weight: 500; border-radius: var(--radius-pill); cursor: pointer;
    transition: background 120ms ease, color 120ms ease, border-color 120ms ease;
    display: inline-flex; align-items: center; gap: 6px; }
.btn:hover { background: var(--state-hover); }
.btn.primary { background: var(--primary); color: var(--on-primary); border-color: var(--primary); }
.btn.primary:hover { filter: brightness(0.95); }
.btn.danger { color: var(--error); border-color: var(--error-soft); }
.btn.danger:hover { background: var(--error-soft); }
.btn svg { width: 14px; height: 14px; }

/* Per-row popup menu */
.popup { position: absolute; right: 12px; top: 44px; z-index: 5;
    background: var(--surface); border: 1px solid var(--surface-variant);
    border-radius: 12px; padding: 4px; box-shadow: var(--shadow-card); min-width: 180px;
    display: none; }
.popup.open { display: block; }
.popup button { display: flex; align-items: center; gap: 10px; width: 100%; text-align: left;
    background: transparent; border: 0; color: var(--on-surface); padding: 10px 12px;
    font-size: 14px; cursor: pointer; border-radius: 8px; }
.popup button:hover { background: var(--state-hover); }
.popup button.danger { color: var(--error); }
.popup button svg { width: 16px; height: 16px; }

/* Status badge */
.badge { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px;
    border-radius: var(--radius-pill); font-size: 11px; font-weight: 600;
    background: var(--surface); color: var(--on-surface-variant);
    text-transform: uppercase; letter-spacing: 0.04em; }
.badge.running { color: var(--primary); background: var(--primary-soft); }
.badge.failed { color: var(--error); background: var(--error-soft); }
.badge.paused { color: var(--warning); }
.badge.completed { color: var(--success); }
.badge.cancelled { color: var(--on-surface-variant); }

/* Empty state */
.empty { padding: 64px 24px; text-align: center; color: var(--on-surface-variant); }
.empty svg { width: 96px; height: 96px; margin-bottom: 16px; opacity: 0.65; }
.empty h2 { font-size: 18px; font-weight: 600; margin: 0 0 6px; color: var(--on-surface); }
.empty p { margin: 0; font-size: 14px; }

/* Bulk-action bar */
.bulk-bar { position: fixed; left: 12px; right: 12px; bottom: 16px;
    background: var(--surface); border: 1px solid var(--surface-variant);
    border-radius: var(--radius-card); padding: 10px 12px;
    box-shadow: 0 6px 18px rgba(0,0,0,0.18);
    display: none; align-items: center; gap: 12px; z-index: 20; }
.bulk-bar.open { display: flex; }
.bulk-bar .label { flex: 1 1 auto; font-size: 14px; font-weight: 500; }

/* Toast */
.toast { position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%);
    background: var(--on-surface); color: var(--surface);
    padding: 10px 16px; border-radius: var(--radius-pill); font-size: 13px;
    box-shadow: var(--shadow-card); opacity: 0; pointer-events: none;
    transition: opacity 180ms ease, transform 180ms ease; z-index: 30; }
.toast.open { opacity: 1; transform: translate(-50%, -8px); }

@media (prefers-color-scheme: dark) {
    .progress { background: rgba(255,255,255,0.08); }
}
</style>
</head>
<body>
<header class="app-bar">
    <h1>$title</h1>
    <div class="summary" id="summary">…</div>
    <div class="controls">
        <div class="search">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>
            </svg>
            <input id="search" type="search" placeholder="Search downloads" autocomplete="off">
        </div>
    </div>
    <div class="chips" id="chips"></div>
</header>

<main class="list" id="list" aria-live="polite"></main>

<noscript>
    <div class="empty">
        <h2>$noScript</h2>
    </div>
</noscript>

<template id="empty-tpl">
    <div class="empty">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
            stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>
        </svg>
        <h2>$emptyTitle</h2>
        <p>$emptyBody</p>
    </div>
</template>

<div class="bulk-bar" id="bulk-bar">
    <span class="label" id="bulk-label">0 selected</span>
    <button class="btn" id="bulk-cancel">Cancel</button>
    <button class="btn danger" id="bulk-delete">Delete</button>
</div>

<div class="toast" id="toast" role="status"></div>

<script>
(function() {
    "use strict";
    var BRIDGE = (typeof MinnalDownloads !== "undefined") ? MinnalDownloads : null;
    var LABELS = $labels;

    var state = {
        items: [],
        filter: "all",
        query: "",
        openMenuUrl: null,
        selected: new Set()
    };

    var els = {
        summary: document.getElementById("summary"),
        list: document.getElementById("list"),
        chips: document.getElementById("chips"),
        search: document.getElementById("search"),
        toast: document.getElementById("toast"),
        bulkBar: document.getElementById("bulk-bar"),
        bulkLabel: document.getElementById("bulk-label"),
        bulkCancel: document.getElementById("bulk-cancel"),
        bulkDelete: document.getElementById("bulk-delete"),
        emptyTpl: document.getElementById("empty-tpl")
    };

    // --- Data refresh -------------------------------------------------------
    function refresh() {
        if (!BRIDGE) return;
        try {
            var json = BRIDGE.list();
            var arr = JSON.parse(json || "[]");
            state.items = Array.isArray(arr) ? arr : [];
        } catch (e) {
            state.items = [];
        }
        render();
    }

    function startPolling() {
        refresh();
        setInterval(refresh, 750);
    }

    // --- Formatting helpers -------------------------------------------------
    function formatBytes(bytes) {
        if (bytes == null || bytes < 0 || isNaN(bytes)) return LABELS.unknownSize;
        if (bytes < 1024) return bytes + " B";
        var units = ["KB", "MB", "GB", "TB"];
        var u = -1;
        do { bytes /= 1024; u++; } while (bytes >= 1024 && u < units.length - 1);
        return bytes.toFixed(bytes >= 10 ? 0 : 1) + " " + units[u];
    }

    function formatSpeed(bps) {
        if (!bps || bps <= 0) return "";
        return formatBytes(bps) + "/s";
    }

    function formatEta(seconds) {
        if (seconds == null || seconds <= 0) return "";
        if (seconds < 60) return Math.max(1, Math.round(seconds)) + "s left";
        if (seconds < 3600) return Math.round(seconds / 60) + "m left";
        var h = Math.floor(seconds / 3600);
        var m = Math.round((seconds % 3600) / 60);
        return h + "h " + m + "m left";
    }

    function relativeTime(ts) {
        if (!ts) return "";
        var diff = Date.now() - ts;
        if (diff < 60000) return LABELS.justNow;
        if (diff < 3600000) return Math.floor(diff / 60000) + "m ago";
        if (diff < 86400000) return Math.floor(diff / 3600000) + "h ago";
        return Math.floor(diff / 86400000) + "d ago";
    }

    function escape(s) {
        if (s == null) return "";
        return String(s).replace(/[&<>"']/g, function(c) {
            return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[c];
        });
    }

    function statusLabel(status) {
        return LABELS.status[status] || status;
    }

    // --- File-type icons ----------------------------------------------------
    function iconFor(item) {
        var mime = (item.mimeType || "").toLowerCase();
        var ext = (item.title || "").toLowerCase().split(".").pop();
        if (mime.indexOf("video/") === 0 || ["mp4","mkv","mov","avi","webm","flv","m4v"].indexOf(ext) !== -1) return ICON.video;
        if (mime.indexOf("audio/") === 0 || ["mp3","wav","flac","aac","m4a","ogg","opus"].indexOf(ext) !== -1) return ICON.audio;
        if (mime.indexOf("image/") === 0 || ["jpg","jpeg","png","gif","webp","heic","svg"].indexOf(ext) !== -1) return ICON.image;
        if (["zip","rar","7z","tar","gz","bz2","xz"].indexOf(ext) !== -1) return ICON.archive;
        if (["apk","xapk","aab"].indexOf(ext) !== -1) return ICON.apk;
        if (mime === "application/pdf" || ext === "pdf") return ICON.pdf;
        if (mime.indexOf("text/") === 0 ||
            ["doc","docx","odt","rtf","txt","md","csv","xls","xlsx","ppt","pptx"].indexOf(ext) !== -1) return ICON.document;
        return ICON.file;
    }

    // SVG library (Material Symbols rounded variants, simplified inline so the page is offline).
    var ICON = {
        file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>',
        video: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>',
        audio: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
        image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
        archive: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" x2="14" y1="12" y2="12"/></svg>',
        apk: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M5 16V8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v8"/><path d="M3 16h18v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"/><path d="M8 6V4M16 6V4"/></svg>',
        pdf: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><text x="8" y="18" font-size="6" font-weight="700" fill="currentColor" stroke="none">PDF</text></svg>',
        document: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="8" x2="16" y1="13" y2="13"/><line x1="8" x2="16" y1="17" y2="17"/></svg>',
        pause: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>',
        play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
        cancel: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>',
        retry: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>',
        open: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>',
        share: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>',
        copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
        trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>',
        more: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>'
    };

    // --- Filter / search ----------------------------------------------------
    var FILTERS = [
        { id: "all", label: LABELS.filters.all, match: function() { return true; } },
        { id: "active", label: LABELS.filters.active,
            match: function(i) { return ["PENDING","RUNNING","RETRYING","PAUSED"].indexOf(i.status) !== -1; } },
        { id: "completed", label: LABELS.filters.completed,
            match: function(i) { return i.status === "COMPLETED"; } },
        { id: "failed", label: LABELS.filters.failed,
            match: function(i) { return i.status === "FAILED" || i.status === "CANCELLED"; } }
    ];

    function filteredItems() {
        var f = FILTERS.find(function(x) { return x.id === state.filter; }) || FILTERS[0];
        var q = state.query.trim().toLowerCase();
        return state.items.filter(function(i) {
            if (!f.match(i)) return false;
            if (!q) return true;
            return (i.title || "").toLowerCase().indexOf(q) !== -1
                || (i.url || "").toLowerCase().indexOf(q) !== -1;
        });
    }

    function counts() {
        var c = { all: state.items.length, active: 0, completed: 0, failed: 0 };
        state.items.forEach(function(i) {
            FILTERS.forEach(function(f) { if (f.id !== "all" && f.match(i)) c[f.id]++; });
        });
        return c;
    }

    // --- Renderers ----------------------------------------------------------
    function renderChips() {
        var c = counts();
        els.chips.innerHTML = FILTERS.map(function(f) {
            var n = c[f.id];
            return '<button class="chip' + (f.id === state.filter ? ' active' : '') + '" data-filter="' + f.id + '">'
                + escape(f.label) + ' <span class="count">' + n + '</span></button>';
        }).join("");
        Array.prototype.forEach.call(els.chips.querySelectorAll(".chip"), function(btn) {
            btn.addEventListener("click", function() {
                state.filter = btn.getAttribute("data-filter");
                renderChips();
                renderList();
            });
        });
    }

    function renderSummary() {
        var total = state.items.length;
        var active = state.items.filter(function(i) {
            return ["PENDING","RUNNING","RETRYING"].indexOf(i.status) !== -1;
        }).length;
        var completedBytes = state.items.reduce(function(sum, i) {
            return i.status === "COMPLETED" ? sum + (i.totalBytes > 0 ? i.totalBytes : 0) : sum;
        }, 0);
        var parts = [];
        parts.push(total === 1 ? LABELS.summary.one : LABELS.summary.other.replace("%d", total));
        if (active > 0) parts.push(LABELS.summary.active.replace("%d", active));
        if (completedBytes > 0) parts.push(formatBytes(completedBytes) + " " + LABELS.summary.completedBytes);
        els.summary.textContent = parts.join(" · ");
    }

    function renderList() {
        var items = filteredItems();
        if (!items.length) {
            els.list.innerHTML = "";
            els.list.appendChild(els.emptyTpl.content.cloneNode(true));
            updateBulk();
            return;
        }
        els.list.innerHTML = items.map(renderCard).join("");
        bindCardEvents();
        updateBulk();
    }

    function renderCard(item) {
        var status = item.status || "PENDING";
        var isActive = ["PENDING","RUNNING","RETRYING"].indexOf(status) !== -1;
        var isPaused = status === "PAUSED";
        var isFailed = status === "FAILED" || status === "CANCELLED";
        var isDone = status === "COMPLETED";
        var pct = (item.totalBytes > 0)
            ? Math.min(100, Math.max(0, Math.floor((item.bytesDownloaded * 100) / item.totalBytes)))
            : -1;
        var bar = "";
        if (isActive || isPaused) {
            bar = '<div class="progress' + (pct < 0 ? ' indeterminate' : '') + '">'
                + '<div class="bar" style="width:' + (pct < 0 ? 0 : pct) + '%"></div></div>';
        }
        var meta = renderMeta(item, status, pct);
        var actions = renderActions(item, status);
        var menu = renderMenu(item, status);
        var fileIcon = iconFor(item);
        var selected = state.selected.has(item.url) ? " selected" : "";

        return '<article class="card' + selected + '" data-url="' + escape(item.url) + '">'
            + '<div class="file-icon">' + fileIcon + '</div>'
            + '<div class="body">'
            +   '<div class="title-row">'
            +     '<div class="title" title="' + escape(item.title) + '">' + escape(item.title || item.url) + '</div>'
            +     '<button class="menu-btn" data-action="menu" aria-label="More">' + ICON.more + '</button>'
            +   '</div>'
            +   meta
            +   bar
            +   actions
            + '</div>'
            + menu
            + '</article>';
    }

    function renderMeta(item, status, pct) {
        var bits = [];
        var statusBadge = '<span class="badge ' + status.toLowerCase() + '">' + statusLabel(status) + '</span>';
        bits.push(statusBadge);

        if (status === "RUNNING" || status === "RETRYING" || status === "PAUSED") {
            var dl = formatBytes(item.bytesDownloaded || 0);
            var total = item.totalBytes > 0 ? formatBytes(item.totalBytes) : LABELS.unknownSize;
            bits.push('<span class="sep">' + escape(dl + " / " + total) + '</span>');
            if (pct >= 0) bits.push('<span class="sep">' + pct + '%</span>');
            var spd = formatSpeed(item.bytesPerSecond);
            if (spd) bits.push('<span class="sep">' + escape(spd) + '</span>');
            var eta = formatEta(item.totalBytes > 0 && item.bytesPerSecond > 0
                ? (item.totalBytes - item.bytesDownloaded) / item.bytesPerSecond : -1);
            if (eta) bits.push('<span class="sep">' + escape(eta) + '</span>');
        } else if (status === "PENDING") {
            bits.push('<span class="sep">' + LABELS.queued + '</span>');
        } else if (status === "COMPLETED") {
            var sz = item.totalBytes > 0 ? formatBytes(item.totalBytes) :
                (item.contentSize && item.contentSize !== "?" ? item.contentSize : "");
            if (sz) bits.push('<span class="sep ok">' + escape(sz) + '</span>');
        } else if (status === "FAILED" && item.errorMessage) {
            bits.push('<span class="sep err">' + escape(item.errorMessage) + '</span>');
        }
        if (item.updatedAt) bits.push('<span class="sep">' + escape(relativeTime(item.updatedAt)) + '</span>');
        return '<div class="meta">' + bits.join(" ") + '</div>';
    }

    function renderActions(item, status) {
        var btns = [];
        if (status === "RUNNING" || status === "RETRYING" || status === "PENDING") {
            btns.push('<button class="btn" data-action="pause">' + ICON.pause + ' ' + LABELS.actions.pause + '</button>');
            btns.push('<button class="btn" data-action="cancel">' + ICON.cancel + ' ' + LABELS.actions.cancel + '</button>');
        } else if (status === "PAUSED") {
            btns.push('<button class="btn primary" data-action="resume">' + ICON.play + ' ' + LABELS.actions.resume + '</button>');
            btns.push('<button class="btn" data-action="cancel">' + ICON.cancel + ' ' + LABELS.actions.cancel + '</button>');
        } else if (status === "FAILED" || status === "CANCELLED") {
            btns.push('<button class="btn primary" data-action="retry">' + ICON.retry + ' ' + LABELS.actions.retry + '</button>');
            btns.push('<button class="btn danger" data-action="delete">' + ICON.trash + ' ' + LABELS.actions.delete + '</button>');
        } else if (status === "COMPLETED") {
            btns.push('<button class="btn primary" data-action="open">' + ICON.open + ' ' + LABELS.actions.open + '</button>');
        }
        return btns.length ? '<div class="actions">' + btns.join("") + '</div>' : "";
    }

    function renderMenu(item, status) {
        var rows = [];
        if (status === "COMPLETED") {
            rows.push('<button data-action="share">' + ICON.share + ' ' + LABELS.actions.share + '</button>');
        }
        rows.push('<button class="danger" data-action="delete">' + ICON.trash + ' ' + LABELS.actions.removeFromList + '</button>');
        if (status === "COMPLETED") {
            rows.push('<button class="danger" data-action="delete-with-file">' + ICON.trash + ' ' + LABELS.actions.deleteFile + '</button>');
        }
        return '<div class="popup' + (state.openMenuUrl === item.url ? ' open' : '') + '" data-popup>'
            + rows.join("") + '</div>';
    }

    // --- Card-level event delegation ---------------------------------------
    function bindCardEvents() {
        Array.prototype.forEach.call(els.list.querySelectorAll(".card"), function(card) {
            var url = card.getAttribute("data-url");
            card.addEventListener("click", function(ev) {
                var btn = ev.target.closest("[data-action]");
                if (!btn) return;
                ev.stopPropagation();
                handleAction(btn.getAttribute("data-action"), url, card);
            });
            card.addEventListener("contextmenu", function(ev) {
                ev.preventDefault();
                toggleSelection(url);
            });
        });
        document.addEventListener("click", function(ev) {
            if (!ev.target.closest(".popup") && !ev.target.closest(".menu-btn")) {
                if (state.openMenuUrl) { state.openMenuUrl = null; renderList(); }
            }
        }, { once: true });
    }

    function handleAction(action, url, card) {
        var item = state.items.find(function(i) { return i.url === url; });
        switch (action) {
            case "menu":
                state.openMenuUrl = (state.openMenuUrl === url) ? null : url;
                renderList();
                return;
            case "pause": call("pause", url); toast(LABELS.toast.paused); break;
            case "resume": call("resume", url); toast(LABELS.toast.resumed); break;
            case "cancel": call("cancel", url); toast(LABELS.toast.cancelled); break;
            case "retry": call("retry", url); toast(LABELS.toast.retrying); break;
            case "delete": call("deleteEntry", url, false); toast(LABELS.toast.removed); break;
            case "delete-with-file": call("deleteEntry", url, true); toast(LABELS.toast.deletedFile); break;
            case "open":
                if (BRIDGE && item) {
                    var ok = false;
                    try { ok = BRIDGE.openFile(item.localPath || "", item.mimeType || ""); } catch (_) {}
                    if (!ok) toast(LABELS.toast.cannotOpen);
                }
                break;
            case "share":
                copyToClipboard(item ? (item.localPath || item.url) : "");
                toast(LABELS.toast.linkCopied);
                break;
        }
        // Optimistic refresh — actual state will come back on next poll.
        setTimeout(refresh, 50);
    }

    function call(method, url, arg) {
        if (!BRIDGE || typeof BRIDGE[method] !== "function") return;
        try {
            if (typeof arg !== "undefined") BRIDGE[method](url, arg);
            else BRIDGE[method](url);
        } catch (_) {}
    }

    function copyToClipboard(text) {
        if (!text) return;
        try {
            var area = document.createElement("textarea");
            area.value = text; area.style.position = "fixed"; area.style.opacity = "0";
            document.body.appendChild(area);
            area.select(); document.execCommand("copy");
            document.body.removeChild(area);
        } catch (_) {}
    }

    // --- Bulk select --------------------------------------------------------
    function toggleSelection(url) {
        if (state.selected.has(url)) state.selected.delete(url);
        else state.selected.add(url);
        renderList();
    }

    function updateBulk() {
        var n = state.selected.size;
        if (n === 0) { els.bulkBar.classList.remove("open"); return; }
        els.bulkBar.classList.add("open");
        els.bulkLabel.textContent = (n === 1 ? LABELS.bulk.one : LABELS.bulk.other.replace("%d", n));
    }
    els.bulkCancel.addEventListener("click", function() {
        state.selected.clear(); renderList();
    });
    els.bulkDelete.addEventListener("click", function() {
        var urls = Array.from(state.selected);
        urls.forEach(function(u) { call("deleteEntry", u, false); });
        state.selected.clear();
        toast(LABELS.toast.removed);
        setTimeout(refresh, 50);
    });

    // --- Search -------------------------------------------------------------
    els.search.addEventListener("input", function() {
        state.query = els.search.value;
        renderList();
    });

    // --- Toast --------------------------------------------------------------
    var toastTimer = null;
    function toast(msg) {
        if (!msg) return;
        els.toast.textContent = msg;
        els.toast.classList.add("open");
        if (toastTimer) clearTimeout(toastTimer);
        toastTimer = setTimeout(function() { els.toast.classList.remove("open"); }, 1800);
    }

    // --- Render shell once, then start polling -----------------------------
    function render() {
        renderSummary();
        renderChips();
        renderList();
    }
    render();
    if (BRIDGE) {
        startPolling();
    } else {
        // Bridge not available (unsupported WebView, JS disabled at app level, etc.).
        els.summary.textContent = LABELS.bridgeMissing;
    }
})();
</script>
</body>
</html>"""
    }

    private fun downloadLabelsJson(): String {
        // Built as JSON inline so the page is fully self-contained and we can localize from
        // Android resources without a separate fetch.
        fun s(@androidx.annotation.StringRes res: Int): String =
            jsonString(application.getString(res))
        return """{
            "filters": {
                "all": ${s(R.string.downloads_filter_all)},
                "active": ${s(R.string.downloads_filter_active)},
                "completed": ${s(R.string.downloads_filter_completed)},
                "failed": ${s(R.string.downloads_filter_failed)}
            },
            "actions": {
                "pause": ${s(R.string.downloads_action_pause)},
                "resume": ${s(R.string.downloads_action_resume)},
                "cancel": ${s(R.string.download_action_cancel)},
                "retry": ${s(R.string.downloads_action_retry)},
                "open": ${s(R.string.downloads_action_open)},
                "share": ${s(R.string.downloads_action_share)},
                "removeFromList": ${s(R.string.downloads_action_remove_from_list)},
                "deleteFile": ${s(R.string.downloads_action_delete_file)},
                "delete": ${s(R.string.downloads_action_delete)}
            },
            "status": {
                "PENDING": ${s(R.string.download_status_pending)},
                "RUNNING": ${s(R.string.download_status_running)},
                "PAUSED": ${s(R.string.download_status_paused)},
                "RETRYING": ${s(R.string.download_status_retrying)},
                "FAILED": ${s(R.string.download_status_failed)},
                "CANCELLED": ${s(R.string.download_status_cancelled)},
                "COMPLETED": ${s(R.string.download_status_completed)}
            },
            "summary": {
                "one": ${s(R.string.downloads_summary_one)},
                "other": ${s(R.string.downloads_summary_other)},
                "active": ${s(R.string.downloads_summary_active)},
                "completedBytes": ${s(R.string.downloads_summary_completed_bytes)}
            },
            "toast": {
                "paused": ${s(R.string.downloads_toast_paused)},
                "resumed": ${s(R.string.downloads_toast_resumed)},
                "cancelled": ${s(R.string.downloads_toast_cancelled)},
                "retrying": ${s(R.string.downloads_toast_retrying)},
                "removed": ${s(R.string.downloads_toast_removed)},
                "deletedFile": ${s(R.string.downloads_toast_deleted_file)},
                "linkCopied": ${s(R.string.downloads_toast_link_copied)},
                "cannotOpen": ${s(R.string.downloads_toast_cannot_open)}
            },
            "bulk": {
                "one": ${s(R.string.downloads_bulk_one)},
                "other": ${s(R.string.downloads_bulk_other)}
            },
            "queued": ${s(R.string.downloads_queued)},
            "unknownSize": ${s(R.string.unknown_size)},
            "justNow": ${s(R.string.downloads_just_now)},
            "bridgeMissing": ${s(R.string.downloads_bridge_missing)}
        }""".trimIndent()
    }

    /** JSON-encode a string with surrounding quotes; safe for HTML / `<script>` injection. */
    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c") // defuse </script> injections
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        const val FILENAME = "downloads.html"
    }
}
