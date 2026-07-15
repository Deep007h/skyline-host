// Dynamic API Endpoint Base for Desktop/Mobile APK
let API_BASE = localStorage.getItem('skyline_backend_url') || '';

let activeLogsInterval = null;
let activeLogsSiteId = null;

// Initialize Lucide icons on start
document.addEventListener('DOMContentLoaded', () => {
  // Pre-fill connection input if present
  const ipInput = document.getElementById('server-ip-input');
  if (ipInput) {
    ipInput.value = API_BASE;
  }
  updateConnectionStatusText();

  fetchSystemInfo();
  fetchSites();
  lucide.createIcons();

  // Setup form submission
  document.getElementById('host-form').addEventListener('submit', handleHostSubmit);

  // Setup auto-detect folder button
  document.getElementById('btn-detect-path').addEventListener('click', autoDetectProjectPath);
});

function saveConnectionUrl() {
  const ipInput = document.getElementById('server-ip-input');
  if (!ipInput) return;
  
  let val = ipInput.value.trim();
  if (val) {
    if (!val.startsWith('http://') && !val.startsWith('https://')) {
      val = 'http://' + val;
    }
    localStorage.setItem('skyline_backend_url', val);
    API_BASE = val;
  } else {
    localStorage.removeItem('skyline_backend_url');
    API_BASE = '';
  }
  
  updateConnectionStatusText();
  fetchSystemInfo();
  fetchSites();
}

function updateConnectionStatusText() {
  const statusEl = document.getElementById('connection-status');
  if (!statusEl) return;
  
  if (API_BASE) {
    statusEl.textContent = `connected to ${API_BASE}`;
    statusEl.style.color = 'var(--success-green)';
  } else {
    statusEl.textContent = 'default relative host';
    statusEl.style.color = 'var(--text-muted)';
  }
}

// Fetch system info from API
async function fetchSystemInfo() {
  try {
    const res = await fetch(`${API_BASE}/api/system`);
    if (res.ok) {
      const data = await res.json();
      
      // Update sidebar version indicator
      const verSidebar = document.getElementById('sys-version-sidebar');
      if (verSidebar) verSidebar.textContent = data.nodeVersion;

      // Update header stat pill items
      const osPill = document.getElementById('sys-os');
      if (osPill) osPill.textContent = `${capitalize(data.platform)} (${data.arch})`;
      
      const nodePill = document.getElementById('sys-node');
      if (nodePill) nodePill.textContent = data.nodeVersion;

      const memPill = document.getElementById('sys-memory');
      if (memPill) memPill.textContent = data.memoryUsage;
      
      const binaryStatus = document.getElementById('sys-binary');
      if (binaryStatus) {
        if (data.binaryExists) {
          binaryStatus.textContent = 'Ready';
          binaryStatus.previousElementSibling.className = 'status-dot-green';
        } else {
          binaryStatus.textContent = 'Downloading';
          binaryStatus.previousElementSibling.className = 'status-dot-green'; // keep green or update styles
        }
      }
    }
  } catch (err) {
    console.error('Failed to fetch system info:', err);
  }
}

function capitalize(s) {
  if (typeof s !== 'string') return '';
  return s.charAt(0).toUpperCase() + s.slice(1);
}

// Fetch all configured sites
async function fetchSites() {
  try {
    const res = await fetch(`${API_BASE}/api/sites`);
    if (res.ok) {
      const sites = await res.json();
      renderSitesList(sites);
    }
  } catch (err) {
    console.error('Failed to fetch sites list:', err);
  }
}

