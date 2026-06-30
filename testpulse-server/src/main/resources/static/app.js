/* TestPulse dashboard — shared JS
 *
 * No framework, no build step. Plain ES2017+ that any browser from the last
 * five years runs natively. Three things in here:
 *   1. API client — wrapped fetch with the X-Api-Key header
 *   2. Formatting helpers — durations, dates, status pills
 *   3. SSE subscription — live updates for runs/scenarios/steps
 *
 * The API key is read from localStorage on first load; if missing, the user
 * is prompted for one. This is fine because the dashboard is meant to be
 * accessed by humans who know the key — the library uses its own credentials
 * server-side, not whatever the dashboard has.
 */

const TP = {

    /* -------- API key handling -------- */

    apiKey() {
        let k = localStorage.getItem('testpulse.apiKey');
        if (!k) {
            k = prompt('Enter your TestPulse API key:');
            if (k) {
                localStorage.setItem('testpulse.apiKey', k.trim());
                return k.trim();
            }
        }
        return k;
    },

    clearApiKey() {
        localStorage.removeItem('testpulse.apiKey');
    },

    /* -------- API client -------- */

    async fetch(path, opts) {
        const key = this.apiKey();
        if (!key) throw new Error('No API key set');
        const res = await fetch(path, {
            ...(opts || {}),
            headers: {
                ...(opts && opts.headers || {}),
                'X-Api-Key': key,
                'Accept': 'application/json',
            },
        });
        if (res.status === 401) {
            this.clearApiKey();
            throw new Error('Unauthorised — API key cleared, refresh to re-enter');
        }
        if (!res.ok) {
            const body = await res.text().catch(() => '');
            throw new Error(`HTTP ${res.status}: ${body || res.statusText}`);
        }
        const ct = res.headers.get('Content-Type') || '';
        return ct.includes('application/json') ? res.json() : res.text();
    },

    listRuns(params) {
        const q = new URLSearchParams(params || {}).toString();
        return this.fetch(`/api/runs${q ? '?' + q : ''}`);
    },

    getRun(runId) {
        return this.fetch(`/api/runs/${runId}`);
    },

    getSteps(scenarioId) {
        return this.fetch(`/api/scenarios/${scenarioId}/steps`);
    },

    getHeatmap(days) {
        return this.fetch(`/api/analytics/heatmap?days=${days || 14}`);
    },

    getTrend(days) {
        return this.fetch(`/api/analytics/trend?days=${days || 30}`);
    },

    /* -------- SSE (Server-Sent Events) --------
     * EventSource can't set headers, so the API key goes in the query string.
     * Server's ApiHandler accepts apiKey as a query param fallback for this.
     */

    subscribe(channel, handlers) {
        const key = this.apiKey();
        if (!key) return null;
        const path = channel === 'global'
            ? `/api/stream?apiKey=${encodeURIComponent(key)}`
            : `/api/runs/${channel}/stream?apiKey=${encodeURIComponent(key)}`;
        const es = new EventSource(path);
        Object.keys(handlers || {}).forEach(evt => {
            es.addEventListener(evt, e => {
                try { handlers[evt](JSON.parse(e.data)); }
                catch (err) { console.warn('SSE handler error:', err); }
            });
        });
        es.onerror = e => console.warn('SSE error:', e);
        return es;
    },

    /* -------- Formatting -------- */

    duration(ms) {
        if (!ms || ms < 0) return '—';
        if (ms < 1000)   return Math.round(ms) + 'ms';
        if (ms < 60000)  return (ms / 1000).toFixed(1) + 's';
        const m = Math.floor(ms / 60000);
        const s = Math.round((ms % 60000) / 1000);
        return `${m}m ${s}s`;
    },

    timeAgo(iso) {
        if (!iso) return '—';
        const then = new Date(iso).getTime();
        const now = Date.now();
        const diff = Math.max(0, now - then);
        if (diff < 60000)    return Math.round(diff / 1000) + 's ago';
        if (diff < 3600000)  return Math.round(diff / 60000) + 'm ago';
        if (diff < 86400000) return Math.round(diff / 3600000) + 'h ago';
        return Math.round(diff / 86400000) + 'd ago';
    },

    statusPill(status) {
        const s = (status || '').toLowerCase();
        const map = {
            passed:  { cls: 'pass',  label: 'Passed' },
            failed:  { cls: 'fail',  label: 'Failed' },
            running: { cls: 'run',   label: 'Running', live: true },
            empty:   { cls: 'empty', label: 'Empty' },
            skipped: { cls: 'skipped', label: 'Skipped' },
            pending: { cls: 'skipped', label: 'Pending' },
        };
        const m = map[s] || { cls: 'empty', label: status || 'Unknown' };
        const live = m.live ? '<span class="live-dot"></span>' : '';
        return `<span class="pill ${m.cls}">${live}${m.label}</span>`;
    },

    passRate(run) {
        const total = run.scenariosTotal || 0;
        const passed = run.scenariosPassed || 0;
        return total === 0 ? 0 : (passed / total);
    },

    escape(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    },

    qsParam(name) {
        return new URLSearchParams(window.location.search).get(name);
    },

    /* -------- DOM helpers -------- */

    el(tag, attrs, ...children) {
        const e = document.createElement(tag);
        Object.entries(attrs || {}).forEach(([k, v]) => {
            if (k === 'class') e.className = v;
            else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
            else e.setAttribute(k, v);
        });
        children.flat().forEach(c => {
            if (c == null) return;
            e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
        });
        return e;
    },

    /* -------- Header --------
     * Rendered into <div id="header"></div> on every page. Active nav item is
     * marked by the page's <body data-page="..."> attribute.
     */

    renderHeader() {
        const host = document.getElementById('header');
        if (!host) return;
        const page = document.body.getAttribute('data-page') || 'runs';
        host.innerHTML = `
            <header class="header">
                <a href="/" class="header-brand">
                    <span class="dot"></span>
                    TestPulse
                </a>
                <nav class="header-nav">
                    <a href="/" class="${page === 'runs' ? 'active' : ''}">Runs</a>
                    <a href="/analytics.html" class="${page === 'analytics' ? 'active' : ''}">Analytics</a>
                </nav>
                <span class="header-meta" id="header-meta"></span>
            </header>
        `;
    },
};

document.addEventListener('DOMContentLoaded', () => TP.renderHeader());
