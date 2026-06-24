// quarkus-chat-ui REST + EventSource SSE client

(function () {
    'use strict';

    // Enable KaTeX math rendering inside markdown so LaTeX ($...$, $$...$$) in assistant answers
    // is typeset in the left pane. Optional: if the CDN scripts did not load, fall back to plain text.
    try {
        if (typeof marked !== 'undefined' && typeof markedKatex === 'function') {
            marked.use(markedKatex({ throwOnError: false, nonStandard: true }));
        }
    } catch (e) {
        // math rendering is optional; ignore and render markdown without it
    }

    // --- Single-user startup (login / multi-user / auth removed) ---
    var appInitialized = false;

    function initApp() {
        if (appInitialized) return;
        appInitialized = true;
        doInitApp();
    }

    function apiUrl(path) {
        return path;
    }

    // No login screen: reveal the app and initialize immediately.
    document.getElementById('app').style.display = '';
    initApp();

    function doInitApp() {

    const chatArea = document.getElementById('chat-area');
    const promptInput = document.getElementById('prompt-input');
    const sendBtn = document.getElementById('send-btn');
    const queueBtn = document.getElementById('queue-btn');
    const cancelBtn = document.getElementById('cancel-btn');
    const modelSelect = document.getElementById('model-select');
    const themeSelect = document.getElementById('theme-select');
    var activeKeybind = 'default'; // set from /api/config
    const connectionStatus = document.getElementById('connection-status');
    const queueArea = document.getElementById('queue-area');
    const queueResizeHandle = document.getElementById('queue-resize-handle');
    const inputResizeHandle = document.getElementById('input-resize-handle');
    const activityLabel = document.getElementById('activity-label');
    const inputArea = document.getElementById('input-area');

    let thinkingStartTime = null;   // Date.now() when thinking started
    let thinkingTimer = null;       // setInterval ID

    // --- Per-session localStorage isolation ---
    // When served under /session/{id}/ (k8s-pups), suffix localStorage keys with the
    // session ID so multiple instances don't share chat history, prompt queue, theme,
    // and model selection.  When served at / (standalone), suffix is empty — backward compatible.
    var SESSION_SUFFIX = (function () {
        var m = window.location.pathname.match(/^\/session\/([^/]+)\//);
        return m ? '-' + m[1] : '';
    })();

    // --- Theme (per-session in k8s-pups, global in standalone) ---
    var THEME_KEY = 'chat-ui-theme' + SESSION_SUFFIX;
    var savedTheme = localStorage.getItem(THEME_KEY) || 'dark-catppuccin';
    console.log('[chat-ui] theme restore: key=' + THEME_KEY + ' saved=' + localStorage.getItem(THEME_KEY) + ' using=' + savedTheme);
    document.documentElement.setAttribute('data-theme', savedTheme);
    themeSelect.value = savedTheme;

    themeSelect.addEventListener('change', function () {
        var theme = themeSelect.value;
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
        console.log('[chat-ui] theme saved: key=' + THEME_KEY + ' value=' + theme);
    });

    // --- Keybind (from server config: -Dchat-ui.keybind=emacs|vi|default) ---
    promptInput.addEventListener('keydown', function (e) {
        if (activeKeybind !== 'emacs') return;
        if (!e.ctrlKey) return;

        var ta = e.target;
        var start = ta.selectionStart;
        var end = ta.selectionEnd;
        var val = ta.value;

        switch (e.key) {
            case 'a': // beginning of line
                e.preventDefault();
                var lineStart = val.lastIndexOf('\n', start - 1) + 1;
                ta.setSelectionRange(lineStart, lineStart);
                break;
            case 'e': // end of line
                e.preventDefault();
                var lineEnd = val.indexOf('\n', start);
                if (lineEnd === -1) lineEnd = val.length;
                ta.setSelectionRange(lineEnd, lineEnd);
                break;
            case 'f': // forward char
                e.preventDefault();
                if (start < val.length) ta.setSelectionRange(start + 1, start + 1);
                break;
            case 'b': // backward char
                e.preventDefault();
                if (start > 0) ta.setSelectionRange(start - 1, start - 1);
                break;
            case 'n': // next line
                e.preventDefault();
                var curLineEndN = val.indexOf('\n', start);
                if (curLineEndN !== -1) {
                    var colN = start - (val.lastIndexOf('\n', start - 1) + 1);
                    var nextLineEnd = val.indexOf('\n', curLineEndN + 1);
                    if (nextLineEnd === -1) nextLineEnd = val.length;
                    var pos = Math.min(curLineEndN + 1 + colN, nextLineEnd);
                    ta.setSelectionRange(pos, pos);
                }
                break;
            case 'p': // previous line
                e.preventDefault();
                var curLineStartP = val.lastIndexOf('\n', start - 1) + 1;
                if (curLineStartP > 0) {
                    var colP = start - curLineStartP;
                    var prevLineStart = val.lastIndexOf('\n', curLineStartP - 2) + 1;
                    var pos2 = Math.min(prevLineStart + colP, curLineStartP - 1);
                    ta.setSelectionRange(pos2, pos2);
                }
                break;
            case 'd': // delete forward
                e.preventDefault();
                if (start < val.length) {
                    ta.value = val.substring(0, start) + val.substring(start + 1);
                    ta.setSelectionRange(start, start);
                }
                break;
            case 'h': // delete backward (backspace)
                e.preventDefault();
                if (start > 0) {
                    ta.value = val.substring(0, start - 1) + val.substring(start);
                    ta.setSelectionRange(start - 1, start - 1);
                }
                break;
            case 'k': // kill to end of line
                e.preventDefault();
                var killEnd = val.indexOf('\n', start);
                if (killEnd === -1) killEnd = val.length;
                if (killEnd === start && start < val.length) killEnd++; // kill newline if at end of line
                ta.value = val.substring(0, start) + val.substring(killEnd);
                ta.setSelectionRange(start, start);
                break;
        }
    });

    // --- Vi keybind ---
    var viMode = 'insert'; // 'normal' or 'insert'

    function updateViCursor(ta) {
        if (activeKeybind === 'vi' && viMode === 'normal') {
            ta.style.caretColor = 'transparent';
            // block cursor via box-shadow on a zero-width span is not possible in textarea,
            // use a visual indicator in placeholder instead
            ta.classList.add('vi-normal');
        } else {
            ta.style.caretColor = '';
            ta.classList.remove('vi-normal');
        }
    }

    promptInput.addEventListener('keydown', function (e) {
        if (activeKeybind !== 'vi') return;

        var ta = e.target;
        var start = ta.selectionStart;
        var end = ta.selectionEnd;
        var val = ta.value;

        if (viMode === 'normal') {
            e.preventDefault();
            switch (e.key) {
                case 'i': // insert mode
                    viMode = 'insert';
                    updateViCursor(ta);
                    break;
                case 'a': // append
                    viMode = 'insert';
                    if (start < val.length) ta.setSelectionRange(start + 1, start + 1);
                    updateViCursor(ta);
                    break;
                case 'A': // append at end of line
                    viMode = 'insert';
                    var eolA = val.indexOf('\n', start);
                    if (eolA === -1) eolA = val.length;
                    ta.setSelectionRange(eolA, eolA);
                    updateViCursor(ta);
                    break;
                case 'I': // insert at beginning of line
                    viMode = 'insert';
                    var bolI = val.lastIndexOf('\n', start - 1) + 1;
                    ta.setSelectionRange(bolI, bolI);
                    updateViCursor(ta);
                    break;
                case 'o': // open line below
                    viMode = 'insert';
                    var eolO = val.indexOf('\n', start);
                    if (eolO === -1) eolO = val.length;
                    ta.value = val.substring(0, eolO) + '\n' + val.substring(eolO);
                    ta.setSelectionRange(eolO + 1, eolO + 1);
                    updateViCursor(ta);
                    break;
                case 'h': // left
                    if (start > 0) ta.setSelectionRange(start - 1, start - 1);
                    break;
                case 'l': // right
                    if (start < val.length) ta.setSelectionRange(start + 1, start + 1);
                    break;
                case 'j': // down
                    var curEndJ = val.indexOf('\n', start);
                    if (curEndJ !== -1) {
                        var colJ = start - (val.lastIndexOf('\n', start - 1) + 1);
                        var nextEndJ = val.indexOf('\n', curEndJ + 1);
                        if (nextEndJ === -1) nextEndJ = val.length;
                        var posJ = Math.min(curEndJ + 1 + colJ, nextEndJ);
                        ta.setSelectionRange(posJ, posJ);
                    }
                    break;
                case 'k': // up
                    var curStartK = val.lastIndexOf('\n', start - 1) + 1;
                    if (curStartK > 0) {
                        var colK = start - curStartK;
                        var prevStartK = val.lastIndexOf('\n', curStartK - 2) + 1;
                        var posK = Math.min(prevStartK + colK, curStartK - 1);
                        ta.setSelectionRange(posK, posK);
                    }
                    break;
                case 'w': // next word
                    var mW = val.substring(start).match(/\S+\s*/);
                    if (mW) ta.setSelectionRange(start + mW[0].length, start + mW[0].length);
                    break;
                case 'b': // previous word
                    var before = val.substring(0, start);
                    var mB = before.match(/\S+\s*$/);
                    if (mB) ta.setSelectionRange(start - mB[0].length, start - mB[0].length);
                    break;
                case '0': // beginning of line
                    var bol0 = val.lastIndexOf('\n', start - 1) + 1;
                    ta.setSelectionRange(bol0, bol0);
                    break;
                case '$': // end of line
                    var eol$ = val.indexOf('\n', start);
                    if (eol$ === -1) eol$ = val.length;
                    ta.setSelectionRange(eol$, eol$);
                    break;
                case 'x': // delete char
                    if (start < val.length) {
                        ta.value = val.substring(0, start) + val.substring(start + 1);
                        ta.setSelectionRange(start, start);
                    }
                    break;
                case 'd': // dd = delete line (wait for second d)
                    // simplified: just delete current line
                    var bolD = val.lastIndexOf('\n', start - 1) + 1;
                    var eolD = val.indexOf('\n', start);
                    if (eolD === -1) eolD = val.length;
                    else eolD++; // include newline
                    ta.value = val.substring(0, bolD) + val.substring(eolD);
                    ta.setSelectionRange(bolD, bolD);
                    break;
                case 'g': // gg = top (simplified: single g goes to top)
                    ta.setSelectionRange(0, 0);
                    break;
                case 'G': // bottom
                    ta.setSelectionRange(val.length, val.length);
                    break;
            }
            return;
        }

        // insert mode: Escape to return to normal
        if (e.key === 'Escape') {
            e.preventDefault();
            viMode = 'normal';
            updateViCursor(ta);
        }
    });

    // --- Editable title ---
    var appTitle = document.getElementById('app-title');
    var defaultTitle = appTitle.textContent;
    var savedTitle = localStorage.getItem('chat-ui-custom-title');
    if (savedTitle) {
        appTitle.textContent = savedTitle;
        document.title = savedTitle;
    }
    appTitle.addEventListener('input', function () {
        document.title = appTitle.textContent || defaultTitle;
    });
    appTitle.addEventListener('blur', function () {
        var t = appTitle.textContent.trim();
        if (t && t !== defaultTitle) {
            localStorage.setItem('chat-ui-custom-title', t);
        } else {
            localStorage.removeItem('chat-ui-custom-title');
            appTitle.textContent = defaultTitle;
        }
        document.title = appTitle.textContent;
    });
    appTitle.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); appTitle.blur(); }
    });

    // --- Model key (per-session in k8s-pups, global in standalone) ---
    var MODEL_KEY = 'chat-ui-model' + SESSION_SUFFIX;

    let currentAssistantMsg = null;
    let currentAssistantText = '';
    let needsParagraphBreak = false; // insert \n\n before next delta (after tool use etc.)
    let currentThinkingBlock = null; // per-turn collapsible block accumulating streamed reasoning
    let currentThinkingText = '';    // raw accumulated reasoning text shown in that block
    let thinkingStepOffset = 0;      // offset in currentThinkingText where the current step started
    let busy = false;
    let pendingPrompt = false;  // true when an ask_user prompt is awaiting user response

    // --- Chat history (persisted to localStorage) ---
    var HISTORY_KEY = 'chat-ui-history' + SESSION_SUFFIX;
    var MAX_HISTORY = 500;
    var chatHistory = []; // [{role: 'user'|'assistant'|'info'|'error', text: string}]

    // --- Prompt Queue (position-based, persisted to localStorage) ---
    var QUEUE_KEY = 'chat-ui-queue' + SESSION_SUFFIX;
    var queue = [];   // [{text: string, auto: boolean}]
    var queuePos = 0; // index of next item to send
    var MAX_QUEUE_SIZE = 100;

    // Configure marked for safe rendering
    marked.setOptions({
        breaks: true,
        gfm: true
    });

    // Fix unclosed markdown fences/inline-code so marked.parse() doesn't break mid-stream
    function closeOpenMarkdown(text) {
        // Count triple-backtick fences
        var fenceCount = (text.match(/```/g) || []).length;
        if (fenceCount % 2 !== 0) {
            text += '\n```';
            return text; // inside an unclosed fence — single backtick count is irrelevant
        }
        // Count single backticks with complete fenced blocks removed first.
        // (Simple replace(/```/g,'') was wrong: it kept content inside fences,
        //  including any backticks inside them, which inflated the count.)
        var withoutFences = text.replace(/```[\s\S]*?```/g, '');
        var tickCount = (withoutFences.match(/`/g) || []).length;
        if (tickCount % 2 !== 0) {
            text += '`';
        }
        return text;
    }

    // Render the final (complete) text of an assistant message.
    // Does NOT call closeOpenMarkdown — the stream is done, the text is complete.
    function renderFinalMarkdown(text) {
        var displayText = text
            .replace(/<think>[\s\S]*?<\/think>/g, '')
            .replace(/<think>[\s\S]*$/, '');
        return marked.parse(displayText);
    }

    // --- Timestamp helper ---

    function formatTime(date) {
        var y = date.getFullYear();
        var m = String(date.getMonth() + 1).padStart(2, '0');
        var d = String(date.getDate()).padStart(2, '0');
        var hh = String(date.getHours()).padStart(2, '0');
        var mm = String(date.getMinutes()).padStart(2, '0');
        var ss = String(date.getSeconds()).padStart(2, '0');
        var tz = -date.getTimezoneOffset();
        var tzSign = tz >= 0 ? '+' : '-';
        var tzH = String(Math.floor(Math.abs(tz) / 60)).padStart(2, '0');
        var tzM = String(Math.abs(tz) % 60).padStart(2, '0');
        return y + '-' + m + '-' + d + 'T' + hh + ':' + mm + ':' + ss + tzSign + tzH + ':' + tzM;
    }

    // --- Model loading ---

    function loadModels() {
        fetch('api/models')
            .then(function (resp) { return resp.json(); })
            .then(function (models) {
                modelSelect.innerHTML = '';
                // Count distinct servers among local models
                var servers = {};
                models.forEach(function (m) {
                    if (m.type === 'local' && m.server) {
                        servers[m.server] = true;
                    }
                });
                var serverCount = Object.keys(servers).length;

                // Local models first, then Claude models
                var localModels = models.filter(function (m) { return m.type === 'local'; });
                var cloudModels = models.filter(function (m) { return m.type !== 'local'; });
                var sorted = localModels.concat(cloudModels);

                sorted.forEach(function (m) {
                    var opt = document.createElement('option');
                    opt.value = m.name;
                    opt.setAttribute('data-type', m.type);
                    if (m.type === 'local') {
                        opt.textContent = serverCount > 1 && m.server
                            ? m.name + ' (' + m.server + ')'
                            : m.name + ' (local)';
                    } else {
                        opt.textContent = m.name;
                    }
                    modelSelect.appendChild(opt);
                });

                // Restore previously selected model from localStorage
                var savedModel = localStorage.getItem(MODEL_KEY);
                console.log('[chat-ui] model restore: saved=' + savedModel + ' options=' + modelSelect.options.length);
                if (savedModel) {
                    var found = false;
                    for (var i = 0; i < modelSelect.options.length; i++) {
                        if (modelSelect.options[i].value === savedModel) {
                            modelSelect.value = savedModel;
                            found = true;
                            break;
                        }
                    }
                    console.log('[chat-ui] model restore: found=' + found + ' current=' + modelSelect.value);
                }
            })
            .catch(function () {
                modelSelect.innerHTML = '';
                var opt = document.createElement('option');
                opt.value = '';
                opt.textContent = '(no models available)';
                opt.disabled = true;
                modelSelect.appendChild(opt);
            });
    }

    // --- EventSource SSE connection ---

    var eventSource = null;

    function connectSSE() {
        if (eventSource) {
            eventSource.close();
        }
        eventSource = new EventSource(apiUrl('api/chat/stream'));

        eventSource.onopen = function () {
            connectionStatus.textContent = 'ready';
            connectionStatus.className = 'connected';
        };

        eventSource.onmessage = function (event) {
            try {
                handleEvent(JSON.parse(event.data));
            } catch (e) {
                // skip non-JSON
            }
        };

        eventSource.onerror = function () {
            connectionStatus.textContent = 'reconnecting';
            connectionStatus.className = 'disconnected';
        };
    }

    // --- Event handling ---

    function handleEvent(event) {
        switch (event.type) {
            case 'delta':
                handleDelta(event.content);
                break;
            case 'thinking':
                handleThinking(event.content);
                break;
            case 'thinking_step':
                // Mark where the current agent step begins, so it can be dropped if it is the answer.
                thinkingStepOffset = currentThinkingText.length;
                break;
            case 'thinking_drop':
                dropCurrentThinkingStep();
                break;
            case 'result':
                handleResult(event);
                break;
            case 'error':
                appendMessage('error', event.content);
                // A turn ended in error: clear busy but do NOT auto-advance the queue.
                // (spec: error pauses the queue; the human resumes with an empty send.)
                finalizeThinkingBlock();
                busy = false;
                cancelBtn.disabled = true;
                break;
            case 'info':
                appendMessage('info', event.content);
                break;
            case 'translation':
                insertTranslation(event.content);
                break;
            case 'mcp_user':
                appendMcpUserMessage(event.content);
                break;
            case 'status':
                updateStatus(event);
                break;
            case 'prompt':
                handlePrompt(event);
                break;
        }
    }

    function startThinkingTimer() {
        if (thinkingTimer) return; // already running
        thinkingStartTime = Date.now();
        thinkingTimer = setInterval(updateElapsed, 1000);
    }

    function stopThinkingTimer() {
        if (thinkingTimer) {
            clearInterval(thinkingTimer);
            thinkingTimer = null;
        }
        thinkingStartTime = null;
        activityLabel.textContent = '';
        activityLabel.removeAttribute('data-base');
    }

    function updateElapsed() {
        if (!thinkingStartTime) return;
        var elapsed = Math.floor((Date.now() - thinkingStartTime) / 1000);
        var suffix = ' (' + elapsed + 's)';
        // Update thinking indicator in chat area
        if (currentAssistantMsg) {
            var indicator = currentAssistantMsg.querySelector('.thinking-indicator');
            if (indicator) {
                var base = indicator.getAttribute('data-base') || indicator.textContent;
                if (!indicator.getAttribute('data-base')) indicator.setAttribute('data-base', base);
                indicator.textContent = base + suffix;
            }
        }
        // Update activity label in status bar
        var base = activityLabel.getAttribute('data-base');
        if (base) {
            activityLabel.textContent = base + suffix;
        }
    }

    function handleThinking(content) {
        // Re-enable cancel if server is still active (e.g., after POST timeout)
        if (!busy) {
            busy = true;
            cancelBtn.disabled = false;
        }
        if (!content) return;

        // Accumulate the streamed reasoning into a per-turn collapsible "thinking" block, shown
        // live and kept separate from the final answer bubble. textContent (not innerHTML) keeps the
        // raw ReAct trace verbatim (Thought / Action / Action Input / Observation) and avoids injection.
        if (!currentThinkingBlock) {
            currentThinkingText = '';
            var details = document.createElement('details');
            details.className = 'thinking-block';
            details.open = true;
            var summary = document.createElement('summary');
            summary.textContent = 'Thinking…';
            var pre = document.createElement('pre');
            pre.className = 'thinking-body';
            details.appendChild(summary);
            details.appendChild(pre);
            chatArea.appendChild(details);
            currentThinkingBlock = details;
        }
        currentThinkingText += content;
        currentThinkingBlock.querySelector('.thinking-body').textContent = currentThinkingText;

        // Status-bar elapsed indicator
        activityLabel.setAttribute('data-base', 'Thinking…');
        activityLabel.textContent = 'Thinking…';
        startThinkingTimer();
        scrollToBottom();
    }

    // The current step turned out to be the final answer: remove its streamed text from the thinking
    // block (it is shown in the answer bubble instead). If nothing intermediate remains, drop the block
    // entirely — so a single-step answer shows ONLY the bubble, with no duplicated "thinking" copy.
    function dropCurrentThinkingStep() {
        if (!currentThinkingBlock) return;
        currentThinkingText = currentThinkingText.slice(0, thinkingStepOffset);
        if (currentThinkingText.trim() === '') {
            if (currentThinkingBlock.parentNode) {
                currentThinkingBlock.parentNode.removeChild(currentThinkingBlock);
            }
            currentThinkingBlock = null;
            currentThinkingText = '';
        } else {
            currentThinkingBlock.querySelector('.thinking-body').textContent = currentThinkingText;
        }
    }

    function handleDelta(content) {
        if (!content) {
            return;
        }

        // Re-enable cancel if server is still active (e.g., after POST timeout)
        if (!busy) {
            busy = true;
            cancelBtn.disabled = false;
        }

        if (!currentAssistantMsg) {
            currentAssistantMsg = document.createElement('div');
            currentAssistantMsg.className = 'message assistant streaming';
            chatArea.appendChild(currentAssistantMsg);
        }

        if (needsParagraphBreak) {
            needsParagraphBreak = false;
            if (currentAssistantText && !currentAssistantText.endsWith('\n\n')) {
                currentAssistantText += '\n\n';
            }
        }

        currentAssistantText += content;
        // Strip <think>...</think> blocks (Qwen3 thinking mode) from display
        var displayText = currentAssistantText
            .replace(/<think>[\s\S]*?<\/think>/g, '')
            .replace(/<think>[\s\S]*$/, '');  // partial unclosed <think> block
        currentAssistantMsg.innerHTML = marked.parse(closeOpenMarkdown(displayText));
        scrollToBottom();
    }

    function handleResult(msg) {
        stopThinkingTimer();
        if (currentAssistantMsg) {
            currentAssistantMsg.classList.remove('streaming');
            // Re-render with the complete text — no closeOpenMarkdown patching needed.
            if (currentAssistantText) {
                currentAssistantMsg.innerHTML = renderFinalMarkdown(currentAssistantText);
            }

            var footer = document.createElement('div');
            footer.className = 'message-footer';


            if (msg.costUsd != null && msg.costUsd > 0) {
                var cost = document.createElement('span');
                cost.textContent = 'Cost: $' + msg.costUsd.toFixed(4);
                footer.appendChild(cost);
            }
            if (msg.durationMs != null && msg.durationMs >= 0) {
                var duration = document.createElement('span');
                var secs = (msg.durationMs / 1000).toFixed(1);
                duration.textContent = 'Duration: ' + secs + 's';
                footer.appendChild(duration);
            }
            if (msg.sessionId) {
                var session = document.createElement('span');
                session.textContent = 'Session: ' + msg.sessionId.substring(0, 12) + '...';
                session.title = msg.sessionId;
                footer.appendChild(session);
            }

            // Model name
            var modelName = modelSelect.value || '';
            if (modelName) {
                var modelSpan = document.createElement('span');
                modelSpan.title = modelName;
                modelSpan.textContent = modelName.length > 30
                    ? modelName.substring(0, 30) + '...' : modelName;
                footer.appendChild(modelSpan);
            }

            // Copy as Markdown button
            var copyBtn = document.createElement('button');
            copyBtn.className = 'copy-md-btn';
            copyBtn.textContent = 'Copy MD';
            copyBtn.title = 'Copy as Markdown';
            var mdText = currentAssistantText;
            copyBtn.addEventListener('click', function () {
                navigator.clipboard.writeText(mdText).then(function () {
                    copyBtn.textContent = 'Copied!';
                    setTimeout(function () { copyBtn.textContent = 'Copy MD'; }, 1500);
                });
            });
            footer.appendChild(copyBtn);

            var timeSpan = document.createElement('span');
            timeSpan.textContent = formatTime(new Date());
            footer.appendChild(timeSpan);

            currentAssistantMsg.appendChild(footer);

            currentAssistantMsg = null;
            chatHistory.push({ role: 'assistant', text: currentAssistantText });
            currentAssistantText = '';
            needsParagraphBreak = false;
            turnCounter++;   // this turn is now committed server-side; keep the counter in sync
            saveHistory();
            scrollToBottom();
            trimChatArea();
        }

        // The result event itself is the authoritative turn-complete signal — its type carries
        // the meaning, so always clear busy and drain the queue (no separate busy flag needed).
        if (msg.model) {
            updateStatus(msg);
        }
        finalizeThinkingBlock();
        busy = false;
        cancelBtn.disabled = true;
        promptInput.focus();
        processQueue();
    }

    // Retention reduction: a finished turn's thinking block is a LIVE display buffer only (the I/O tab
    // holds the LLM REQUEST/RESPONSE and full tool observations in H2). To stop the left pane from
    // accumulating large thinking text across turns, once a turn ends we keep only a bounded head+tail
    // of its block in the DOM and collapse it (the live, in-progress turn keeps its full text).
    var THINKING_TRIM_THRESHOLD = 6000; // only trim a finished block longer than this
    var THINKING_KEEP_HEAD = 3500;
    var THINKING_KEEP_TAIL = 1500;

    // Release the per-turn thinking block (kept in the DOM, collapsed + trimmed) so the next turn
    // starts fresh and the finished block no longer holds its whole trace in memory.
    function finalizeThinkingBlock() {
        if (!currentThinkingBlock) return;
        var s = currentThinkingBlock.querySelector('summary');
        if (s) s.textContent = 'Thinking';
        var body = currentThinkingBlock.querySelector('.thinking-body');
        if (body) {
            var t = body.textContent || '';
            if (t.length > THINKING_TRIM_THRESHOLD) {
                var omitted = t.length - THINKING_KEEP_HEAD - THINKING_KEEP_TAIL;
                body.textContent = t.slice(0, THINKING_KEEP_HEAD)
                    + '\n\n… [' + omitted + ' chars trimmed from this live thinking view] …\n\n'
                    + t.slice(-THINKING_KEEP_TAIL);
            }
        }
        currentThinkingBlock.open = false; // collapse: a finished trace shouldn't dominate the view
        currentThinkingBlock = null;
        currentThinkingText = '';
    }

    function handlePrompt(event) {
        var div = document.createElement('div');
        div.className = 'message prompt';
        var contentP = document.createElement('p');
        contentP.textContent = event.content || 'Prompt from Claude';
        div.appendChild(contentP);

        // All prompts (ask_user, permission, etc.) are sent via /api/respond
        // which writes to Claude CLI's stdin. The CLI process is still running
        // and waiting for the response in its read loop.

        if (event.options && event.options.length > 0) {
            var btnGroup = document.createElement('div');
            btnGroup.className = 'prompt-buttons';
            event.options.forEach(function (opt) {
                var btn = document.createElement('button');
                btn.className = 'prompt-option-btn';
                btn.textContent = opt;
                btn.addEventListener('click', function () {
                    pendingPrompt = false;
                    sendResponse(event.promptId, opt);
                    div.classList.add('answered');
                    btnGroup.querySelectorAll('button').forEach(
                        function (b) { b.disabled = true; });
                });
                btnGroup.appendChild(btn);
            });
            div.appendChild(btnGroup);
        } else {
            // Free-text input for the response
            var inputRow = document.createElement('div');
            inputRow.className = 'prompt-input-row';
            var input = document.createElement('input');
            input.type = 'text';
            input.className = 'prompt-text-input';
            input.placeholder = 'Type your response...';
            var submitBtn = document.createElement('button');
            submitBtn.className = 'prompt-option-btn';
            submitBtn.textContent = 'Send';
            submitBtn.addEventListener('click', function () {
                if (input.value.trim()) {
                    pendingPrompt = false;
                    sendResponse(event.promptId, input.value.trim());
                    div.classList.add('answered');
                    input.disabled = true;
                    submitBtn.disabled = true;
                }
            });
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && !e.isComposing) {
                    e.preventDefault();
                    submitBtn.click();
                }
            });
            inputRow.appendChild(input);
            inputRow.appendChild(submitBtn);
            div.appendChild(inputRow);
        }

        chatArea.appendChild(div);
        chatHistory.push({ role: 'prompt', text: event.content || '' });
        saveHistory();
        scrollToBottom();
    }

    // Send text as a regular chat message (for ask_user prompt responses)
    function sendPromptText(text) {
        queue.splice(queuePos, 0, { text: text, auto: true });
        trimQueue();
        renderQueue();
        saveQueue();
        if (!busy) {
            processQueue();
        } else {
            showQueue();
        }
    }

    async function sendResponse(promptId, response) {
        try {
            await fetch(apiUrl('api/respond'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ promptId: promptId, response: response })
            });
            chatHistory.push({ role: 'user', text: '[Response] ' + response });
            saveHistory();
        } catch (e) {
            appendMessage('error', 'Failed to send response: ' + e.message);
        }
    }

    function updateStatus(msg) {
        if (msg.model) {
            // Restore from localStorage first; fall back to server-side model
            var savedModel = localStorage.getItem(MODEL_KEY);
            var targetModel = savedModel || msg.model;
            var currentOption = modelSelect.options[modelSelect.selectedIndex];
            var currentIsLocal = currentOption && currentOption.getAttribute('data-type') === 'local';
            console.log('[chat-ui] updateStatus model: server=' + msg.model + ' saved=' + savedModel + ' target=' + targetModel + ' currentIsLocal=' + currentIsLocal);
            if (!currentIsLocal) {
                // Only set if the target model exists in the dropdown
                for (var i = 0; i < modelSelect.options.length; i++) {
                    if (modelSelect.options[i].value === targetModel) {
                        modelSelect.value = targetModel;
                        break;
                    }
                }
            }
        }
        if (msg.busy != null) {
            busy = msg.busy;
            cancelBtn.disabled = !busy;
            if (!busy) {
                stopThinkingTimer();
                promptInput.focus();
                // Safety net: if result event doesn't arrive (e.g., error path),
                // process queue after a longer delay to avoid being stuck.
                // Normal path: handleResult() calls processQueue() immediately.
                setTimeout(function () {
                    if (!busy) processQueue();
                }, 2000);
            }
        }
    }

    var notificationBar = document.getElementById('notification-bar');
    function isTaskNotification(text) {
        return text && text.indexOf('<task-notification>') !== -1;
    }
    function showNotification(text) {
        var item = document.createElement('div');
        item.className = 'notification-item';
        var statusMatch = text.match(/<status>(.*?)<\/status>/);
        var summaryMatch = text.match(/<summary>(.*?)<\/summary>/);
        var status = statusMatch ? statusMatch[1] : 'unknown';
        var summary = summaryMatch ? summaryMatch[1] : 'Background task notification';
        item.textContent = '[' + status + '] ' + summary;
        item.className += ' notification-' + status;
        notificationBar.appendChild(item);
        notificationBar.style.display = 'block';
        setTimeout(function () {
            item.classList.add('notification-fade');
            setTimeout(function () {
                if (item.parentNode) item.parentNode.removeChild(item);
                if (notificationBar.children.length === 0) notificationBar.style.display = 'none';
            }, 500);
        }, 10000);
    }

    // Insert a translation bubble directly below the user message, before the
    // assistant "Waiting for response..." placeholder that was already added to the DOM.
    function insertTranslation(text) {
        var div = document.createElement('div');
        div.className = 'message translation';
        div.textContent = text;
        // currentAssistantMsg is the "Waiting for response..." div that sits right
        // after the user message. Inserting before it places the translation between
        // the user message and the assistant placeholder — exactly where it belongs.
        if (currentAssistantMsg && currentAssistantMsg.parentNode === chatArea) {
            chatArea.insertBefore(div, currentAssistantMsg);
        } else {
            chatArea.appendChild(div);
        }
        chatHistory.push({ role: 'translation', text: text });
        saveHistory();
        scrollToBottom();
        trimChatArea();
    }

    function appendMessage(className, text) {
        if (className === 'user' && isTaskNotification(text)) {
            showNotification(text);
            return;
        }
        var div = document.createElement('div');
        div.className = 'message ' + className;
        div.textContent = text;
        if (className === 'user') {
            var footer = document.createElement('div');
            footer.className = 'message-footer';
            var timeSpan = document.createElement('span');
            timeSpan.textContent = formatTime(new Date());
            footer.appendChild(timeSpan);
            var copyBtn = document.createElement('button');
            copyBtn.className = 'copy-md-btn';
            copyBtn.textContent = 'Copy';
            copyBtn.title = 'Copy prompt text';
            var promptText = text;
            copyBtn.addEventListener('click', function () {
                navigator.clipboard.writeText(promptText).then(function () {
                    copyBtn.textContent = 'Copied!';
                    setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1500);
                });
            });
            footer.appendChild(copyBtn);
            div.appendChild(footer);
        }
        chatArea.appendChild(div);
        chatHistory.push({ role: className, text: text });
        saveHistory();
        scrollToBottom();
        trimChatArea();
    }

    function appendMcpUserMessage(text) {
        var div = document.createElement('div');
        div.className = 'message user mcp-user';
        div.textContent = text;
        var footer = document.createElement('div');
        footer.className = 'message-footer';
        var timeSpan = document.createElement('span');
        timeSpan.textContent = formatTime(new Date());
        footer.appendChild(timeSpan);
        div.appendChild(footer);
        chatArea.appendChild(div);
        chatHistory.push({ role: 'user', text: text });
        saveHistory();
        scrollToBottom();
        trimChatArea();
    }

    // --- History persistence (localStorage) ---

    function saveHistory() {
        var toSave = chatHistory.slice(-MAX_HISTORY);
        try {
            localStorage.setItem(HISTORY_KEY, JSON.stringify(toSave));
        } catch (e) {
            // localStorage full — trim aggressively
            try {
                localStorage.setItem(HISTORY_KEY, JSON.stringify(chatHistory.slice(-50)));
            } catch (e2) { /* give up */ }
        }
    }

    // Conversation turn counter, kept in sync with the server's committed turns so the left pane can
    // label each turn (matching the Trace's "Turn N"). Set on hydrate, bumped on each completed turn.
    var turnCounter = 0;

    // A small header for a user turn: its number (matches Trace "Turn N") and, when the prompt was
    // NOT entered in this browser, a "via <source>" tag so non-UI input is visible.
    function turnBadgeEl(turnNo, source) {
        var b = document.createElement('div'); b.className = 'turn-badge';
        var n = document.createElement('span'); n.className = 'turn-no';
        n.textContent = 'Turn ' + turnNo;
        b.appendChild(n);
        if (source && source !== 'browser') {
            var s = document.createElement('span'); s.className = 'turn-src';
            s.textContent = 'via ' + source;
            b.appendChild(s);
        }
        return b;
    }

    // Appends a user message bubble with its turn badge (used by both live sends and server hydration).
    function appendUserTurn(text, turnNo, source) {
        var div = document.createElement('div'); div.className = 'message user';
        div.appendChild(turnBadgeEl(turnNo, source));
        var body = document.createElement('div'); body.className = 'msg-body'; body.textContent = text;
        div.appendChild(body);
        var footer = document.createElement('div'); footer.className = 'message-footer';
        var ts = document.createElement('span'); ts.textContent = formatTime(new Date());
        footer.appendChild(ts);
        var copyBtn = document.createElement('button');
        copyBtn.className = 'copy-md-btn'; copyBtn.textContent = 'Copy'; copyBtn.title = 'Copy prompt text';
        copyBtn.addEventListener('click', function () {
            navigator.clipboard.writeText(text).then(function () {
                copyBtn.textContent = 'Copied!'; setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1500);
            });
        });
        footer.appendChild(copyBtn); div.appendChild(footer);
        chatArea.appendChild(div);
        chatHistory.push({ role: 'user', text: text });
        saveHistory(); scrollToBottom(); trimChatArea();
        return div;
    }

    // Render the left pane from the SERVER's authoritative conversation (ConversationStore) so the chat
    // always reflects what the model actually has in context — including turns entered by non-UI
    // clients (which carry a "via api" tag). Falls back to the localStorage cache if unavailable/empty.
    function hydrateConversation() {
        return fetch(apiUrl('api/conversation'))
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (turns) {
                if (!Array.isArray(turns) || !turns.length) { restoreHistory(); return; }
                chatArea.innerHTML = ''; chatHistory = [];
                turns.forEach(function (t) {
                    appendUserTurn(t.question, t.turn, t.source);
                    chatArea.appendChild(createAssistantDiv(t.answer));
                    chatHistory.push({ role: 'assistant', text: t.answer });
                });
                saveHistory();
                turnCounter = turns[turns.length - 1].turn;
                scrollToBottom();
            })
            .catch(function () { restoreHistory(); });
    }

    function restoreHistory() {
        try {
            var saved = localStorage.getItem(HISTORY_KEY);
            if (!saved) return;
            var entries = JSON.parse(saved);
            if (!Array.isArray(entries) || entries.length === 0) return;

            chatHistory = entries.filter(function (e) { return !(e.role === 'user' && isTaskNotification(e.text)); });
            for (var i = 0; i < entries.length; i++) {
                var entry = entries[i];
                if (entry.role === 'assistant') {
                    chatArea.appendChild(createAssistantDiv(entry.text));
                } else if (entry.role === 'prompt') {
                    var div = document.createElement('div');
                    div.className = 'message prompt answered';
                    var p = document.createElement('p');
                    p.textContent = entry.text;
                    div.appendChild(p);
                    chatArea.appendChild(div);
                } else {
                    var div = document.createElement('div');
                    div.className = 'message ' + entry.role;
                    div.textContent = entry.text;
                    if (entry.role === 'user') {
                        var footer = document.createElement('div');
                        footer.className = 'message-footer';
                        var copyBtn = document.createElement('button');
                        copyBtn.className = 'copy-md-btn';
                        copyBtn.textContent = 'Copy';
                        copyBtn.title = 'Copy prompt text';
                        (function(t, btn) {
                            btn.addEventListener('click', function () {
                                navigator.clipboard.writeText(t).then(function () {
                                    btn.textContent = 'Copied!';
                                    setTimeout(function () { btn.textContent = 'Copy'; }, 1500);
                                });
                            });
                        })(entry.text, copyBtn);
                        footer.appendChild(copyBtn);
                        div.appendChild(footer);
                    }
                    chatArea.appendChild(div);
                }
            }
            scrollToBottom();
        } catch (e) {
            // ignore restore errors
        }
    }

    // --- Queue persistence (localStorage) ---

    function saveQueue() {
        try {
            localStorage.setItem(QUEUE_KEY, JSON.stringify({ queue: queue, pos: queuePos }));
        } catch (e) { /* ignore */ }
    }

    function restoreQueue() {
        try {
            var saved = localStorage.getItem(QUEUE_KEY);
            if (!saved) return;
            var data = JSON.parse(saved);
            if (data && Array.isArray(data.queue)) {
                queue = data.queue;
                queuePos = typeof data.pos === 'number' ? data.pos : 0;
                if (queuePos > queue.length) queuePos = queue.length;
                if (queue.length > 0) {
                    showQueue();
                    renderQueue();
                }
            }
        } catch (e) { /* ignore */ }
    }

    function createAssistantDiv(text) {
        var div = document.createElement('div');
        div.className = 'message assistant';
        div.innerHTML = marked.parse(text);

        var footer = document.createElement('div');
        footer.className = 'message-footer';

        var copyBtn = document.createElement('button');
        copyBtn.className = 'copy-md-btn';
        copyBtn.textContent = 'Copy MD';
        copyBtn.title = 'Copy as Markdown';
        copyBtn.addEventListener('click', function () {
            navigator.clipboard.writeText(text).then(function () {
                copyBtn.textContent = 'Copied!';
                setTimeout(function () { copyBtn.textContent = 'Copy MD'; }, 1500);
            });
        });
        footer.appendChild(copyBtn);
        div.appendChild(footer);
        return div;
    }

    var userScrolledUp = false;

    chatArea.addEventListener('scroll', function () {
        var threshold = 80;
        var atBottom = chatArea.scrollHeight - chatArea.scrollTop - chatArea.clientHeight < threshold;
        userScrolledUp = !atBottom;
    });

    function scrollToBottom() {
        if (!userScrolledUp) {
            chatArea.scrollTop = chatArea.scrollHeight;
        }
    }

    function forceScrollToBottom() {
        userScrolledUp = false;
        chatArea.scrollTop = chatArea.scrollHeight;
    }

    // --- Chat area memory limit ---
    var MAX_CHAT_LINES = 5000;

    function trimChatArea() {
        if (chatArea.children.length < 3) return;
        var totalLines = chatArea.innerText.split('\n').length;
        while (totalLines > MAX_CHAT_LINES && chatArea.children.length > 1) {
            var oldest = chatArea.children[0];
            var oldestLines = (oldest.innerText || '').split('\n').length;
            chatArea.removeChild(oldest);
            totalLines -= oldestLines;
        }
    }

    // --- Queue management (position-based) ---

    function addToQueue(text) {
        queue.push({ text: text, auto: true });
        trimQueue();
        renderQueue();
        saveQueue();
    }

    function trimQueue() {
        while (queue.length > MAX_QUEUE_SIZE) {
            queue.shift();
            if (queuePos > 0) queuePos--;
        }
    }

    function removeFromQueue(index) {
        queue.splice(index, 1);
        if (index < queuePos) {
            queuePos--;
        } else if (index === queuePos && queuePos >= queue.length) {
            // pos was pointing at removed item which was last
        }
        renderQueue();
        saveQueue();
    }

    function toggleAutoInQueue(index) {
        if (index >= 0 && index < queue.length) {
            queue[index].auto = !queue[index].auto;
            renderQueue();
            saveQueue();
        }
    }

    function moveInQueue(index, direction) {
        var target = index + direction;
        if (index < queuePos || target < queuePos || target >= queue.length) return;
        var tmp = queue[index];
        queue[index] = queue[target];
        queue[target] = tmp;
        renderQueue();
        saveQueue();
    }

    function hasPending() {
        return queuePos < queue.length;
    }

    function renderQueue() {
        if (queue.length === 0) {
            queueArea.innerHTML = '<div class="queue-header"><span>Queue is empty</span></div>';
            return;
        }

        var pending = queue.length - queuePos;
        var headerText = 'Queue (' + queue.length + ')';
        if (pending > 0) {
            headerText += ' - ' + pending + ' pending';
        }
        headerText += ':';

        var html = '<div class="queue-header">'
            + '<span>' + escapeHtml(headerText) + '</span>'
            + '<button class="queue-save-btn" id="queue-save-btn" title="Save as Markdown">Save</button>'
            + '</div>';

        for (var i = 0; i < queue.length; i++) {
            var item = queue[i];
            var displayText = item.text;
            var sent = (i < queuePos);
            var isCurrent = (i === queuePos);
            var isWaiting = (isCurrent && !busy && !item.auto);

            var cls = 'queue-item';
            if (sent) cls += ' sent';
            if (isCurrent) cls += ' current';
            if (isWaiting) cls += ' waiting';

            var checked = item.auto ? ' checked' : '';

            html += '<div class="' + cls + '" data-index="' + i + '">'
                + '<span class="queue-index">' + (i + 1) + '.</span>'
                + '<span class="queue-text" title="' + escapeAttr(item.text) + '">'
                + escapeHtml(displayText) + '</span>';

            if (!sent) {
                html += '<label class="queue-auto">'
                    + '<input type="checkbox"' + checked + ' data-queue-auto="' + i + '"> Auto</label>';
                var canUp = (i > queuePos);
                var canDown = (i < queue.length - 1);
                html += '<button class="queue-move" data-queue-up="' + i + '"'
                    + (canUp ? '' : ' disabled') + ' title="Move up">&uarr;</button>';
                html += '<button class="queue-move" data-queue-down="' + i + '"'
                    + (canDown ? '' : ' disabled') + ' title="Move down">&darr;</button>';
            }

            html += '<button class="queue-edit" data-queue-edit="' + i + '" title="Edit (copy to input)">📝</button>';
            html += '<button class="queue-remove" data-queue-remove="' + i + '" title="Remove">&times;</button>'
                + '</div>';
        }

        queueArea.innerHTML = html;
        queueArea.scrollTop = queueArea.scrollHeight;
    }

    // Delegate click events on queue area
    queueArea.addEventListener('click', function (e) {
        var editBtn = e.target.closest('[data-queue-edit]');
        if (editBtn) {
            var idx = parseInt(editBtn.getAttribute('data-queue-edit'), 10);
            var item = queue[idx];
            if (item) {
                promptInput.value = item.text;
                autoResize();
                promptInput.focus();
            }
            return;
        }

        var removeBtn = e.target.closest('[data-queue-remove]');
        if (removeBtn) {
            var idx = parseInt(removeBtn.getAttribute('data-queue-remove'), 10);
            removeFromQueue(idx);
            return;
        }

        var upBtn = e.target.closest('[data-queue-up]');
        if (upBtn) {
            var idx = parseInt(upBtn.getAttribute('data-queue-up'), 10);
            moveInQueue(idx, -1);
            return;
        }

        var downBtn = e.target.closest('[data-queue-down]');
        if (downBtn) {
            var idx = parseInt(downBtn.getAttribute('data-queue-down'), 10);
            moveInQueue(idx, 1);
            return;
        }

        if (e.target.id === 'queue-save-btn' || e.target.closest('#queue-save-btn')) {
            saveQueueAsMarkdown();
            return;
        }
    });

    queueArea.addEventListener('change', function (e) {
        if (e.target.hasAttribute('data-queue-auto')) {
            var idx = parseInt(e.target.getAttribute('data-queue-auto'), 10);
            toggleAutoInQueue(idx);
        }
    });

    // --- Input textarea resize handle ---
    var INPUT_HEIGHT_KEY = 'chat-ui-input-height' + SESSION_SUFFIX;
    var savedInputHeight = localStorage.getItem(INPUT_HEIGHT_KEY);
    if (savedInputHeight) {
        promptInput.style.height = savedInputHeight + 'px';
    } else {
        // Default: match rows="5" (line-height:1.5 * 14px * 5 + 2*10px padding)
        promptInput.style.height = '125px';
    }

    (function () {
        var dragging = false;
        var startY = 0;
        var startHeight = 0;

        inputResizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            dragging = true;
            startY = e.clientY;
            startHeight = promptInput.offsetHeight;
            inputResizeHandle.classList.add('dragging');
            document.body.style.cursor = 'ns-resize';
            document.body.style.userSelect = 'none';
        });

        document.addEventListener('mousemove', function (e) {
            if (!dragging) return;
            var delta = startY - e.clientY;
            var newHeight = Math.max(42, Math.min(startHeight + delta, 500));
            promptInput.style.height = newHeight + 'px';
        });

        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            dragging = false;
            inputResizeHandle.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            localStorage.setItem(INPUT_HEIGHT_KEY, promptInput.offsetHeight);
        });
    })();

    // --- Queue resize handle ---
    var QUEUE_HEIGHT_KEY = 'chat-ui-queue-height' + SESSION_SUFFIX;
    var savedQueueHeight = localStorage.getItem(QUEUE_HEIGHT_KEY);
    if (savedQueueHeight) {
        queueArea.style.height = savedQueueHeight + 'px';
    } else {
        queueArea.style.height = '130px';
    }

    (function () {
        var dragging = false;
        var startY = 0;
        var startHeight = 0;

        queueResizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            dragging = true;
            startY = e.clientY;
            startHeight = queueArea.offsetHeight;
            queueResizeHandle.classList.add('dragging');
            document.body.style.cursor = 'ns-resize';
            document.body.style.userSelect = 'none';
        });

        document.addEventListener('mousemove', function (e) {
            if (!dragging) return;
            var delta = startY - e.clientY;
            var newHeight = Math.max(60, Math.min(startHeight + delta, 400));
            queueArea.style.height = newHeight + 'px';
        });

        document.addEventListener('mouseup', function () {
            if (!dragging) return;
            dragging = false;
            queueResizeHandle.classList.remove('dragging');
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
            localStorage.setItem(QUEUE_HEIGHT_KEY, queueArea.offsetHeight);
        });
    })();

    function saveQueueAsMarkdown() {
        if (queue.length === 0) return;

        var lines = ['# Prompt Queue', ''];
        for (var i = 0; i < queue.length; i++) {
            var item = queue[i];
            var marker = (i < queuePos) ? '[x]' : '[ ]';
            lines.push((i + 1) + '. ' + marker + ' ' + item.text);
        }
        lines.push('');

        var blob = new Blob([lines.join('\n')], { type: 'text/markdown' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        var now = new Date();
        var ts = now.getFullYear()
            + String(now.getMonth() + 1).padStart(2, '0')
            + String(now.getDate()).padStart(2, '0')
            + '-' + String(now.getHours()).padStart(2, '0')
            + String(now.getMinutes()).padStart(2, '0');
        a.download = 'prompt-queue-' + ts + '.md';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    function saveChatAsMarkdown() {
        if (chatHistory.length === 0) return;

        var now = new Date();
        var dateStr = now.getFullYear() + '-'
            + String(now.getMonth() + 1).padStart(2, '0') + '-'
            + String(now.getDate()).padStart(2, '0') + ' '
            + String(now.getHours()).padStart(2, '0') + ':'
            + String(now.getMinutes()).padStart(2, '0');

        var lines = ['# Conversation - ' + dateStr, ''];

        for (var i = 0; i < chatHistory.length; i++) {
            var entry = chatHistory[i];
            if (entry.role === 'user') {
                lines.push('## User', '', entry.text, '');
            } else if (entry.role === 'assistant') {
                lines.push('## Assistant', '', entry.text, '');
            } else if (entry.role === 'info') {
                lines.push('> [info] ' + entry.text, '');
            } else if (entry.role === 'error') {
                lines.push('> [error] ' + entry.text, '');
            } else if (entry.role === 'prompt') {
                lines.push('> [prompt] ' + entry.text, '');
            }
        }

        var blob = new Blob([lines.join('\n')], { type: 'text/markdown' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        var ts = now.getFullYear()
            + String(now.getMonth() + 1).padStart(2, '0')
            + String(now.getDate()).padStart(2, '0')
            + String(now.getHours()).padStart(2, '0')
            + String(now.getMinutes()).padStart(2, '0');
        a.download = 'conversation-' + ts + '.md';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    function escapeHtml(str) {
        var div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
                  .replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function processQueue() {
        if (busy) return;          // a turn is in flight — never dispatch concurrently (spec: one turn at a time)
        if (pendingPrompt) return;
        if (!hasPending()) return;

        if (queue[queuePos].auto) {
            sendFromQueue();
        } else {
            renderQueue();
        }
    }

    function sendFromQueue() {
        if (!hasPending()) return;
        var item = queue[queuePos];
        queuePos++;
        renderQueue();
        saveQueue();
        executePrompt(item.text);
    }

    // --- Send prompt ---

    function sendPrompt() {
        var text = promptInput.value.trim();

        if (!text) {
            // Empty send = "send the next one": dispatch the front pending item by one,
            // regardless of its auto value. This is the manual advance / resume action.
            if (!busy && hasPending()) {
                sendFromQueue();
            }
            return;
        }

        promptInput.value = '';
        autoResize();

        // Always add to queue, then send if not busy
        addToQueue(text);
        if (!busy) {
            processQueue();
        } else {
            renderQueue();
            showQueue();
        }
    }

    async function executePrompt(text) {
        // Display user message with its turn number (this browser = source "browser").
        appendUserTurn(text, turnCounter + 1, 'browser');
        busy = true;
        cancelBtn.disabled = false;

        // Show immediate thinking indicator (before API responds)
        currentAssistantMsg = document.createElement('div');
        currentAssistantMsg.className = 'message assistant streaming';
        currentAssistantMsg.innerHTML = '<span class="thinking-indicator">Waiting for response...</span>';
        chatArea.appendChild(currentAssistantMsg);
        forceScrollToBottom();
        activityLabel.setAttribute('data-base', 'Waiting for response...');
        activityLabel.textContent = 'Waiting for response...';
        startThinkingTimer();

        // Submit prompt via POST; events arrive through EventSource
        try {
            var response = await fetch(apiUrl('api/chat'), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text, source: 'browser', model: modelSelect.value, noThink: !(document.getElementById('think-check') || {checked: true}).checked })
            });
            if (!response.ok) {
                stopThinkingTimer();
                if (currentAssistantMsg && !currentAssistantText) {
                    currentAssistantMsg.remove();
                    currentAssistantMsg = null;
                }
                var errText = await response.text();
                appendMessage('error', 'HTTP ' + response.status + ': ' + errText.substring(0, 200));
                busy = false;
                cancelBtn.disabled = true;
                return;                       // error: pause the queue (no auto-advance)
            }
            var result = await response.json();
            if (result.type === 'error') {
                appendMessage('error', result.content);
                busy = false;
                cancelBtn.disabled = true;    // error: pause the queue (no auto-advance)
            }
            // Events will arrive through the EventSource connection
        } catch (e) {
            // Clean up thinking indicator if no content was streamed
            stopThinkingTimer();
            if (currentAssistantMsg && !currentAssistantText) {
                currentAssistantMsg.remove();
                currentAssistantMsg = null;
            }
            appendMessage('error', 'Request failed: ' + e.message);
            busy = false;
            cancelBtn.disabled = true;       // error: pause the queue (no auto-advance)
        }
    }

    async function cancelRequest() {
        if (!busy) return;                 // nothing in flight to cancel
        try {
            await fetch(apiUrl('api/cancel'), { method: 'POST' });
        } catch (e) {
            // ignore network errors on cancel
        }
        // Cancel = pause: return to Idle, keep the partial reply, do NOT advance the queue.
        stopThinkingTimer();
        if (currentAssistantMsg) {
            currentAssistantMsg.classList.remove('streaming');
            currentAssistantMsg = null;
        }
        currentAssistantText = '';
        finalizeThinkingBlock();
        busy = false;
        cancelBtn.disabled = true;
        appendMessage('info', 'Cancelled');
    }

    // --- Input handling (IME-safe) ---

    promptInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && e.shiftKey && !e.isComposing) {
            e.preventDefault();
            sendPrompt();
        }
    });

    promptInput.addEventListener('input', function () {
        autoResize();
    });

    function autoResize() {
        promptInput.style.height = 'auto';
        promptInput.style.height = Math.min(promptInput.scrollHeight, 200) + 'px';
    }

    sendBtn.addEventListener('click', sendPrompt);

    function showQueue() {
        queueArea.style.display = 'block';
        queueResizeHandle.style.display = 'block';
    }

    function hideQueue() {
        queueArea.style.display = 'none';
        queueResizeHandle.style.display = 'none';
    }

    queueBtn.addEventListener('click', function () {
        var text = promptInput.value.trim();
        if (text) {
            promptInput.value = '';
            autoResize();
            queue.push({ text: text, auto: false });
            trimQueue();
            showQueue();
            renderQueue();
            saveQueue();
            queueArea.scrollTop = queueArea.scrollHeight;
        } else {
            if (queueArea.style.display === 'none' || !queueArea.style.display) {
                showQueue();
                renderQueue();
                queueArea.scrollTop = queueArea.scrollHeight;
            } else {
                hideQueue();
            }
        }
    });

    cancelBtn.addEventListener('click', cancelRequest);

    document.getElementById('save-chat-btn').addEventListener('click', saveChatAsMarkdown);

    // Clears the on-screen view only (DOM + localStorage + queue). The server-side conversation
    // memory and the I/O-log session are NOT touched — the model still remembers.
    function resetView() {
        chatArea.innerHTML = '';
        chatHistory = [];
        currentAssistantMsg = null;
        currentAssistantText = '';
        currentThinkingBlock = null;
        currentThinkingText = '';
        needsParagraphBreak = false;
        busy = false;
        cancelBtn.disabled = true;
        stopThinkingTimer();
        queue = [];
        queuePos = 0;
        pendingPrompt = false;
        localStorage.removeItem(HISTORY_KEY);
        promptInput.focus();
    }

    document.getElementById('clear-chat-btn').addEventListener('click', resetView);

    // Starts a new conversation: clear the view AND reset the server's conversation memory and the
    // I/O-log session (DELETE /api/history). The next turn begins a fresh memory and a new session.
    document.getElementById('new-conversation-btn').addEventListener('click', function () {
        resetView();
        turnCounter = 0;   // new conversation: turns restart at 1
        fetch(apiUrl('api/history'), { method: 'DELETE' }).catch(function () { /* best-effort */ });
    });

    modelSelect.addEventListener('change', async function () {
        var selected = modelSelect.value;
        var option = modelSelect.options[modelSelect.selectedIndex];
        var isLocal = option && option.getAttribute('data-type') === 'local';

        // Persist selected model across page reloads
        try {
            localStorage.setItem(MODEL_KEY, selected);
            console.log('[chat-ui] model saved: ' + selected + ' verify=' + localStorage.getItem(MODEL_KEY));
        } catch (e) {
            console.error('[chat-ui] model save FAILED:', e);
        }

        // The model is sent per-request; nothing to update server-side.
        appendMessage('info', 'Switched to model: ' + selected);
    });

    document.getElementById('refresh-models-btn').addEventListener('click', function () {
        loadModels();
    });

    // --- Load app config (title, keybind, auth, logs) ---
    fetch('api/config')
        .then(function (resp) { return resp.json(); })
        .then(function (cfg) {
            if (cfg.title) {
                document.title = cfg.title;
                var h1 = document.querySelector('header h1');
                if (h1) h1.textContent = cfg.title;
            }
            if (cfg.keybind) {
                activeKeybind = cfg.keybind;
            }
        })
        .catch(function () {});

    // --- Initial data load ---
    loadModels();
    hydrateConversation();   // render the left pane from the server's authoritative conversation
    restoreQueue();
    connectSSE();
    promptInput.focus();



    // ── Extensions panel ──────────────────────────────────────────────────────
    (function initExtensions() {
        var extBtn     = document.getElementById('extensions-btn');
        if (!extBtn) return;   // extensions panel removed from the UI; IIFE no-ops
        var extPanel   = document.getElementById('extensions-panel');
        var extTabs    = document.querySelectorAll('.ext-tab');
        var extContent = document.getElementById('ext-content');
        var extData    = null;
        var extActive  = 'skills';

        function renderTab(tab) {
            extActive = tab;
            if (!extData) { extContent.innerHTML = '<div class="ext-loading">Loading…</div>'; return; }
            var map = {
                skills: [['Command','Description'], extData.skills,
                          function(r) { return ['/'+r.name, r.description]; }, 'skills'],
                agents: [['Agent','Description'], extData.agents,
                          function(r) { return [r.name, r.description]; }, 'agents'],
                hooks:  [['Event','Matcher','Command'], extData.hooks,
                          function(r) { return [r.event, r.matcher||'—', r.command]; }, null],
                mcp:    [['Name','Type','Description / Endpoint'], extData.mcpServers,
                          function(r) { return [r.name, r.type, r.description||r.endpoint||'']; }, null]
            };
            var cfg = map[tab];
            if (!cfg) return;
            extContent.innerHTML = buildTable(cfg[0], cfg[1], cfg[2], cfg[3]);
        }

        function buildTable(headers, rows, rowFn, clickType) {
            if (!rows || !rows.length)
                return '<div class="ext-loading">No entries found.</div>';
            var h = '<table class="ext-table"><thead><tr>';
            headers.forEach(function(hd) { h += '<th>'+esc(hd)+'</th>'; });
            h += '</tr></thead><tbody>';
            rows.forEach(function(r) {
                var cells = rowFn(r);
                var rowCls = clickType ? ' class="ext-clickable"' : '';
                var rowData = clickType
                    ? ' data-type="'+esc(clickType)+'" data-name="'+esc(r.name||'')+'"'
                    : '';
                h += '<tr'+rowCls+rowData+'>';
                cells.forEach(function(c, i) {
                    var cls = i===0 ? 'ext-name' : (i===headers.length-1 ? 'ext-desc' : '');
                    h += '<td'+(cls?' class="'+cls+'"':'')+'>'+esc(c||'')+'</td>';
                });
                h += '</tr>';
            });
            return h + '</tbody></table>';
        }

        function esc(s) {
            return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')
                            .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
        }

        function setTabCounts() {
            if (!extData) return;
            var c = {skills:extData.skills.length, agents:extData.agents.length,
                     hooks:extData.hooks.length, mcp:extData.mcpServers.length};
            var labels = {skills:'Skills',agents:'Agents',hooks:'Hooks',mcp:'MCP Servers'};
            extTabs.forEach(function(t) {
                var k = t.dataset.tab;
                t.innerHTML = labels[k]+' <span class="ext-count">'+'('+( c[k]||0 )+')'+'</span>';
            });
        }

        function loadData() {
            if (extData) return;
            fetch('/api/extensions')
                .then(function(r) { return r.json(); })
                .then(function(d) { extData = d; setTabCounts(); renderTab(extActive); })
                .catch(function(e) {
                    extContent.innerHTML = '<div class="ext-loading">Error: '+esc(e.message)+'</div>';
                });
        }

        extBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            var open = extPanel.style.display !== 'none';
            extPanel.style.display = open ? 'none' : 'flex';
            if (!open) loadData();
        });

        extTabs.forEach(function(t) {
            t.addEventListener('click', function() {
                extTabs.forEach(function(x) { x.classList.remove('active'); });
                t.classList.add('active');
                renderTab(t.dataset.tab);
            });
        });

        // Click on skill/agent row → open content dialog
        extContent.addEventListener('click', function(e) {
            var row = e.target.closest('tr[data-type]');
            if (!row) return;
            openExtDialog(row.dataset.type, row.dataset.name);
        });

        document.addEventListener('click', function(e) {
            if (!document.getElementById('extensions-menu').contains(e.target))
                extPanel.style.display = 'none';
        });

        // ── Extension content dialog ─────────────────────────────────────────
        var extDialogOverlay = document.getElementById('ext-dialog-overlay');
        var extDialogTitle   = document.getElementById('ext-dialog-title');
        var extDialogBody    = document.getElementById('ext-dialog-body');

        function openExtDialog(type, name) {
            var prefix = type === 'skills' ? '/' : '';
            extDialogTitle.textContent = prefix + name;
            extDialogBody.innerHTML = '<div class="ext-loading">Loading…</div>';
            extDialogOverlay.style.display = 'flex';
            fetch('/api/extensions/content?type=' + encodeURIComponent(type)
                  + '&name=' + encodeURIComponent(name))
                .then(function(r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.text();
                })
                .then(function(md) {
                    extDialogBody.innerHTML = (typeof marked !== 'undefined')
                        ? marked.parse(md)
                        : '<pre>' + esc(md) + '</pre>';
                })
                .catch(function(err) {
                    extDialogBody.innerHTML = '<div class="ext-loading">Error: ' + esc(err.message) + '</div>';
                });
        }

        document.getElementById('ext-dialog-close').addEventListener('click', function() {
            extDialogOverlay.style.display = 'none';
        });
        extDialogOverlay.addEventListener('click', function(e) {
            if (e.target === extDialogOverlay) extDialogOverlay.style.display = 'none';
        });
        document.addEventListener('keydown', function(e) {
            if (e.key === 'Escape' && extDialogOverlay.style.display !== 'none')
                extDialogOverlay.style.display = 'none';
        });
    })();
    // ── End Extensions panel ──────────────────────────────────────────────────

    } // end doInitApp
})();
