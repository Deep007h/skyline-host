const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const https = require('https');
const { spawn } = require('child_process');
const net = require('net');

const PORT = process.env.PORT || 8080;
const isPkg = typeof process.pkg !== 'undefined';
const RUNTIME_DIR = isPkg ? process.cwd() : __dirname;

const BIN_DIR = path.join(RUNTIME_DIR, 'bin');
const SITES_FILE = path.join(RUNTIME_DIR, 'sites.json');
const BIN_PATH = path.join(BIN_DIR, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared');

const app = express();
app.use(cors());
app.use(express.json());

// Disable caching for development
app.use((req, res, next) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
  next();
});

app.use(express.static(path.join(__dirname, 'public')));

// In-memory runtime state
let sites = [];
const runningServers = new Map();
const runningTunnels = new Map();
const logsCache = new Map(); // siteId -> array of log strings

// Ensure directories exist
if (!fs.existsSync(BIN_DIR)) {
  fs.mkdirSync(BIN_DIR, { recursive: true });
}

// Load sites from config file
if (fs.existsSync(SITES_FILE)) {
  try {
    sites = JSON.parse(fs.readFileSync(SITES_FILE, 'utf8'));
    // Reset runtime statuses on load
    sites.forEach(s => {
      s.status = 'stopped';
      s.tunnelUrl = '';
      logsCache.set(s.id, [`[System] Site configuration loaded: ${s.name}`]);
    });
  } catch (err) {
    console.error('Failed to parse sites.json:', err);
    sites = [];
  }
}

const saveSitesConfig = () => {
  try {
    const configData = sites.map(s => ({
      id: s.id,
      name: s.name,
      path: s.path,
      port: s.port
    }));
    fs.writeFileSync(SITES_FILE, JSON.stringify(configData, null, 2), 'utf8');
  } catch (err) {
    console.error('Failed to save sites.json:', err);
  }
};

// Find a free port dynamically
const findFreePort = () => {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
    server.on('error', reject);
  });
};

// Redirection-aware downloader
const downloadFile = (url, dest) => {
  return new Promise((resolve, reject) => {
    const request = https.get(url, (response) => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        // Follow redirect
        return downloadFile(response.headers.location, dest).then(resolve).catch(reject);
      }
      if (response.statusCode !== 200) {
        return reject(new Error(`Server returned status code ${response.statusCode}`));
      }

      const file = fs.createWriteStream(dest);
      response.pipe(file);
      file.on('finish', () => {
        file.close(() => resolve());
      });
    });
    request.on('error', (err) => {
      fs.unlink(dest, () => reject(err));
    });
  });
};

// Map current platform and architecture to cloudflared download URL
const getBinaryUrl = () => {
  const platform = process.platform;
  const arch = process.arch;

  if (platform === 'win32') {
    return 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe';
  } else if (platform === 'darwin') {
    return 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-amd64';
  } else {
    // Default to Linux
    return arch === 'arm64'
      ? 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64'
      : 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64';
  }
};

// Check and download cloudflared if missing
const ensureBinary = async () => {
  if (fs.existsSync(BIN_PATH)) {
    return true;
  }
  console.log(`Binary not found at ${BIN_PATH}. Starting automatic download...`);
  const downloadUrl = getBinaryUrl();
  console.log(`Downloading cloudflared from ${downloadUrl}`);
  await downloadFile(downloadUrl, BIN_PATH);
  
  if (process.platform !== 'win32') {
    fs.chmodSync(BIN_PATH, 0o755); // Grant execution permission
  }
  console.log('cloudflared downloaded and configured successfully.');
  return true;
};

const addLog = (siteId, message) => {
  const time = new Date().toLocaleTimeString();
  const formatted = `[${time}] ${message}`;
  let logs = logsCache.get(siteId) || [];
  logs.push(formatted);
  if (logs.length > 200) {
    logs.shift(); // keep cache size small
  }
  logsCache.set(siteId, logs);
};

// Stop a hosted site server and its tunnel
const stopSite = (id) => {
  const site = sites.find(s => s.id === id);
  if (!site) return;

  addLog(id, '[System] Stopping site server and tunnel...');

  // Stop static server
  const server = runningServers.get(id);
  if (server) {
    server.close();
    runningServers.delete(id);
    addLog(id, '[Server] Local Express server stopped.');
  }

  // Kill tunnel
  const tunnelProc = runningTunnels.get(id);
  if (tunnelProc) {
    tunnelProc.kill('SIGTERM');
    runningTunnels.delete(id);
    addLog(id, '[Tunnel] Cloudflare Tunnel connection killed.');
  }

  site.status = 'stopped';
  site.tunnelUrl = '';
};

