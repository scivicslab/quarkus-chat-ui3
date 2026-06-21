/* Console shell behaviour: splitter drag, right-pane tab switching, and the Logs tab.
   This script only drives the shell (#console-root / #right-panel); the chat-ui app.js
   owns everything inside #left-panel and is left untouched. */
(function () {
    'use strict';

    // ── Draggable splitter ──────────────────────────────────────────────────
    function initSplitter() {
        var splitter = document.getElementById('splitter');
        var left = document.getElementById('left-panel');
        var root = document.getElementById('console-root');
        if (!splitter || !left || !root) return;

        var dragging = false;

        function onMove(e) {
            if (!dragging) return;
            var rect = root.getBoundingClientRect();
            var x = e.clientX - rect.left;
            var min = 320;                 // keep the chat usable
            var max = rect.width - 280;    // keep the right pane usable
            if (x < min) x = min;
            if (x > max) x = max;
            left.style.width = x + 'px';
            e.preventDefault();
        }

        function stop() {
            if (!dragging) return;
            dragging = false;
            splitter.classList.remove('dragging');
            document.body.style.userSelect = '';
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', stop);
        }

        splitter.addEventListener('mousedown', function (e) {
            dragging = true;
            splitter.classList.add('dragging');
            document.body.style.userSelect = 'none';
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', stop);
            e.preventDefault();
        });
    }

    // ── Right-pane tabs ─────────────────────────────────────────────────────
    function initTabs() {
        var bar = document.getElementById('right-tab-bar');
        if (!bar) return;
        bar.addEventListener('click', function (e) {
            var btn = e.target.closest('.rtab-btn');
            if (!btn) return;
            var tab = btn.getAttribute('data-tab');
            bar.querySelectorAll('.rtab-btn').forEach(function (b) {
                b.classList.toggle('active', b === btn);
            });
            document.querySelectorAll('.rtab-content').forEach(function (c) {
                c.classList.toggle('active', c.id === 'tab-' + tab);
            });
            if (tab === 'logs') refreshLogs();
        });
    }

    // ── Logs tab ────────────────────────────────────────────────────────────
    function fmtLogTime(ms) {
        if (!ms) return '';
        var d = new Date(ms);
        var p = function (n, w) { return String(n).padStart(w || 2, '0'); };
        return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
            + '.' + p(d.getMilliseconds(), 3);
    }

    function renderLogs(entries) {
        var list = document.getElementById('logs-list');
        if (!list) return;
        list.textContent = '';
        if (!Array.isArray(entries) || entries.length === 0) {
            var empty = document.createElement('div');
            empty.className = 'log-empty';
            empty.textContent = 'No log entries yet.';
            list.appendChild(empty);
            return;
        }
        entries.forEach(function (e) {
            var line = document.createElement('div');
            line.className = 'log-line log-' + (e.level || 'INFO');
            if (typeof e.levelValue === 'number') line.setAttribute('data-lv', e.levelValue);
            var t = document.createElement('span');
            t.className = 'log-time';
            t.textContent = fmtLogTime(e.time) + ' ';
            var lv = document.createElement('span');
            lv.className = 'log-level';
            lv.textContent = '[' + (e.level || '?') + '] ';
            var lg = document.createElement('span');
            lg.className = 'log-logger';
            lg.textContent = (e.logger || '') + ': ';
            var msg = document.createElement('span');
            msg.textContent = e.message || '';   // textContent => no HTML injection
            line.appendChild(t);
            line.appendChild(lv);
            line.appendChild(lg);
            line.appendChild(msg);
            list.appendChild(line);
        });
        list.scrollTop = list.scrollHeight;
    }

    var lastLogEntries = [];

    // Re-render the last-fetched logs, keeping only entries at or above the selected severity.
    // Filtering uses the numeric level value supplied by the backend (Level.intValue()), so it is
    // independent of the level's name (JUL "WARNING" and JBoss "WARN" are both 900). ALL = no floor.
    // An entry without a numeric value is never hidden.
    function applyLevelFilter() {
        var sel = document.getElementById('logs-level');
        var min = (sel && sel.value !== '') ? Number(sel.value) : -Infinity;
        var filtered = lastLogEntries.filter(function (e) {
            var lv = (typeof e.levelValue === 'number') ? e.levelValue : NaN;
            return isNaN(lv) || lv >= min;   // unknown value: always show
        });
        renderLogs(filtered);
        var status = document.getElementById('logs-status');
        if (status) status.textContent = filtered.length + ' / ' + lastLogEntries.length + ' line(s)';
    }

    // True while the user has an active text selection inside the given element.
    // Used to suppress auto-refresh re-renders so a copy operation is not wiped.
    function hasTextSelectionIn(el) {
        var sel = window.getSelection();
        if (!el || !sel || sel.isCollapsed || sel.rangeCount === 0) return false;
        var node = sel.anchorNode;
        return !!(node && el.contains(node));
    }

    var logsRefreshing = false;
    var lastLogSig = null;
    function refreshLogs() {
        if (logsRefreshing) return;
        logsRefreshing = true;
        var status = document.getElementById('logs-status');
        fetch('api/logs')
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (entries) {
                entries = Array.isArray(entries) ? entries : [];
                var sig = JSON.stringify(entries);
                if (sig === lastLogSig) return;   // unchanged (e.g. idle): do not touch the DOM
                lastLogSig = sig;
                lastLogEntries = entries;
                applyLevelFilter();
            })
            .catch(function (err) {
                if (status) status.textContent = 'error: ' + err.message;
            })
            .finally(function () { logsRefreshing = false; });
    }

    function initLogs() {
        var btn = document.getElementById('logs-refresh');
        var auto = document.getElementById('logs-auto');
        if (btn) btn.addEventListener('click', refreshLogs);
        var levelSel = document.getElementById('logs-level');
        if (levelSel) levelSel.addEventListener('change', applyLevelFilter);

        var timer = null;
        function applyAuto() {
            if (auto && auto.checked) {
                if (!timer) timer = setInterval(function () {
                    var tab = document.getElementById('tab-logs');
                    // Skip the tick while the user is selecting/copying inside the log list.
                    if (tab && tab.classList.contains('active')
                        && !hasTextSelectionIn(document.getElementById('logs-list'))) {
                        refreshLogs();
                    }
                }, 3000);
            } else if (timer) {
                clearInterval(timer);
                timer = null;
            }
        }
        if (auto) auto.addEventListener('change', applyAuto);
        applyAuto();
        refreshLogs();   // Logs is the default tab — load immediately
    }

    document.addEventListener('DOMContentLoaded', function () {
        initSplitter();
        initTabs();
        initLogs();
    });
})();
