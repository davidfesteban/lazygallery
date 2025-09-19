// server.js
const express = require('express');
const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const multer = require('multer');
const mime = require('mime-types');
const archiver = require('archiver');
const crypto = require('crypto');
const sharp = require('sharp');

class Config {
  static PORT = process.env.PORT || 8080;
  static ROOT = __dirname;
  static UPLOAD_DIR = path.join(__dirname, 'uploads');
  static PUBLIC_DIR = path.join(__dirname, 'public');
  static THUMB_DIR = path.join(__dirname, 'thumbnails');
  static THUMB_ROUTE = '/thumbnails';
  static THUMB_WIDTH = parseInt(process.env.THUMB_WIDTH || '512', 10);
  static THUMB_HEIGHT = parseInt(process.env.THUMB_HEIGHT || '512', 10);
  static THUMB_QUALITY = parseInt(process.env.THUMB_QUALITY || '75', 10);
  static CACHE_DIR = path.join(__dirname, 'cache');

  static ensureDirs() {
    if (!fs.existsSync(this.UPLOAD_DIR)) fs.mkdirSync(this.UPLOAD_DIR, { recursive: true });
    if (!fs.existsSync(this.THUMB_DIR)) fs.mkdirSync(this.THUMB_DIR, { recursive: true });
    if (!fs.existsSync(this.CACHE_DIR)) fs.mkdirSync(this.CACHE_DIR, { recursive: true });
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
  constructor(uploadDir, thumbDir, cacheDir) {
    this.uploadDir = uploadDir;
    this.thumbDir = thumbDir;
    this.cacheDir = cacheDir;
    this.thumbnailQueue = new Map();
    this.archiveJobs = new Map();
    this.inventoryPromise = null;
    this.inventoryCache = null;
    this.inventoryCacheGeneration = -1;
    this.inventoryGeneration = 0;
    this.invalidateTimer = null;
    this.watchUploads();
  }

  async list(offset = 0, limit = 50) {
    const inventory = await this.getInventory();
    const total = inventory.length;
    const sliceMeta = inventory.slice(offset, offset + limit);
    const nextOffset = offset + sliceMeta.length < total ? offset + sliceMeta.length : null;

    const items = await MediaService.mapConcurrent(sliceMeta, 4, async (meta) => {
      const item = {
        name: meta.name,
        url: `/uploads/${encodeURIComponent(meta.name)}`,
        type: meta.type,
        mime: meta.mime,
        size: meta.size,
        mtime: meta.mtime,
        previewUrl: null
      };

      if (meta.type === 'image') {
        try {
          const thumbName = await this.ensureThumbnail(meta.name);
          if (thumbName) item.previewUrl = `${Config.THUMB_ROUTE}/${encodeURIComponent(thumbName)}`;
        } catch (err) {
          console.warn('Thumbnail generation failed', meta.name, err.message);
        }
      }

      return item;
    });

    return { items, nextOffset, total };
  }

  // Map helper with bounded concurrency to avoid hammering the FS.
  static async mapConcurrent(items, limit, mapper) {
    const results = new Array(items.length);
    let index = 0;
    const workerCount = Math.max(1, Math.min(limit, items.length));
    const workers = Array.from({ length: workerCount }, async () => {
      while (true) {
        const current = index++;
        if (current >= items.length) break;
        results[current] = await mapper(items[current], current);
      }
    });
    await Promise.all(workers);
    return results;
  }

  async getInventory() {
    if (this.inventoryCache && !this.inventoryPromise && this.inventoryCacheGeneration === this.inventoryGeneration) {
      return this.inventoryCache;
    }

    if (!this.inventoryPromise) {
      const generation = this.inventoryGeneration;
      this.inventoryPromise = this.buildInventory()
        .then((items) => {
          if (this.inventoryGeneration === generation) {
            this.inventoryCache = items;
            this.inventoryCacheGeneration = generation;
          }
          return items;
        })
        .catch((err) => {
          this.inventoryPromise = null;
          throw err;
        })
        .finally(() => {
          if (this.inventoryGeneration === generation) {
            this.inventoryPromise = null;
          }
        });
    }

    return this.inventoryPromise;
  }

  async buildInventory() {
    const entries = await fsp.readdir(this.uploadDir, { withFileTypes: true });
    const files = entries.filter(e => e.isFile());

    const records = await MediaService.mapConcurrent(files, 32, async (entry) => {
      const filePath = path.join(this.uploadDir, entry.name);
      try {
        const stat = await fsp.stat(filePath);
        const mimeType = mime.lookup(entry.name) || 'application/octet-stream';
        let type = 'other';
        if (mimeType.startsWith('image/')) type = 'image';
        else if (mimeType.startsWith('video/')) type = 'video';

        return {
          name: entry.name,
          size: stat.size,
          mtime: stat.mtimeMs,
          mime: mimeType,
          type
        };
      } catch (err) {
        console.warn('stat failed during inventory', entry.name, err.message);
        return null;
      }
    });

    return records
      .filter(Boolean)
      .sort((a, b) => {
        if (b.mtime !== a.mtime) return b.mtime - a.mtime;
        return a.name.localeCompare(b.name);
      });
  }

  invalidateInventory() {
    // Debounce repeated fs.watch events; next request will rebuild cache.
    if (this.invalidateTimer) return;
    this.invalidateTimer = setTimeout(() => {
      this.inventoryGeneration += 1;
      this.inventoryCache = null;
      this.inventoryCacheGeneration = -1;
      this.inventoryPromise = null;
      this.invalidateTimer = null;
    }, 25);
    this.invalidateTimer.unref?.();
  }

  watchUploads() {
    try {
      this.uploadWatcher?.close?.();
      this.uploadWatcher = fs.watch(this.uploadDir, { persistent: false }, () => {
        this.invalidateInventory();
      });
      this.uploadWatcher.on('error', (err) => {
        console.warn('upload watcher error', err.message);
      });
    } catch (err) {
      console.warn('watchUploads warning', err.message);
    }
  }

  async ensureThumbnail(filename) {
    if (this.thumbnailQueue.has(filename)) {
      return this.thumbnailQueue.get(filename);
    }

    const job = (async () => {
      const sourcePath = path.join(this.uploadDir, filename);
      const thumbName = `${filename}.thumb.jpg`;
      const thumbPath = path.join(this.thumbDir, thumbName);

      try {
        await fsp.access(thumbPath);
        return thumbName;
      } catch {}

      try {
        await sharp(sourcePath)
          .rotate()
          .resize({
            width: Config.THUMB_WIDTH,
            height: Config.THUMB_HEIGHT,
            fit: 'inside',
            withoutEnlargement: true
          })
          .jpeg({ quality: Config.THUMB_QUALITY, mozjpeg: true })
          .toFile(thumbPath);
        return thumbName;
      } catch (err) {
        console.warn('Failed to create thumbnail', filename, err.message);
        return null;
      }
    })();

    this.thumbnailQueue.set(filename, job);
    try {
      return await job;
    } finally {
      this.thumbnailQueue.delete(filename);
    }
  }

  streamZipTo(res) {
    this.pipeCachedArchive(res).catch(err => {
      console.error('streamZipTo error', err);
      if (!res.headersSent) res.status(500).json({ error: 'Failed to prepare archive.' });
      else res.end();
    });
  }

  async pipeCachedArchive(res) {
    const inventory = await this.snapshotInventory();
    const signature = this.computeInventorySignature(inventory);
    const archivePath = await this.ensureArchive(signature, inventory);
    const stat = await fsp.stat(archivePath);

    const fileName = `wedding-media-${signature.slice(0, 8)}.zip`;
    const etag = `"${signature}"`;
    if (res.req.headers['if-none-match'] === etag) {
      res.status(304).setHeader('ETag', etag);
      res.setHeader('Cache-Control', 'public, max-age=0, must-revalidate');
      return res.end();
    }

    res.setHeader('Content-Type', 'application/zip');
    res.setHeader('Content-Length', stat.size);
    res.setHeader('Content-Disposition', `attachment; filename="${fileName}"`);
    res.setHeader('ETag', etag);
    res.setHeader('Last-Modified', new Date(stat.mtimeMs).toUTCString());
    res.setHeader('Cache-Control', 'public, max-age=0, must-revalidate');

    const stream = fs.createReadStream(archivePath);
    stream.on('error', err => {
      console.error('zip read error', err);
      if (!res.headersSent) {
        res.status(500).json({ error: 'Failed to stream archive.' });
      } else {
        res.end();
      }
    });
    stream.pipe(res);
  }

  async snapshotInventory() {
    const base = await this.getInventory();
    return [...base]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(item => ({ name: item.name, size: item.size, mtime: item.mtime }));
  }

  computeInventorySignature(inventory) {
    const hash = crypto.createHash('sha1');
    for (const file of inventory) {
      hash.update(file.name);
      hash.update('|');
      hash.update(String(file.size));
      hash.update('|');
      hash.update(String(Math.round(file.mtime)));
      hash.update('\n');
    }
    return hash.digest('hex');
  }

  async ensureArchive(signature, inventory) {
    const archiveName = `media-${signature}.zip`;
    const archivePath = path.join(this.cacheDir, archiveName);

    try {
      await fsp.access(archivePath);
      return archivePath;
    } catch {}

    if (!this.archiveJobs.has(signature)) {
      const job = this.buildArchive(archivePath, inventory)
        .then(async () => {
          await this.pruneOldArchives(signature);
          return archivePath;
        })
        .catch(err => {
          // Ensure cache entry removed on failure
          try { fs.rmSync(archivePath, { force: true }); } catch {}
          throw err;
        })
        .finally(() => {
          this.archiveJobs.delete(signature);
        });
      this.archiveJobs.set(signature, job);
    }
    return this.archiveJobs.get(signature);
  }

  async buildArchive(targetPath, inventory) {
    await fsp.mkdir(path.dirname(targetPath), { recursive: true });
    const tmpPath = `${targetPath}.tmp-${process.pid}-${Date.now()}`;

    await new Promise((resolve, reject) => {
      const output = fs.createWriteStream(tmpPath);
      const archive = archiver('zip', { zlib: { level: 9 } });

      output.on('close', resolve);
      output.on('error', reject);
      archive.on('error', reject);

      archive.pipe(output);

      for (const file of inventory) {
        archive.file(path.join(this.uploadDir, file.name), { name: file.name });
      }

      archive.finalize().catch(reject);
    });

    await fsp.rename(tmpPath, targetPath);
  }

  async pruneOldArchives(activeSignature) {
    try {
      const entries = await fsp.readdir(this.cacheDir, { withFileTypes: true });
      for (const entry of entries) {
        if (!entry.isFile()) continue;
        if (!entry.name.endsWith('.zip')) continue;
        if (entry.name.includes(activeSignature)) continue;
        const full = path.join(this.cacheDir, entry.name);
        try { await fsp.unlink(full); } catch {}
      }
    } catch (err) {
      console.warn('pruneOldArchives warning', err.message);
    }
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
    const isProd = process.env.NODE_ENV === 'production';
    this.app.use(express.static(Config.PUBLIC_DIR, {
      maxAge: isProd ? '1d' : 0,
      immutable: isProd,
      setHeaders: (res, filePath) => {
        if (filePath.endsWith('index.html')) {
          res.setHeader('Cache-Control', 'no-cache');
        }
      }
    }));

    const mediaStaticOpts = {
      fallthrough: true,
      immutable: true,
      maxAge: '7d'
    };
    this.app.use('/uploads', express.static(Config.UPLOAD_DIR, mediaStaticOpts));
    this.app.use(Config.THUMB_ROUTE, express.static(Config.THUMB_DIR, mediaStaticOpts));

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
    const uploaded = req.files?.map(f => f.filename) ?? [];
    res.json({ uploaded });
    if (uploaded.length) {
      this.mediaService.invalidateInventory();
    }
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
    const mediaService = new MediaService(Config.UPLOAD_DIR, Config.THUMB_DIR, Config.CACHE_DIR);
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