// Start a hosted site server and its tunnel
const startSite = async (id) => {
  const site = sites.find(s => s.id === id);
  if (!site) return;

  if (site.status === 'running') {
    return;
  }

  site.status = 'starting';
  site.tunnelUrl = '';
  addLog(id, `[System] Starting site "${site.name}"...`);

  try {
    // 1. Verify directory path exists
    if (!fs.existsSync(site.path)) {
      throw new Error(`Directory path does not exist: ${site.path}`);
    }
    const stat = fs.statSync(site.path);
    if (!stat.isDirectory()) {
      throw new Error(`Path is not a folder: ${site.path}`);
    }

    // 2. Start dynamic Express static server
    const siteApp = express();
    siteApp.use(cors());
    siteApp.use(express.static(site.path));
    
    // Fallback support for single-page apps (index.html fallback)
    siteApp.get('*', (req, res) => {
      const indexPath = path.join(site.path, 'index.html');
      if (fs.existsSync(indexPath)) {
        res.sendFile(indexPath);
      } else {
        res.status(404).send('Not Found');
      }
    });

    const serverListener = siteApp.listen(site.port, () => {
      addLog(id, `[Server] Serving folder locally on http://localhost:${site.port}`);
    });

    serverListener.on('error', (err) => {
      addLog(id, `[Server Error] ${err.message}`);
      stopSite(id);
    });

    runningServers.set(id, serverListener);

    // 3. Ensure cloudflared is downloaded
    await ensureBinary();

    // 4. Spawn Cloudflare Tunnel
    addLog(id, '[Tunnel] Launching Cloudflare Tunnel...');
    const tunnelProc = spawn(BIN_PATH, ['tunnel', '--url', `http://127.0.0.1:${site.port}`]);
    runningTunnels.set(id, tunnelProc);

    // Parse TryCloudflare link from logs
    let urlFound = false;

    const handleData = (data) => {
      const output = data.toString();
      // Write raw logs to site log cache
      output.split(/\r?\n/).forEach(line => {
        if (line.trim()) {
          addLog(id, `[Tunnel Log] ${line.trim()}`);
        }
      });

      if (!urlFound) {
        const match = output.match(/https:\/\/[a-z0-9\-]+\.trycloudflare\.com/);
        if (match) {
          urlFound = true;
          site.tunnelUrl = match[0];
          site.status = 'running';
          addLog(id, `[Tunnel Success] Public URL generated: ${site.tunnelUrl}`);
        }
      }
    };

    tunnelProc.stdout.on('data', handleData);
    tunnelProc.stderr.on('data', handleData);

    tunnelProc.on('close', (code) => {
      addLog(id, `[Tunnel] cloudflared process exited with code ${code}`);
      if (site.status === 'starting' || site.status === 'running') {
        stopSite(id);
        site.status = 'error';
        site.error = `Tunnel process exited with code ${code}`;
      }
    });

    tunnelProc.on('error', (err) => {
      addLog(id, `[Tunnel Error] Failed to spawn cloudflared: ${err.message}`);
      stopSite(id);
      site.status = 'error';
      site.error = err.message;
    });

  } catch (err) {
    console.error(`Failed to start site ${id}:`, err);
    addLog(id, `[System Error] ${err.message}`);
    stopSite(id);
    site.status = 'error';
    site.error = err.message;
  }
};

// API Endpoints
app.get('/api/sites', (req, res) => {
  res.json(sites);
});

app.post('/api/sites', async (req, res) => {
  const { name, path: sitePath } = req.body;
  if (!name || !sitePath) {
    return res.status(400).json({ error: 'Name and local folder path are required.' });
  }

  // Validate directory existence
  if (!fs.existsSync(sitePath)) {
    return res.status(400).json({ error: 'Local folder path does not exist.' });
  }
  const stat = fs.statSync(sitePath);
  if (!stat.isDirectory()) {
    return res.status(400).json({ error: 'The specified path is not a folder.' });
  }

  try {
    const freePort = await findFreePort();
    const newSite = {
      id: Date.now().toString(),
      name,
      path: path.resolve(sitePath),
      port: freePort,
      status: 'stopped',
      tunnelUrl: ''
    };

    sites.push(newSite);
    logsCache.set(newSite.id, [`[System] Site created: ${name}`]);
    saveSitesConfig();

    // Auto-start site after creation
    await startSite(newSite.id);
    res.json(newSite);
  } catch (err) {
    res.status(500).json({ error: `Failed to create site: ${err.message}` });
  }
});

app.post('/api/sites/:id/start', async (req, res) => {
  const { id } = req.params;
  const site = sites.find(s => s.id === id);
  if (!site) {
    return res.status(404).json({ error: 'Site not found.' });
  }
  await startSite(id);
  res.json(site);
});

app.post('/api/sites/:id/stop', (req, res) => {
  const { id } = req.params;
  const site = sites.find(s => s.id === id);
  if (!site) {
    return res.status(404).json({ error: 'Site not found.' });
  }
  stopSite(id);
  res.json(site);
});

app.delete('/api/sites/:id', (req, res) => {
  const { id } = req.params;
  const index = sites.findIndex(s => s.id === id);
  if (index === -1) {
    return res.status(404).json({ error: 'Site not found.' });
  }
  stopSite(id);
  sites.splice(index, 1);
  logsCache.delete(id);
  saveSitesConfig();
  res.json({ success: true });
});

app.get('/api/logs/:id', (req, res) => {
  const { id } = req.params;
  const logs = logsCache.get(id) || [];
  res.json(logs);
});

app.get('/api/system', (req, res) => {
  res.json({
    platform: process.platform,
    arch: process.arch,
    nodeVersion: process.version,
    memoryUsage: Math.round(process.memoryUsage().heapUsed / 1024 / 1024) + ' MB',
    binaryExists: fs.existsSync(BIN_PATH)
  });
});

// Trigger download in background on start
ensureBinary().catch(err => console.error('Background cloudflared download failed:', err));

app.listen(PORT, () => {
  console.log(`========================================`);
  console.log(`   Skyline Host Server Active           `);
  console.log(`   Manage dashboard on http://localhost:${PORT} `);
  console.log(`========================================`);
});
