/**
 * DataAgent 前端：原生 JS 消费 /api/chat/stream 的 SSE 流。
 * 事件序列：meta → delta*n → tool/step*n → done（异常时 error）。
 * delta 实时打字机；tool/step 进执行轨迹面板；done 定稿回答并渲染 ECharts。
 */
(() => {
    'use strict';

    const chatArea = document.getElementById('chatArea');
    const input = document.getElementById('questionInput');
    const sendBtn = document.getElementById('sendBtn');
    const newChatBtn = document.getElementById('newChatBtn');
    const welcome = document.getElementById('welcome');

    let conversationId = null;
    let busy = false;
    const charts = [];

    const TOOL_META = {
        schema_search: { icon: '🔍', label: '检索表结构' },
        execute_sql:   { icon: '🗄️', label: '执行 SQL' },
        render_chart:  { icon: '📈', label: '生成图表' },
    };

    // ---------- 工具函数 ----------

    const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    /** 极简 markdown：只处理 **加粗**，其余原样（配合 CSS 的 pre-wrap 保留换行） */
    const renderText = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');

    const scrollToBottom = () => { chatArea.scrollTop = chatArea.scrollHeight; };

    const truncate = (s, n) => (s && s.length > n) ? s.slice(0, n) + `\n…（共 ${s.length} 字，已截断）` : s;

    /** 工具入参里最值得展示的字段：SQL 原文 / 检索词，其余显示整个 JSON */
    function toolInputPreview(name, inputJson) {
        try {
            const args = JSON.parse(inputJson);
            if (name === 'execute_sql' && args.sql) return args.sql;
            if (name === 'schema_search' && args.query) return '检索词: ' + args.query;
            return JSON.stringify(args, null, 2);
        } catch { return inputJson; }
    }

    // ---------- 消息 DOM ----------

    function addUserMessage(text) {
        const div = document.createElement('div');
        div.className = 'msg msg-user';
        div.innerHTML = '<div class="bubble"></div>';
        div.querySelector('.bubble').textContent = text;
        chatArea.appendChild(div);
        scrollToBottom();
    }

    /** 助手消息卡片：轨迹面板 + 流式正文 + （完成后）图表和 SQL */
    function createAssistantCard() {
        const wrap = document.createElement('div');
        wrap.className = 'msg msg-assistant';
        wrap.innerHTML = `
            <div class="card">
                <details class="trace" open>
                    <summary>执行轨迹</summary>
                    <div class="trace-steps"></div>
                </details>
                <div class="answer streaming"></div>
            </div>`;
        chatArea.appendChild(wrap);
        scrollToBottom();

        const card = wrap.querySelector('.card');
        const trace = wrap.querySelector('.trace');
        const traceSteps = wrap.querySelector('.trace-steps');
        const answer = wrap.querySelector('.answer');
        const pendingTools = [];   // 已发出 tool 事件、还没等到 step 定稿的工具行
        let stepCount = 0;
        let liveText = '';

        return {
            /** 文本增量：先当作正在输出的正文展示 */
            onDelta(text) {
                liveText += text;
                answer.innerHTML = renderText(liveText);
                scrollToBottom();
            },

            /** 思考/规则提醒定稿：这段文本是中间过程，挪进轨迹面板，清空正文区 */
            onThought(step) {
                stepCount++;
                const row = document.createElement('div');
                row.className = 'step-row step-thought';
                const icon = step.type === 'guardrail' ? '🛡️' : '💭';
                row.textContent = icon + ' ' + step.output.trim();
                traceSteps.appendChild(row);
                liveText = '';
                answer.innerHTML = '';
                scrollToBottom();
            },

            /** 工具开始执行：插入"运行中"行 */
            onToolStart(ev) {
                const meta = TOOL_META[ev.name] || { icon: '🔧', label: ev.name };
                const row = document.createElement('details');
                row.className = 'step-row step-tool';
                row.innerHTML = `
                    <summary>${meta.icon} ${esc(meta.label)}
                        <span class="status-running">运行中…</span></summary>
                    <div class="step-io"><b>输入</b><pre></pre></div>`;
                row.querySelector('pre').textContent = truncate(toolInputPreview(ev.name, ev.input), 1500);
                traceSteps.appendChild(row);
                pendingTools.push({ name: ev.name, row });
                scrollToBottom();
            },

            /** 工具执行完毕：把对应"运行中"行定稿为成功/失败并补输出 */
            onToolStep(step) {
                stepCount++;
                const idx = pendingTools.findIndex(p => p.name === step.name);
                const pending = idx >= 0 ? pendingTools.splice(idx, 1)[0] : null;
                const row = pending ? pending.row : traceSteps.appendChild(document.createElement('details'));
                const status = row.querySelector('.status-running');
                if (status) {
                    status.textContent = step.error ? '失败' : '完成';
                    status.className = step.error ? 'status-error' : 'status-ok';
                }
                const io = row.querySelector('.step-io');
                if (io) {
                    const out = document.createElement('b');
                    out.textContent = '输出';
                    const pre = document.createElement('pre');
                    pre.textContent = truncate(step.output || '', 1500);
                    io.appendChild(out);
                    io.appendChild(pre);
                }
                scrollToBottom();
            },

            /** 最终定稿：正文替换为最终回答，渲染图表与 SQL，折叠轨迹 */
            onDone(done, durationText) {
                answer.classList.remove('streaming');
                if (!done.success) answer.classList.add('error');
                answer.innerHTML = renderText(done.answer || '(无回答)');

                if (done.chartOption) {
                    const chartDiv = document.createElement('div');
                    chartDiv.className = 'chart';
                    card.appendChild(chartDiv);
                    try {
                        const chart = echarts.init(chartDiv);
                        chart.setOption(JSON.parse(done.chartOption));
                        charts.push(chart);
                    } catch (e) {
                        chartDiv.textContent = '图表渲染失败: ' + e.message;
                    }
                }

                if (done.sql) {
                    const sqlBox = document.createElement('details');
                    sqlBox.className = 'sql-box';
                    sqlBox.innerHTML = '<summary>查看 SQL</summary><pre></pre>';
                    sqlBox.querySelector('pre').textContent = done.sql;
                    card.appendChild(sqlBox);
                }

                trace.open = false;
                trace.querySelector('summary').textContent =
                    `执行轨迹（${stepCount} 步${durationText ? ' · ' + durationText : ''}）`;
                scrollToBottom();
            },

            onError(message) {
                answer.classList.remove('streaming');
                answer.classList.add('error');
                answer.textContent = '出错了：' + message;
                trace.open = false;
                scrollToBottom();
            },
        };
    }

    // ---------- SSE 解析 ----------

    /**
     * 手写 SSE 解析（fetch + ReadableStream）：EventSource 不支持 POST。
     * 按空行分事件块，块内聚合 event: 与多行 data:。
     */
    async function streamChat(body, handlers) {
        const resp = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!resp.ok || !resp.body) {
            throw new Error('HTTP ' + resp.status);
        }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buf = '';

        const dispatch = (block) => {
            let event = 'message';
            const dataLines = [];
            for (const line of block.split('\n')) {
                if (line.startsWith('event:')) event = line.slice(6).trim();
                else if (line.startsWith('data:')) dataLines.push(line.slice(5));
            }
            if (!dataLines.length) return;
            const data = JSON.parse(dataLines.join('\n'));
            (handlers[event] || (() => {}))(data);
        };

        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += decoder.decode(value, { stream: true });
            let sep;
            while ((sep = buf.indexOf('\n\n')) >= 0) {
                const block = buf.slice(0, sep).replace(/\r/g, '');
                buf = buf.slice(sep + 2);
                if (block.trim()) dispatch(block);
            }
        }
    }

    // ---------- 主流程 ----------

    async function send(question) {
        if (busy || !question.trim()) return;
        busy = true;
        input.value = '';
        input.disabled = true;
        sendBtn.disabled = true;
        if (welcome) welcome.style.display = 'none';

        addUserMessage(question);
        const card = createAssistantCard();
        const startAt = Date.now();
        let gotDone = false;

        try {
            await streamChat({ question, conversationId }, {
                meta:  (d) => { conversationId = d.conversationId; },
                delta: (d) => card.onDelta(d.text),
                tool:  (d) => card.onToolStart(d),
                step:  (d) => d.type === 'tool_call' ? card.onToolStep(d) : card.onThought(d),
                done:  (d) => {
                    gotDone = true;
                    card.onDone(d, ((Date.now() - startAt) / 1000).toFixed(1) + 's');
                },
                error: (d) => { gotDone = true; card.onError(d.message); },
            });
            if (!gotDone) card.onError('连接中断，请重试');
        } catch (e) {
            card.onError(e.message);
        } finally {
            busy = false;
            input.disabled = false;
            sendBtn.disabled = false;
            input.focus();
        }
    }

    sendBtn.addEventListener('click', () => send(input.value));
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.isComposing) send(input.value);
    });
    newChatBtn.addEventListener('click', () => {
        conversationId = null;
        charts.forEach(c => c.dispose());
        charts.length = 0;
        chatArea.querySelectorAll('.msg').forEach(m => m.remove());
        if (welcome) welcome.style.display = '';
        input.focus();
    });
    document.querySelectorAll('.chip').forEach(chip =>
        chip.addEventListener('click', () => send(chip.textContent)));
    window.addEventListener('resize', () => charts.forEach(c => c.resize()));

    input.focus();
})();
