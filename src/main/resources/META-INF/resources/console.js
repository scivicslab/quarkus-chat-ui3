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
            if (tab === 'actors') refreshActors();
            if (tab === 'io') ioOnShow();
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

    // ── Actors tab ──────────────────────────────────────────────────────────
    // Renders the actor tree from GET /api/actors. Each node: {name, type, alive, children[]}.
    function actorNodeEl(node) {
        var wrap = document.createElement('div');
        wrap.className = 'actor-node';
        var label = document.createElement('div');
        label.className = 'actor-label' + (node.alive ? '' : ' actor-dead');
        var dot = document.createElement('span');
        dot.className = 'actor-dot ' + (node.alive ? 'alive' : 'dead');
        dot.textContent = '●';
        var name = document.createElement('span');
        name.className = 'actor-name';
        name.textContent = node.name;
        var type = document.createElement('span');
        type.className = 'actor-type';
        type.textContent = node.type ? '  ' + node.type : '';
        label.appendChild(dot);
        label.appendChild(name);
        label.appendChild(type);
        wrap.appendChild(label);
        if (node.children && node.children.length) {
            var kids = document.createElement('div');
            kids.className = 'actor-children';
            node.children.forEach(function (c) { kids.appendChild(actorNodeEl(c)); });
            wrap.appendChild(kids);
        }
        return wrap;
    }

    function renderActorTree(root) {
        var el = document.getElementById('actors-tree');
        if (!el) return;
        el.textContent = '';
        if (!root) {
            var empty = document.createElement('div');
            empty.className = 'actor-empty';
            empty.textContent = 'No actors.';
            el.appendChild(empty);
            return;
        }
        el.appendChild(actorNodeEl(root));
    }

    function countActors(node) {
        if (!node) return 0;
        var n = 1;
        (node.children || []).forEach(function (c) { n += countActors(c); });
        return n;
    }

    var actorsRefreshing = false;
    var lastActorSig = null;
    function refreshActors() {
        if (actorsRefreshing) return;
        actorsRefreshing = true;
        var status = document.getElementById('actors-status');
        fetch('api/actors')
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (root) {
                var sig = JSON.stringify(root);
                if (sig === lastActorSig) return;   // unchanged: leave the DOM alone
                lastActorSig = sig;
                renderActorTree(root);
                if (status) status.textContent = countActors(root) + ' actor(s)';
            })
            .catch(function (err) {
                if (status) status.textContent = 'error: ' + err.message;
            })
            .finally(function () { actorsRefreshing = false; });
    }

    function initActors() {
        var btn = document.getElementById('actors-refresh');
        var auto = document.getElementById('actors-auto');
        if (btn) btn.addEventListener('click', refreshActors);

        var timer = null;
        function applyAuto() {
            if (auto && auto.checked) {
                if (!timer) timer = setInterval(function () {
                    var tab = document.getElementById('tab-actors');
                    if (tab && tab.classList.contains('active')) refreshActors();
                }, 3000);
            } else if (timer) {
                clearInterval(timer);
                timer = null;
            }
        }
        if (auto) auto.addEventListener('change', applyAuto);
        applyAuto();
    }

    // ── I/O tab (complete I/O viewer: session -> agent -> filtered raw I/O) ────
    var ioSessionsLoaded = false;
    var ioCurrentSession = null;
    var ioCurrentAgent = '';   // '' = all agents
    var ioTraceMode = false;   // false = raw entries, true = reconstructed agent-loop trace

    function ioFilters() {
        return {
            q: document.getElementById('io-q').value.trim(),
            label: document.getElementById('io-label').value.trim(),
            level: document.getElementById('io-level').value,
            limit: document.getElementById('io-limit').value || '200'
        };
    }

    function ioSetStatus(t) { var s = document.getElementById('io-status'); if (s) s.textContent = t; }

    function ioLoadSessions() {
        return fetch('api/sessions').then(function (r) { return r.json(); }).then(function (list) {
            var sel = document.getElementById('io-session');
            sel.textContent = '';
            (list || []).forEach(function (s) {
                var o = document.createElement('option');
                o.value = s.sessionId;
                o.textContent = '#' + s.sessionId + '  ' + (s.workflowName || '') + '  ' + (s.startedAt || '');
                sel.appendChild(o);
            });
            ioSessionsLoaded = true;
            ioCurrentSession = (list && list.length) ? String((sel.value = String(list[0].sessionId))) : null;
        });
    }

    function ioLoadAgents() {
        if (!ioCurrentSession) return Promise.resolve();
        return fetch('api/sessions/' + ioCurrentSession + '/agents').then(function (r) { return r.json(); }).then(function (list) {
            var el = document.getElementById('io-agents');
            el.textContent = '';
            var all = document.createElement('div');
            all.className = 'io-agent' + (ioCurrentAgent === '' ? ' active' : '');
            all.textContent = 'All agents';
            all.addEventListener('click', function () { ioCurrentAgent = ''; ioLoadAgents(); ioLoadLogs(); });
            el.appendChild(all);
            (list || []).forEach(function (a) {
                var d = document.createElement('div');
                d.className = 'io-agent' + (ioCurrentAgent === a.agent ? ' active' : '');
                var n = document.createElement('span'); n.className = 'io-agent-name'; n.textContent = a.agent;
                var c = document.createElement('span'); c.className = 'io-agent-count'; c.textContent = a.lines;
                d.appendChild(n); d.appendChild(c);
                d.addEventListener('click', function () { ioCurrentAgent = a.agent; ioLoadAgents(); ioLoadLogs(); });
                el.appendChild(d);
            });
        });
    }

    function ioLoadLogs() {
        var entriesEl = document.getElementById('io-entries');
        if (!ioCurrentSession) { if (entriesEl) entriesEl.textContent = ''; ioSetStatus(''); return Promise.resolve(); }
        if (ioTraceMode) return ioLoadTrace();
        var f = ioFilters();
        var qs = new URLSearchParams();
        if (ioCurrentAgent) qs.set('agent', ioCurrentAgent);
        if (f.q) qs.set('q', f.q);
        if (f.label) qs.set('label', f.label);
        if (f.level) qs.set('level', f.level);
        if (f.limit) qs.set('limit', f.limit);
        return fetch('api/sessions/' + ioCurrentSession + '/logs?' + qs.toString())
            .then(function (r) { return r.json(); })
            .then(function (page) { ioRenderEntries(page); })
            .catch(function (err) { ioSetStatus('error: ' + err.message); });
    }

    // Trace mode: reconstruct the agent loop (per turn) from the complete I/O log.
    function ioLoadTrace() {
        return fetch('api/sessions/' + ioCurrentSession + '/trace')
            .then(function (r) { return r.json(); })
            .then(function (turns) { ioRenderTrace(turns || []); })
            .catch(function (err) { ioSetStatus('error: ' + err.message); });
    }

    function ioRenderTrace(turns) {
        var el = document.getElementById('io-entries');
        el.textContent = '';
        if (!turns.length) {
            var none = document.createElement('div'); none.className = 'io-empty';
            none.textContent = 'No agent-loop trace in this session.'; el.appendChild(none);
            ioSetStatus('0 turns'); return;
        }
        var steps = 0;
        turns.forEach(function (t) {
            steps += (t.steps || []).length;
            var box = document.createElement('div'); box.className = 'tr-turn';
            var head = document.createElement('div'); head.className = 'tr-turn-head';
            head.textContent = 'Turn ' + t.turn;
            box.appendChild(head);
            // The user's prompt is the loop's starting point — show it first so the turn reads top-down.
            var u = document.createElement('div'); u.className = 'tr-user';
            u.textContent = '🗣 ' + (t.userPrompt ? t.userPrompt : '(user prompt not found)');
            box.appendChild(u);
            (t.steps || []).forEach(function (s) { box.appendChild(ioTraceStepEl(s)); });
            el.appendChild(box);
        });
        ioSetStatus(turns.length + ' turn(s), ' + steps + ' step(s)');
    }

    function ioTraceStepEl(s) {
        var row = document.createElement('div'); row.className = 'tr-step tr-' + s.kind;
        if (s.kind === 'tool') {
            var call = document.createElement('div'); call.className = 'tr-line tr-toolexec';
            call.textContent = '↳ ' + s.toolName + '(' + s.toolInput + ')';
            row.appendChild(call);
            var obs = document.createElement('div'); obs.className = 'tr-obs';
            obs.textContent = '→ ' + s.observation + ' …  [' + s.obsChars + ' chars]';
            row.appendChild(obs);
            return row;
        }
        // llm step
        var tok = (typeof s.promptTokens === 'number' && s.promptTokens >= 0)
            ? '  ·  ' + s.promptTokens + '→' + s.completionTokens + ' tok' : '';
        var line = document.createElement('div'); line.className = 'tr-line';
        line.textContent = (s.finalAnswer ? '💬 answer' : '💭 step') + tok;
        row.appendChild(line);
        if (s.toolCalls) {
            var tc = document.createElement('div'); tc.className = 'tr-toolcall';
            tc.textContent = '🔧 ' + s.toolCalls;
            row.appendChild(tc);
        }
        if (s.thought) {
            var th = document.createElement('div'); th.className = 'tr-thought';
            th.textContent = s.thought;
            row.appendChild(th);
        } else if (s.toolCalls) {
            var no = document.createElement('div'); no.className = 'tr-empty-reason';
            no.textContent = '(no verbalized reason — the model called the tool directly)';
            row.appendChild(no);
        }
        return row;
    }

    function ioApplyMode() {
        var btn = document.getElementById('io-mode');
        if (btn) btn.classList.toggle('active', ioTraceMode);
        var filters = document.querySelector('#tab-io .io-filters');
        if (filters) filters.style.display = ioTraceMode ? 'none' : '';
    }

    function ioRenderEntries(page) {
        var el = document.getElementById('io-entries');
        el.textContent = '';
        var entries = (page && page.entries) || [];
        if (!entries.length) {
            var e = document.createElement('div'); e.className = 'io-empty'; e.textContent = 'No entries.'; el.appendChild(e);
            ioSetStatus('0 entries'); return;
        }
        entries.forEach(function (en) { el.appendChild(ioEntryEl(en)); });
        ioSetStatus(page.returned + ' entries' + (page.limited ? ' (limited — narrow the filter)' : ''));
    }

    // Finds the end index of a JSON object/array starting at `start` (brace-balanced, string-aware).
    function ioMatchJsonEnd(s, start) {
        var depth = 0, inStr = false, esc = false;
        for (var i = start; i < s.length; i++) {
            var c = s[i];
            if (inStr) {
                if (esc) esc = false;
                else if (c === '\\') esc = true;
                else if (c === '"') inStr = false;
            } else if (c === '"') { inStr = true; }
            else if (c === '{' || c === '[') { depth++; }
            else if (c === '}' || c === ']') { depth--; if (depth === 0) return i; }
        }
        return -1;
    }

    // Pretty-prints any embedded JSON object/array in the text (2-space indent), leaving the rest
    // (REQUEST:/RESPONSE:/USAGE: labels, plain text) as-is. Truncated/invalid JSON is left untouched.
    function ioPrettyJson(text) {
        if (!text) return text || '';
        var out = '', i = 0;
        while (i < text.length) {
            var ch = text[i];
            if (ch === '{' || ch === '[') {
                var end = ioMatchJsonEnd(text, i);
                if (end > i) {
                    var slice = text.slice(i, end + 1);
                    try {
                        out += JSON.stringify(JSON.parse(slice), null, 2);
                        i = end + 1;
                        continue;
                    } catch (e) { /* not valid JSON (e.g. truncated preview): leave as-is */ }
                }
            }
            out += ch;
            i++;
        }
        return out;
    }

    function ioEntryEl(en) {
        var wrap = document.createElement('details'); wrap.className = 'io-entry';
        var sum = document.createElement('summary'); sum.className = 'io-entry-head';
        var head = (en.label || '') + '  ·  ' + (en.agent || '') + '  ·  ' + (en.level || '') + '  ·  ' + (en.chars || 0) + ' chars';
        // Real vLLM token counts (prompt -> completion), parsed server-side from the USAGE line.
        // -1 means the entry has no USAGE line (e.g. tool calls), so the chip is omitted there.
        if (typeof en.promptTokens === 'number' && en.promptTokens >= 0) {
            head += '  ·  ' + en.promptTokens + '→' + (en.completionTokens >= 0 ? en.completionTokens : 0) + ' tok';
        }
        sum.textContent = head;
        wrap.appendChild(sum);
        var body = document.createElement('pre'); body.className = 'io-entry-body';
        body.textContent = ioPrettyJson(en.message || '');
        wrap.appendChild(body);
        if (en.truncated) {
            var more = document.createElement('button');
            more.type = 'button'; more.className = 'io-more';
            more.textContent = 'Load full (' + en.chars + ' chars)';
            more.addEventListener('click', function (ev) {
                ev.preventDefault();
                fetch('api/sessions/' + ioCurrentSession + '/entry/' + en.id)
                    .then(function (r) { return r.json(); })
                    .then(function (d) { body.textContent = ioPrettyJson(d.message || ''); more.remove(); });
            });
            wrap.appendChild(more);
        }
        return wrap;
    }

    function ioOnShow() {
        var p = ioSessionsLoaded ? Promise.resolve() : ioLoadSessions();
        p.then(function () { return ioLoadAgents(); }).then(function () { return ioLoadLogs(); });
    }

    function initIo() {
        var sessionSel = document.getElementById('io-session');
        if (sessionSel) sessionSel.addEventListener('change', function () {
            ioCurrentSession = sessionSel.value; ioCurrentAgent = ''; ioLoadAgents(); ioLoadLogs();
        });
        var refresh = document.getElementById('io-refresh');
        if (refresh) refresh.addEventListener('click', function () { ioSessionsLoaded = false; ioOnShow(); });
        var apply = document.getElementById('io-apply');
        if (apply) apply.addEventListener('click', ioLoadLogs);
        var q = document.getElementById('io-q');
        if (q) q.addEventListener('keydown', function (e) { if (e.key === 'Enter') ioLoadLogs(); });
        var mode = document.getElementById('io-mode');
        if (mode) mode.addEventListener('click', function () {
            ioTraceMode = !ioTraceMode; ioApplyMode(); ioLoadLogs();
        });
    }

    // ── Right-pane config bar (live LLM settings via /api/config) ─────────────
    function cfgStatus(t) {
        var s = document.getElementById('cfg-status');
        if (!s) return;
        s.textContent = t;
        setTimeout(function () { if (s.textContent === t) s.textContent = ''; }, 2000);
    }

    function cfgLoad() {
        return fetch('api/config').then(function (r) { return r.json(); }).then(function (c) {
            var t = document.getElementById('cfg-temp'); if (t) t.value = c.temperature;
            var mt = document.getElementById('cfg-maxtokens'); if (mt) mt.value = c.maxTokens;
            return fetch('api/models')
                .then(function (r) { return r.ok ? r.json() : []; })
                .then(function (models) {
                    var sel = document.getElementById('cfg-model'); if (!sel) return;
                    sel.textContent = '';
                    var def = document.createElement('option');
                    def.value = ''; def.textContent = '(vLLM default)'; sel.appendChild(def);
                    (models || []).forEach(function (m) {
                        var o = document.createElement('option'); o.value = m.name; o.textContent = m.name; sel.appendChild(o);
                    });
                    sel.value = c.modelId || '';
                })
                .catch(function () {});
        }).catch(function () {});
    }

    function cfgPatch(field, value) {
        var body = {}; body[field] = value;
        fetch('api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        }).then(function (r) {
            cfgStatus(r.ok ? (field + ' = ' + value) : ('error ' + r.status));
        }).catch(function (e) { cfgStatus('error: ' + e.message); });
    }

    function initConfig() {
        cfgLoad();
        var t = document.getElementById('cfg-temp');
        if (t) t.addEventListener('change', function () { cfgPatch('temperature', parseFloat(t.value)); });
        var mt = document.getElementById('cfg-maxtokens');
        if (mt) mt.addEventListener('change', function () { cfgPatch('maxTokens', parseInt(mt.value, 10)); });
        var md = document.getElementById('cfg-model');
        if (md) md.addEventListener('change', function () { cfgPatch('modelId', md.value); });
    }

    document.addEventListener('DOMContentLoaded', function () {
        initSplitter();
        initTabs();
        initLogs();
        initActors();
        initIo();
        initConfig();
    });
})();
