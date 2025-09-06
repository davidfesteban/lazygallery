// server.js
const express = require('express');
const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const multer = require('multer');
const mime = require('mime-types');
const archiver = require('archiver');

class Config {
  static PORT = process.env.PORT || 8080;
  static ROOT = __dirname;
  static UPLOAD_DIR = path.join(__dirname, 'uploads');
  static PUBLIC_DIR = path.join(__dirname, 'public');

  static ensureDirs() {
    if (!fs.existsSync(this.UPLOAD_DIR)) fs.mkdirSync(this.UPLOAD_DIR, { recursive: true });
  }
}

class MulterFactory {
  static build(uploadDir) {
    const storage = multer.diskStorage({
      destination: (_req, _file, cb) => cb(null, uploadDir),
      filename: (_req, file, cb) => {
        const safeBase = file.originalname.replace(/[^\w.\-\s]/g, '_');
        const ts = new Date().toISOString().replace(/[:.]/g, '-');
        const ext = path.extname(safeBase);
        const base = path.basename(safeBase, ext);
        cb(null, `${base}__${ts}${ext}`.replace(/\s+/g, '_'));
      }
    });
    return multer({ storage });
  }
}

class MediaService {
  constructor(uploadDir) {
    this.uploadDir = uploadDir;
  }

  async list(offset = 0, limit = 50) {
    const entries = await fsp.readdir(this.uploadDir, { withFileTypes: true });
    const files = [];

    for (const e of entries) {
      if (!e.isFile()) continue;
      const filePath = path.join(this.uploadDir, e.name);
      const stat = await fsp.stat(filePath);
      const mtype = mime.lookup(e.name) || 'application/octet-stream';
      let type = 'other';
      if (mtype.startsWith('image/')) type = 'image';
      else if (mtype.startsWith('video/')) type = 'video';

      files.push({
        name: e.name,
        url: `/uploads/${encodeURIComponent(e.name)}`,
        type,
        mime: mtype,
        size: stat.size,
        mtime: stat.mtimeMs
      });
    }

    // Newest first
    files.sort((a, b) => b.mtime - a.mtime);

    const total = files.length;
    const slice = files.slice(offset, offset + limit);
    const nextOffset = offset + slice.length < total ? offset + slice.length : null;

    return { items: slice, nextOffset, total };
  }

  streamZipTo(res) {
    const archive = archiver('zip', { zlib: { level: 9 } });
    const zipName = `wedding-media-${Date.now()}.zip`;

    res.setHeader('Content-Type', 'application/zip');
    res.setHeader('Content-Disposition', `attachment; filename="${zipName}"`);

    archive.on('error', err => {
      // If archiver errors after headers sent, end the stream
      try { res.status(500).end(err.message); } catch {}
    });

    archive.pipe(res);
    archive.directory(this.uploadDir, false);
    archive.finalize();
  }
}

class MediaController {
  constructor({ app, mediaService, upload }) {
    this.app = app;
    this.mediaService = mediaService;
    this.upload = upload;
  }

  registerRoutes() {
    // Static
    this.app.use(express.static(Config.PUBLIC_DIR));
    this.app.use('/uploads', express.static(Config.UPLOAD_DIR, { maxAge: '1h' }));

    // List media with pagination
    this.app.get('/api/media', this.handleList.bind(this));

    // Upload multiple files
    this.app.post('/api/upload', this.upload.array('files', 100), this.handleUpload.bind(this));

    // Download all as ZIP
    this.app.get('/api/download-all', this.handleDownloadAll.bind(this));

    // Health
    this.app.get('/api/health', (_req, res) => res.json({ ok: true }));
  }

  async handleList(req, res) {
    try {
      const offset = Math.max(0, parseInt(req.query.offset ?? '0', 10) || 0);
      const limitRaw = parseInt(req.query.limit ?? '50', 10) || 50;
      const limit = Math.min(200, Math.max(1, limitRaw)); // cap at 200

      const data = await this.mediaService.list(offset, limit);
      res.json(data);
    } catch (err) {
      console.error('handleList error', err);
      res.status(500).json({ error: 'Failed to list media.' });
    }
  }

  handleUpload(req, res) {
    res.json({ uploaded: req.files?.map(f => f.filename) ?? [] });
  }

  handleDownloadAll(_req, res) {
    this.mediaService.streamZipTo(res);
  }
}

class AppServer {
  constructor() {
    Config.ensureDirs();
    this.app = express();
    const upload = MulterFactory.build(Config.UPLOAD_DIR);
    const mediaService = new MediaService(Config.UPLOAD_DIR);
    const mediaController = new MediaController({ app: this.app, mediaService, upload });
    mediaController.registerRoutes();
  }

  start() {
    this.server = this.app.listen(Config.PORT, () => {
      console.log(`Server running on http://0.0.0.0:${Config.PORT}`);
    });
    return this.server;
  }
}

// Bootstrap
new AppServer().start();