// Render the 1:1 replica cards on the dashboard
function renderSitesList(sites) {
  const countBadge = document.getElementById('sites-count');
  const emptyState = document.getElementById('empty-state');
  const sitesList = document.getElementById('sites-list');

  countBadge.textContent = sites.length;

  // Clean old site cards, keeping the empty state
  const oldCards = sitesList.querySelectorAll('.site-card');
  oldCards.forEach(c => c.remove());

  if (sites.length === 0) {
    emptyState.style.display = 'flex';
    return;
  }
  
  emptyState.style.display = 'none';

  sites.forEach(site => {
    const card = document.createElement('div');
    card.className = `site-card`;
    card.id = `site-${site.id}`;

    // Status dot color mapping
    const getStatusDotColorClass = (status) => {
      if (status === 'running') return 'status-dot-green';
      if (status === 'starting') return 'status-dot-green'; // pulsing green on UI image
      return 'status-dot-green'; // fallback or adjust colors if needed
    };

    const statusBadgeClass = `status-badge ${site.status}`;
    const statusText = site.status.charAt(0).toUpperCase() + site.status.slice(1);

    const displayUrl = site.status === 'running' && site.tunnelUrl
      ? `<a href="${site.tunnelUrl}" target="_blank" class="value url-link">${site.tunnelUrl} <i data-lucide="external-link" size="12"></i></a>`
      : `<span class="value text-muted">${site.status === 'starting' ? 'Connecting to Cloudflare...' : 'Offline'}</span>`;

    card.innerHTML = `
      <div class="site-card-header">
        <div class="site-title-group">
          <div class="icon-folder-frame">
            <i data-lucide="folder"></i>
          </div>
          <div>
            <h3>${escapeHtml(site.name)}</h3>
            <span class="${statusBadgeClass}">
              <span class="${getStatusDotColorClass(site.status)}"></span>
              <span>${statusText}</span>
            </span>
          </div>
        </div>
        <div class="actions-group">
          ${site.status === 'running' && site.tunnelUrl ? `
            <a href="${site.tunnelUrl}" target="_blank" style="text-decoration:none;">
              <button class="btn-open">
                <i data-lucide="external-link" size="14"></i> Open
              </button>
            </a>
          ` : `
            <button class="btn-open" disabled style="opacity:0.5; cursor:not-allowed;">
              <i data-lucide="external-link" size="14"></i> Open
            </button>
          `}
          <button class="btn-logs" onclick="openLogsModal('${site.id}', '${escapeHtml(site.name)}')">
            <i data-lucide="file-text" size="14"></i> Logs
          </button>
          <button class="btn-more" onclick="deleteSite('${site.id}')" title="Delete Host">
            <i data-lucide="trash-2" size="14"></i>
          </button>
        </div>
      </div>

      <div class="site-card-body">
        <div class="info-row">
          <i data-lucide="folder"></i>
          <span class="label">Local Path</span>
          <span class="value" title="${escapeHtml(site.path)}">${escapeHtml(site.path)}</span>
        </div>
        <div class="info-row">
          <i data-lucide="hash"></i>
          <span class="label">Local Port</span>
          <span class="value">http://localhost:${site.port}</span>
        </div>
        <div class="info-row">
          <i data-lucide="globe"></i>
          <span class="label">Public URL</span>
          ${displayUrl}
        </div>
        <div class="info-row">
          <i data-lucide="clock"></i>
          <span class="label">Started</span>
          <span class="value">${site.status === 'running' ? 'Just now' : 'Stopped'}</span>
        </div>
      </div>

      <div class="card-actions" style="justify-content: flex-end;">
        ${site.status === 'running' ? `
          <button class="btn-stop-tunnel" onclick="toggleSite('${site.id}', 'stop')">Stop Tunnel</button>
        ` : site.status === 'stopped' || site.status === 'error' ? `
          <button class="btn-open" style="border-color:#2B7A4B; color:#2B7A4B;" onclick="toggleSite('${site.id}', 'start')">Start Tunnel</button>
        ` : `
          <button class="btn-open" disabled style="opacity:0.5; cursor:wait;">Starting...</button>
        `}
      </div>
    `;

    sitesList.appendChild(card);
  });

  // Re-initialize icons inside new cards
  lucide.createIcons();
}

// Modal management
function openNewSiteModal() {
  document.getElementById('new-site-modal').classList.add('active');
}

function closeNewSiteModal() {
  document.getElementById('new-site-modal').classList.remove('active');
  // Clear inputs
  document.getElementById('site-name').value = '';
  document.getElementById('site-path').value = '';
}

// Auto-detect project folder path helper (or open native folder picker if running on mobile App)
function autoDetectProjectPath() {
  if (typeof Capacitor !== 'undefined' && Capacitor.Plugins && Capacitor.Plugins.FolderPicker) {
    Capacitor.Plugins.FolderPicker.selectFolder()
      .then(result => {
        let uriPath = result.path;
        try {
          uriPath = decodeURIComponent(uriPath);
          
          // Parse standard path representation from uri
          if (uriPath.includes('primary:')) {
            uriPath = '/storage/emulated/0/' + uriPath.split('primary:')[1];
          } else if (uriPath.includes('tree/')) {
            const splitParts = uriPath.split('tree/');
            if (splitParts[1]) {
              uriPath = splitParts[1];
            }
          }
        } catch(e) {}
        
        document.getElementById('site-path').value = uriPath;
      })
      .catch(err => {
        console.error('Failed to pick native folder:', err);
      });
  } else {
    // Standard desktop relative path auto-detect
    const defaultPath = '/home/deep/Music/newregal-main/NEWREGAL/app/dist';
    document.getElementById('site-path').value = defaultPath;
  }
}

