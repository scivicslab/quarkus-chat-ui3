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
            if (tab === 'actors') refreshActors();
            if (tab === 'logdb') ioOnShow();
            if (tab === 'syslog') refreshLogs();
            if (tab === 'agentloop') wfOnShow();
            if (tab === 'turingwf') twfOnShow();
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

    // ── Sessions tab (list conversations; read each inline; clean up) ──
    // Each session is a collapsible row: expanding it loads that conversation's turn-by-turn trace
    // (request <-> response <-> tool I/O) inline. Arbitrary queries are SQL's job — no raw-log browser.
    var ioSessionsLoaded = false;
    var ioCurrentSession = null;   // vestigial; the inline trace threads its own session id

    function ioSetStatus(t) { var s = document.getElementById('io-status'); if (s) s.textContent = t; }

    function ioDeleteSession(id) {
        if (!confirm('Delete session #' + id + ' and all its logs?')) return;
        ioSetStatus('deleting…');
        fetch('api/sessions/' + encodeURIComponent(id), { method: 'DELETE' })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                ioSetStatus(j.deleted ? ('deleted session #' + id)
                                      : (j.error || 'not deleted (active session is kept)'));
                ioSessionsLoaded = false; ioLoadSessions();
            })
            .catch(function (e) { ioSetStatus('error: ' + e); });
    }

    function ioLoadSessions() {
        return fetch('api/sessions').then(function (r) { return r.json(); }).then(function (list) {
            var el = document.getElementById('io-sessions');
            if (!el) return;
            el.textContent = '';
            list = list || [];
            if (!list.length) {
                var e = document.createElement('div'); e.className = 'io-empty'; e.textContent = 'No sessions.';
                el.appendChild(e); ioSetStatus('0 sessions'); ioSessionsLoaded = true; return;
            }
            list.forEach(function (s) { el.appendChild(ioSessionEl(s)); });
            ioSessionsLoaded = true;
            ioSetStatus(list.length + ' session(s)');
        }).catch(function (err) { ioSetStatus('error: ' + err.message); });
    }

    // One session as a collapsible row: the header shows its meta + a delete button; expanding it lazily
    // loads and renders that session's turn-by-turn trace inline.
    function ioSessionEl(s) {
        var det = document.createElement('details'); det.className = 'sess';
        var sum = document.createElement('summary'); sum.className = 'sess-head';
        var meta = document.createElement('span'); meta.className = 'sess-meta';
        meta.textContent = '#' + s.sessionId + '  ·  ' + (s.startedAt || '') + '  ·  '
                         + (s.workflowName || '') + '  ·  '
                         + (s.totalLogEntries != null ? s.totalLogEntries + ' entries' : '');
        var del = document.createElement('button'); del.type = 'button'; del.className = 'io-del';
        del.title = 'Delete this session and all its logs'; del.textContent = '🗑';
        del.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); ioDeleteSession(s.sessionId); });
        sum.appendChild(meta); sum.appendChild(del);
        det.appendChild(sum);
        var body = document.createElement('div'); body.className = 'sess-body'; body.textContent = 'loading…';
        det.appendChild(body);
        var loaded = false;
        det.addEventListener('toggle', function () {
            if (!det.open || loaded) return;
            loaded = true;
            fetch('api/sessions/' + s.sessionId + '/trace')
                .then(function (r) { return r.json(); })
                .then(function (turns) { ioRenderTraceInto(body, turns || [], s.sessionId); })
                .catch(function (err) { body.textContent = 'error: ' + err.message; loaded = false; });
        });
        return det;
    }

    // ── Trace rendering (per-turn directional messages), shown inline inside a Sessions row ──
    function ioRenderTraceInto(el, turns, sessionId) {
        el.textContent = '';
        if (!turns.length) {
            var none = document.createElement('div'); none.className = 'io-empty';
            none.textContent = 'No agent-loop trace in this session.'; el.appendChild(none); return;
        }
        turns.forEach(function (t) {
            var box = document.createElement('details'); box.className = 'tr-turn'; box.open = true;
            var head = document.createElement('summary'); head.className = 'tr-turn-head';
            head.textContent = 'Turn ' + t.turn;
            box.appendChild(head);
            ioTurnMessages(t).forEach(function (m) { box.appendChild(ioMsgEl(m, sessionId)); });
            el.appendChild(box);
        });
    }

    // Flattens a turn into an ordered list of one-direction MESSAGES (the basic unit). Each log entry
    // splits into its two directions: an llm entry -> (loop→LLM request) + (LLM→loop reply); a tool
    // entry -> (loop→tool input) + (tool→loop observation). The user prompt opens the turn.
    function ioTurnMessages(t) {
        var out = [];
        var firstLlm = (t.steps || []).filter(function (s) { return s.kind === 'llm'; })[0];
        out.push({ dir: 'user → loop', cls: 'user',
                   summary: '🗣 ' + (t.userPrompt || '(user prompt not found)'),
                   id: firstLlm ? firstLlm.id : -1, part: 'USER' });
        (t.steps || []).forEach(function (s) {
            if (s.kind === 'tool') {
                out.push({ dir: 'loop → tool', cls: 'to-tool',
                           summary: '↳ ' + s.toolName + '(' + s.toolInput + ')', id: s.id, part: 'INPUT' });
                out.push({ dir: 'tool → loop', cls: 'from-tool',
                           summary: '→ ' + s.observation + ' …  [' + s.obsChars + ' chars]', id: s.id, part: 'OBSERVATION' });
                return;
            }
            // llm entry → two messages: the request we sent, and the model's reply.
            var tokIn = (typeof s.promptTokens === 'number' && s.promptTokens >= 0) ? (' · ' + s.promptTokens + ' tok in') : '';
            out.push({ dir: 'loop → LLM', cls: 'to-llm',
                       summary: 'request' + tokIn + '  (system + history + user + tools offered)', id: s.id, part: 'REQUEST' });
            var reply;
            if (s.toolCalls) {
                reply = '🔧 ' + s.toolCalls.replace(/\s+/g, ' ').trim();
                if (s.reason) reply += '   💡 ' + s.reason;
            } else {
                reply = '💬 ' + (s.thought || '(empty)');
            }
            var tokOut = (typeof s.completionTokens === 'number' && s.completionTokens >= 0) ? ('  ·  ' + s.completionTokens + ' tok out') : '';
            out.push({ dir: 'LLM → loop', cls: 'from-llm', summary: reply + tokOut, id: s.id, part: 'RESPONSE' });
        });
        return out;
    }

    // One directional message: a summary line (with its "who → whom" tag + color) that expands to show
    // ONLY that direction's full text, fetched lazily from the source log entry.
    function ioMsgEl(m, sessionId) {
        var det = document.createElement('details'); det.className = 'trm ' + m.cls;
        var sum = document.createElement('summary'); sum.className = 'trm-sum';
        var dir = document.createElement('span'); dir.className = 'trm-dir'; dir.textContent = m.dir;
        var txt = document.createElement('span'); txt.className = 'trm-txt'; txt.textContent = m.summary;
        sum.appendChild(dir); sum.appendChild(txt);
        det.appendChild(sum);
        var body = document.createElement('div'); body.className = 'trm-body'; body.textContent = 'loading…';
        det.appendChild(body);
        var loaded = false;
        det.addEventListener('toggle', function () {
            if (!det.open || loaded) return;
            loaded = true;
            if (m.id < 0) { body.textContent = '(no source entry)'; return; }
            fetch('api/sessions/' + sessionId + '/entry/' + m.id)
                .then(function (r) { return r.json(); })
                .then(function (d) { ioRenderPart(body, d.message || '', m.part); })
                .catch(function (err) { body.textContent = 'error: ' + err.message; loaded = false; });
        });
        return det;
    }

    // Renders ONLY the sections belonging to one direction (part) of a source entry into `holder`.
    function ioRenderPart(holder, message, part) {
        holder.textContent = '';
        var sections;
        if (part === 'USER') {
            sections = [{ spec: { t: 'user message', cls: 'user' }, body: ioUserMessageOf(message) }];
        } else {
            var kind = (part === 'INPUT' || part === 'OBSERVATION') ? 'tool' : 'llm';
            var want = { REQUEST: ['REQUEST'], RESPONSE: ['RESPONSE', 'REASONING', 'TOOL_CALLS'],
                         INPUT: ['TOOL', 'INPUT'], OBSERVATION: ['OBSERVATION'] }[part] || [];
            sections = ioSplitEntry(message, kind).filter(function (sec) {
                return want.indexOf(sec.spec.k.slice(0, -1)) >= 0;
            });
        }
        if (!sections.length) {
            var pre0 = document.createElement('pre'); pre0.className = 'tr-full-body'; pre0.textContent = '(empty)';
            holder.appendChild(pre0); return;
        }
        sections.forEach(function (sec) {
            var block = document.createElement('div'); block.className = 'tr-sec ' + (sec.spec.cls || '');
            var h = document.createElement('div'); h.className = 'tr-sec-head';
            var tt = document.createElement('span'); tt.className = 'tr-sec-title'; tt.textContent = sec.spec.t;
            h.appendChild(tt);
            var pre = document.createElement('pre'); pre.className = 'tr-full-body';
            var b = sec.body;
            if (sec.spec.json) { try { b = JSON.stringify(JSON.parse(b), null, 2); } catch (e) { /* keep raw */ } }
            pre.textContent = b ? b : '(empty)';
            block.appendChild(h); block.appendChild(pre);
            holder.appendChild(block);
        });
    }

    // Pulls the current user question (the last user message) out of an entry's REQUEST json.
    function ioUserMessageOf(message) {
        var req = ioSplitEntry(message, 'llm').filter(function (s) { return s.spec.k === 'REQUEST:'; })[0];
        if (!req) return '';
        try {
            var msgs = (JSON.parse(req.body).messages) || [];
            var last = '';
            msgs.forEach(function (mm) { if (mm.role === 'user') last = mm.content || ''; });
            return last;
        } catch (e) { return ''; }
    }

    // The labeled sections of a stored entry, in order, each tagged with its direction (who -> whom)
    // so a single round-trip is NOT shown as one undifferentiated blob.
    function ioEntrySections(kind) {
        if (kind === 'tool') return [
            { k: 'TOOL:',        t: 'TOOL (name)',          d: 'agent loop → tool (Java)', cls: 'to-tool' },
            { k: 'INPUT:',       t: 'INPUT (arguments)',    d: 'agent loop → tool (Java)', cls: 'to-tool' },
            { k: 'OBSERVATION:', t: 'OBSERVATION (result)', d: 'tool (Java) → agent loop', cls: 'from-tool' }
        ];
        return [
            { k: 'REQUEST:',     t: 'REQUEST (system + history + user + tools offered)', d: 'agent loop → LLM', cls: 'to-llm', json: true },
            { k: 'RESPONSE:',    t: 'RESPONSE (assistant text)', d: 'LLM → agent loop', cls: 'from-llm' },
            { k: 'REASONING:',   t: 'REASONING (chain of thought)', d: 'LLM → agent loop', cls: 'from-llm' },
            { k: 'TOOL_CALLS:',  t: 'TOOL_CALLS (functions the model asked to run)', d: 'LLM → agent loop', cls: 'from-llm' },
            { k: 'USAGE:',       t: 'USAGE (token counts)', d: 'LLM server · metadata', cls: 'meta' }
        ];
    }

    // Splits a stored entry message into its directional sections (markers appear once, in order).
    function ioSplitEntry(message, kind) {
        var specs = ioEntrySections(kind);
        var found = [], cursor = 0;
        specs.forEach(function (s) {
            var i = message.indexOf(s.k, cursor);
            if (i >= 0) { found.push({ spec: s, mark: i, start: i + s.k.length }); cursor = i + s.k.length; }
        });
        return found.map(function (f, j) {
            var end = (j + 1 < found.length) ? found[j + 1].mark : message.length;
            return { spec: f.spec, body: message.substring(f.start, end).trim() };
        });
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
        if (!ioSessionsLoaded) ioLoadSessions();
    }

    function initIo() {
        var refresh = document.getElementById('io-refresh');
        if (refresh) refresh.addEventListener('click', function () { ioSessionsLoaded = false; ioLoadSessions(); });
        var delOld = document.getElementById('io-del-old');
        if (delOld) delOld.addEventListener('click', function () {
            var d = document.getElementById('io-del-days');
            var days = d ? parseInt(d.value, 10) : 30;
            if (isNaN(days) || days < 0) { ioSetStatus('enter a valid day count'); return; }
            if (!confirm('Delete ALL sessions older than ' + days + ' day(s)? (the active conversation is kept)')) return;
            ioSetStatus('deleting…');
            fetch('api/sessions/old?days=' + days, { method: 'DELETE' })
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    ioSetStatus('deleted ' + (j.deleted || 0) + ' session(s) older than ' + days + 'd');
                    ioSessionsLoaded = false; ioLoadSessions();
                })
                .catch(function (e) { ioSetStatus('error: ' + e); });
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

    // ── Workflow tab (read-only system workflow viewer; Phase 1) ──────────────
    function wfStatus(msg) {
        var s = document.getElementById('wf-status');
        if (s) s.textContent = msg || '';
    }

    // Splits a workflow YAML into a preamble (everything before the first step) and the top-level
    // step items (lines beginning with exactly "  - "). Display only — no reassembly in Phase 1.
    function wfSplitSteps(yaml) {
        var lines = (yaml || '').split('\n');
        var preamble = [], steps = [], cur = null;
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (/^  - /.test(line)) {            // a top-level step list item under steps:
                if (cur) steps.push(cur.join('\n'));
                cur = [line];
            } else if (cur) {
                cur.push(line);
            } else {
                preamble.push(line);
            }
        }
        if (cur) steps.push(cur.join('\n'));
        return { preamble: preamble.join('\n').replace(/\s+$/, ''), steps: steps };
    }

    // Box heading: the step's transition direction (the states array) plus its 0-based step number,
    // e.g. states ["0", "1"] -> '["0", "1"]   # step 0'. The label still appears in the YAML body.
    function wfStepTitle(text, idx) {
        // states: sits on the step's first line "  - states: [...]"; allow the leading "- ".
        var m = text.match(/(^|\n)\s*-?\s*states:\s*(.+)/);
        var states = m ? m[2].trim() : '';
        return (states ? states + '   ' : '') + '# step ' + idx;
    }

    function wfRenderBox(parent, title, body, kind) {
        var box = document.createElement('div');
        box.className = 'wf-box' + (kind ? ' wf-' + kind : '');
        var h = document.createElement('div');
        h.className = 'wf-box-title';
        h.textContent = title;
        var pre = document.createElement('pre');
        pre.className = 'wf-box-yaml';
        pre.textContent = body;          // read-only; textContent => no HTML injection
        box.appendChild(h);
        box.appendChild(pre);
        parent.appendChild(box);
    }

    // Renders the system steps read-only; after each insertion-point step (label ext-pre/ext-mid/
    // ext-post) renders that point's editable user-extension box inline.
    function wfRender(yaml) {
        var list = document.getElementById('wf-list');
        if (!list) return;
        list.textContent = '';
        var parts = wfSplitSteps(yaml);
        if (parts.preamble) wfRenderBox(list, 'workflow header', parts.preamble, 'head');
        parts.steps.forEach(function (s, i) {
            wfRenderBox(list, wfStepTitle(s, i), s, 'step');
        });
        wfStatus(parts.steps.length + ' step(s) — read-only');
    }

    function wfLoad(name) {
        if (!name) return;
        wfStatus('loading…');
        fetch('/api/workflows/' + encodeURIComponent(name))
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (!d || !d.yaml) { wfStatus('not found'); return; }
                wfRender(d.yaml);
            })
            .catch(function (e) { wfStatus('error: ' + e.message); });
    }

    function wfPopulate(then) {
        var sel = document.getElementById('wf-select');
        if (!sel) return;
        fetch('/api/workflows').then(function (r) { return r.json(); })
            .then(function (arr) {
                sel.textContent = '';
                (arr || []).forEach(function (w) {
                    var o = document.createElement('option');
                    o.value = w.name;
                    o.textContent = w.title || w.name;
                    sel.appendChild(o);
                });
                if (then) then();
            })
            .catch(function (e) { wfStatus('error: ' + e.message); });
    }

    function wfOnShow() {
        var sel = document.getElementById('wf-select');
        if (sel && sel.options.length === 0) {
            wfPopulate(function () { wfLoad(sel.value); });
        } else if (sel) {
            wfLoad(sel.value);
        }
    }

    function initWorkflow() {
        var sel = document.getElementById('wf-select');
        if (sel) sel.addEventListener('change', function () { wfLoad(sel.value); });
        var btn = document.getElementById('wf-refresh');
        if (btn) btn.addEventListener('click', function () { wfLoad(sel ? sel.value : ''); });
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

    // ── Turing Workflow tab ──────────────────────────────────────────────────
    var twfCurrentSpec = null;
    var twfRunId = null;
    var twfPollTimer = null;

    function twfOnShow() {
        if (!document.getElementById('twf-select').options.length ||
                document.getElementById('twf-select').options[0].value === '') {
            twfLoadList();
        }
    }

    function twfSetStatus(msg) {
        var s = document.getElementById('twf-status');
        if (s) s.textContent = msg;
    }

    function twfLoadList() {
        twfSetStatus('loading…');
        fetch('/api/turingwf')
            .then(function(r) { return r.json(); })
            .then(function(d) {
                var sel = document.getElementById('twf-select');
                sel.textContent = '';
                var blank = document.createElement('option');
                blank.value = ''; blank.textContent = '— select workflow —';
                sel.appendChild(blank);
                (d.workflows || []).forEach(function(name) {
                    var opt = document.createElement('option');
                    opt.value = name; opt.textContent = name;
                    sel.appendChild(opt);
                });
                twfSetStatus('');
            })
            .catch(function(e) { twfSetStatus('error: ' + e.message); });
    }

    function twfLoadWorkflow(name) {
        if (!name) {
            twfCurrentSpec = null;
            document.getElementById('twf-form-wrap').style.display = 'none';
            document.getElementById('twf-output').classList.remove('visible');
            document.getElementById('twf-wf-list').textContent = '';
            return;
        }
        twfSetStatus('loading…');
        fetch('/api/turingwf/spec/' + encodeURIComponent(name))
            .then(function(r) { return r.json(); })
            .then(function(spec) {
                twfCurrentSpec = spec;
                twfRenderForm(spec.params || []);
                twfRenderYaml(spec.yaml || '');
                document.getElementById('twf-form-wrap').style.display = '';
                twfSetStatus('');
            })
            .catch(function(e) { twfSetStatus('error: ' + e.message); });
    }

    function twfRenderForm(params) {
        var form = document.getElementById('twf-form');
        form.textContent = '';
        params.forEach(function(p) {
            var field = document.createElement('div');
            field.className = 'twf-field';

            var label = document.createElement('label');
            label.className = 'twf-label' + (p.required ? ' required' : '');
            label.textContent = p.name;
            if (p.description) label.title = p.description;
            field.appendChild(label);

            var input = document.createElement('input');
            input.type = 'text';
            input.setAttribute('data-param', p.name);
            if (p.defaultValue != null) input.value = p.defaultValue;
            if (p.description) input.placeholder = p.description;
            field.appendChild(input);

            form.appendChild(field);
        });
    }

    function twfCollectParams() {
        var result = {};
        var inputs = document.querySelectorAll('#twf-form [data-param]');
        for (var i = 0; i < inputs.length; i++) {
            var inp = inputs[i];
            var name = inp.getAttribute('data-param');
            var val = inp.value.trim();
            var spec = null;
            if (twfCurrentSpec && twfCurrentSpec.params) {
                for (var k = 0; k < twfCurrentSpec.params.length; k++) {
                    if (twfCurrentSpec.params[k].name === name) { spec = twfCurrentSpec.params[k]; break; }
                }
            }
            if (!val && spec && spec.required) {
                twfSetStatus('required: ' + name);
                return null;
            }
            if (val) result[name] = val;
        }
        return result;
    }

    function twfRenderYaml(yaml) {
        var list = document.getElementById('twf-wf-list');
        list.textContent = '';
        var title = twfCurrentSpec ? twfCurrentSpec.name : 'workflow';
        wfRenderBox(list, title, yaml, 'head');
    }

    function twfRun() {
        if (!twfCurrentSpec) { twfSetStatus('select a workflow first'); return; }
        var params = twfCollectParams();
        if (params === null) return;

        var name = twfCurrentSpec.name;
        twfSetStatus('starting…');
        document.getElementById('twf-run').disabled = true;
        document.getElementById('twf-cancel-run').disabled = false;

        var out = document.getElementById('twf-output');
        out.textContent = '';
        out.classList.add('visible');

        fetch('/api/turingwf/run/' + encodeURIComponent(name), {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(params)
        })
        .then(function(r) { return r.json(); })
        .then(function(d) {
            if (d.error) { twfSetStatus('error: ' + d.error); twfRunDone(); return; }
            twfRunId = d.runId;
            twfSetStatus('running…');
            twfPollStatus();
        })
        .catch(function(e) { twfSetStatus('error: ' + e.message); twfRunDone(); });
    }

    function twfPollStatus() {
        if (!twfRunId) return;
        fetch('/api/turingwf/status/' + encodeURIComponent(twfRunId))
            .then(function(r) { return r.json(); })
            .then(function(d) {
                var out = document.getElementById('twf-output');
                (d.lines || []).forEach(function(line) {
                    out.textContent += line + '\n';
                    out.scrollTop = out.scrollHeight;
                });
                if (d.done) {
                    twfSetStatus(d.error ? 'error: ' + d.error : 'done');
                    twfRunDone();
                } else {
                    twfPollTimer = setTimeout(twfPollStatus, 700);
                }
            })
            .catch(function(e) {
                twfSetStatus('poll error: ' + e.message);
                twfPollTimer = setTimeout(twfPollStatus, 2000);
            });
    }

    function twfRunDone() {
        twfRunId = null;
        if (twfPollTimer) { clearTimeout(twfPollTimer); twfPollTimer = null; }
        document.getElementById('twf-run').disabled = false;
        document.getElementById('twf-cancel-run').disabled = true;
    }

    function initTuringWf() {
        document.getElementById('twf-select').addEventListener('change', function() {
            twfLoadWorkflow(this.value);
        });
        document.getElementById('twf-refresh').addEventListener('click', twfLoadList);
        document.getElementById('twf-run').addEventListener('click', twfRun);
        document.getElementById('twf-cancel-run').addEventListener('click', function() {
            if (twfPollTimer) { clearTimeout(twfPollTimer); twfPollTimer = null; }
            twfSetStatus('stopped');
            twfRunDone();
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        initSplitter();
        initTabs();
        initLogs();
        initActors();
        initIo();
        initConfig();
        initWorkflow();
        initTuringWf();
        ioOnShow();   // Sessions is the default active tab; load it on startup.
    });
})();