// Handle form submission to create site
async function handleHostSubmit(e) {
  e.preventDefault();
  const nameInput = document.getElementById('site-name');
  const pathInput = document.getElementById('site-path');
  const submitBtn = document.getElementById('submit-btn');

  const name = nameInput.value.trim();
  const sitePath = pathInput.value.trim();

  if (!name || !sitePath) return;

  // Show loading state
  submitBtn.disabled = true;
  const originalHtml = submitBtn.innerHTML;
  submitBtn.innerHTML = `<span>Connecting...</span>`;

  try {
    const res = await fetch(`${API_BASE}/api/sites`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, path: sitePath })
    });

    if (res.ok) {
      closeNewSiteModal();
      
      // Refresh sites list
      await fetchSites();
      
      // Auto-poll the status for a few seconds to watch the tunnel connect
      let pollCount = 0;
      const interval = setInterval(async () => {
        await fetchSites();
        pollCount++;
        if (pollCount >= 10) clearInterval(interval);
      }, 1000);
    } else {
      const errData = await res.json();
      alert(`Error: ${errData.error || 'Failed to host site'}`);
    }
  } catch (err) {
    alert(`Request failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
    submitBtn.innerHTML = originalHtml;
    lucide.createIcons();
  }
}

// Toggle start/stop state
async function toggleSite(id, action) {
  try {
    const res = await fetch(`${API_BASE}/api/sites/${id}/${action}`, {
      method: 'POST'
    });
    if (res.ok) {
      await fetchSites();
      
      if (action === 'start') {
        let pollCount = 0;
        const interval = setInterval(async () => {
          await fetchSites();
          pollCount++;
          if (pollCount >= 6) clearInterval(interval);
        }, 1000);
      }
    }
  } catch (err) {
    console.error(`Failed to ${action} site:`, err);
  }
}

// Delete site config
async function deleteSite(id) {
  if (!confirm('Are you sure you want to stop and delete this site host configuration?')) {
    return;
  }
  try {
    const res = await fetch(`${API_BASE}/api/sites/${id}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      await fetchSites();
    }
  } catch (err) {
    console.error('Failed to delete site:', err);
  }
}

// Log Stream Viewer Modal
function openLogsModal(siteId, siteName) {
  activeLogsSiteId = siteId;
  document.getElementById('logs-title').textContent = `${siteName} Logs`;
  document.getElementById('logs-modal').classList.add('active');
  
  fetchLogs();
  activeLogsInterval = setInterval(fetchLogs, 1500);
}

function closeLogsModal() {
  document.getElementById('logs-modal').classList.remove('active');
  if (activeLogsInterval) {
    clearInterval(activeLogsInterval);
    activeLogsInterval = null;
  }
  activeLogsSiteId = null;
}

async function fetchLogs() {
  if (!activeLogsSiteId) return;
  try {
    const res = await fetch(`${API_BASE}/api/logs/${activeLogsSiteId}`);
    if (res.ok) {
      const logs = await res.json();
      const viewport = document.getElementById('logs-viewport');
      
      const isAtBottom = viewport.scrollHeight - viewport.clientHeight <= viewport.scrollTop + 40;
      
      viewport.innerHTML = '';
      
      if (logs.length === 0) {
        viewport.innerHTML = '<div class="log-line system">Waiting for logs...</div>';
      } else {
        logs.forEach(line => {
          const div = document.createElement('div');
          div.className = 'log-line';
          
          if (line.includes('[System]')) {
            div.className = 'log-line system';
          } else if (line.includes('[Server]')) {
            div.className = 'log-line server';
          } else if (line.includes('Error') || line.includes('[Server Error]') || line.includes('[Tunnel Error]')) {
            div.className = 'log-line error';
          }
          
          div.textContent = line;
          viewport.appendChild(div);
        });
      }

      if (isAtBottom) {
        viewport.scrollTop = viewport.scrollHeight;
      }
    }
  } catch (err) {
    console.error('Failed to fetch logs:', err);
  }
}

// Utility to escape HTML
function escapeHtml(str) {
  if (!str) return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}
