class GalleryApp {
  constructor() {
    // Elements
    this.galleryEl = document.getElementById('gallery');
    this.fabBtn = document.getElementById('fabBtn');
    this.fabMenu = document.getElementById('fabMenu');
    this.downloadAllBtn = document.getElementById('downloadAllBtn');
    this.uploadBtn = document.getElementById('uploadBtn');
    this.fileInput = document.getElementById('fileInput');

    this.lightbox = document.getElementById('lightbox');
    this.lightboxBody = document.getElementById('lightboxBody');
    this.lightboxClose = document.getElementById('lightboxClose');

    this.progressModal = document.getElementById('progressModal');
    this.progressBar = document.getElementById('progressBar');
    this.progressText = document.getElementById('progressText');
    this.etaText = document.getElementById('etaText');
    this.speedText = document.getElementById('speedText');

    this.sentinel = document.getElementById('sentinel');

    // Wake Lock handle
    this.wakeLock = null;

    // Paging state
    this.paging = {
      nextOffset: 0,
      loading: false,
      done: false,
      pageSize: 50
    };

    // Observers
    this.infiniteIO = new IntersectionObserver(async (entries) => {
      const entry = entries[0];
      if (entry.isIntersecting) await this.loadNextPage();
    });
    this.infiniteIO.observe(this.sentinel);

    // Lazy load media; prime videos to paint first frame
    this.lazyIO = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        const el = e.target;
        const src = el.dataset.src;
        if (src) {
          if (el.tagName === 'VIDEO') {
            el.src = src;              // set actual source
            el.preload = 'metadata';   // begin fetching metadata
            this.primeVideoOnce(el);   // render first frame
            el.load();
          } else {
            el.src = src;              // images: just set src
          }
          delete el.dataset.src;
        }
        this.lazyIO.unobserve(el);
      }
    }, { rootMargin: '200px' });

    // Wire events
    this.wireMenu();
    this.wireLightbox();
    this.wireUpload();

    // Keep screen awake while the page is visible
    this.acquireWakeLock();
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') this.acquireWakeLock();
    });
    window.addEventListener('pagehide', () => this.releaseWakeLock());

    // First load
    this.loadGallery().catch(console.error);
  }

  // ===== Wake Lock =====
  async acquireWakeLock() {
    try {
      if ('wakeLock' in navigator && !this.wakeLock) {
        this.wakeLock = await navigator.wakeLock.request('screen');
        this.wakeLock.addEventListener?.('release', () => { this.wakeLock = null; });
      }
    } catch (_) {
      // ignore if unsupported/denied
    }
  }
  releaseWakeLock() {
    try { this.wakeLock?.release(); } catch {} finally { this.wakeLock = null; }
  }

  // ===== Menu / FAB =====
  toggleMenu(force) {
    const expanded = force ?? this.fabMenu.getAttribute('aria-hidden') === 'true';
    this.fabMenu.setAttribute('aria-hidden', expanded ? 'false' : 'true');
    this.fabBtn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
  }
  wireMenu() {
    this.fabBtn.addEventListener('click', () => this.toggleMenu());
    document.addEventListener('click', (e) => {
      if (!document.querySelector('.fab').contains(e.target)) {
        this.fabMenu.setAttribute('aria-hidden', 'true');
        this.fabBtn.setAttribute('aria-expanded', 'false');
      }
    });
    this.downloadAllBtn.addEventListener('click', () => {
      const a = document.createElement('a');
      a.href = '/api/download-all';
      a.download = '';
      document.body.appendChild(a);
      a.click();
      a.remove();
      this.fabMenu.setAttribute('aria-hidden', 'true');
    });
    this.uploadBtn.addEventListener('click', () => {
      this.fileInput.click();
      this.fabMenu.setAttribute('aria-hidden', 'true');
    });
  }

  // ===== Lightbox =====
  openLightbox(kind, url) {
    this.lightboxBody.innerHTML = '';
    if (kind === 'image') {
      const img = document.createElement('img');
      img.src = url;
      img.alt = 'Image preview';
      this.lightboxBody.appendChild(img);
    } else if (kind === 'video') {
      const vid = document.createElement('video');
      vid.src = url;
      vid.controls = true;
      vid.autoplay = true;   // will play in modal
      vid.playsInline = true;
      this.lightboxBody.appendChild(vid);
    } else {
      const p = document.createElement('p');
      p.textContent = 'Unsupported file type.';
      this.lightboxBody.appendChild(p);
    }
    this.lightbox.setAttribute('aria-hidden', 'false');
  }
  closeLightbox() {
    // Pause and fully unload any video to stop background playback/buffering
    const v = this.lightboxBody.querySelector('video');
    if (v) this.unloadVideo(v);
    this.lightboxBody.innerHTML = '';
    this.lightbox.setAttribute('aria-hidden','true');
  }
  wireLightbox() {
    this.lightboxClose.addEventListener('click', () => this.closeLightbox());
    this.lightbox.addEventListener('click', (e) => {
      if (e.target.classList.contains('modal-backdrop')) this.closeLightbox();
    });
  }

  // ===== Upload (parallel, full quality) =====
  wireUpload() {
    this.fileInput.addEventListener('change', async () => {
      if (!this.fileInput.files || this.fileInput.files.length === 0) return;
      await this.uploadFiles(this.fileInput.files);
      this.fileInput.value = ''; // reset
      await this.loadGallery();  // refresh list from start
    });
  }

  async uploadFiles(fileList) {
    const files = Array.from(fileList);
    const CONCURRENCY = 5; // tune 3–6 for WAN

    // Aggregate progress
    let uploadedBytes = 0;
    const totalBytes = files.reduce((a, f) => a + f.size, 0);

    this.progressBar.value = 0;
    this.progressText.textContent = '0%';
    this.etaText.textContent = 'ETA: --:--';
    this.speedText.textContent = 'Speed: -- MB/s';
    this.progressModal.setAttribute('aria-hidden','false');

    const startedAt = Date.now();
    await this.acquireWakeLock();

    const uploadOne = (file) => new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const form = new FormData();
      form.append('files', file); // server expects 'files' field

      xhr.open('POST', '/api/upload');

      let lastReported = 0;
      xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        const delta = e.loaded - lastReported;
        lastReported = e.loaded;
        uploadedBytes += delta;

        const pct = Math.round((uploadedBytes / totalBytes) * 100);
        this.progressBar.value = pct;
        this.progressText.textContent = `${pct}%`;

        const elapsed = (Date.now() - startedAt) / 1000;
        const speed = uploadedBytes / elapsed; // bytes/sec
        const remaining = totalBytes - uploadedBytes;
        const etaSec = speed > 0 ? Math.round(remaining / speed) : 0;

        this.speedText.textContent = `Speed: ${(speed / (1024 * 1024)).toFixed(2)} MB/s`;
        this.etaText.textContent = `ETA: ${GalleryApp.formatEta(etaSec)}`;
      };

      xhr.onload = () => (xhr.status >= 200 && xhr.status < 300)
        ? resolve()
        : reject(new Error(`Upload failed (${xhr.status})`));
      xhr.onerror = () => reject(new Error('Network error during upload.'));
      xhr.send(form);
    });

    // Simple worker pool
    const queue = files.slice();
    const workers = Array.from({ length: CONCURRENCY }, async () => {
      while (queue.length) {
        const f = queue.shift();
        await uploadOne(f);
      }
    });

    try {
      await Promise.all(workers);
    } catch (err) {
      alert(`Upload error: ${err.message}`);
    } finally {
      this.progressModal.setAttribute('aria-hidden','true');
      this.releaseWakeLock();
    }
  }

  // ===== Pagination / Lazy load grid =====
  async loadGallery() {
    // full refresh (e.g., after upload)
    this.galleryEl.innerHTML = '';
    this.paging = { nextOffset: 0, loading: false, done: false, pageSize: 50 };
    await this.loadNextPage();
  }

  async loadNextPage() {
    if (this.paging.loading || this.paging.done) return;
    this.paging.loading = true;

    try {
      const res = await fetch(`/api/media?offset=${this.paging.nextOffset}&limit=${this.paging.pageSize}`);
      const { items, nextOffset } = await res.json();

      if (this.paging.nextOffset === 0 && items.length === 0) {
        const empty = document.createElement('p');
        empty.style.color = '#aab2c0';
        empty.textContent = 'No media yet. Use “Upload” to add photos or videos.';
        this.galleryEl.appendChild(empty);
        this.paging.done = true;
        return;
      }

      this.appendItems(items);

      if (nextOffset === null) {
        this.paging.done = true;
      } else {
        this.paging.nextOffset = nextOffset;
      }
    } catch (err) {
      console.error('loadNextPage error', err);
    } finally {
      this.paging.loading = false;
    }
  }

  appendItems(items) {
    for (const it of items) {
      const tile = document.createElement('div');
      tile.className = 'tile';

      let mediaEl;
      if (it.type === 'image') {
        mediaEl = document.createElement('img');
        mediaEl.alt = it.name;
        mediaEl.loading = 'lazy';
        // placeholder to avoid layout shift
        mediaEl.src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==';
        mediaEl.dataset.src = it.url; // set real src lazily
      } else if (it.type === 'video') {
        mediaEl = document.createElement('video');
        mediaEl.muted = true;        // allow autoplay for priming
        mediaEl.loop = true;
        mediaEl.playsInline = true;
        mediaEl.preload = 'none';    // switch to metadata when visible
        mediaEl.dataset.src = it.url; // real src set lazily
        mediaEl.addEventListener('mouseenter', () => mediaEl.play());
        mediaEl.addEventListener('mouseleave', () => mediaEl.pause());
      } else {
        mediaEl = document.createElement('img');
        mediaEl.alt = it.name;
        mediaEl.loading = 'lazy';
        const svg = `
          <svg xmlns="http://www.w3.org/2000/svg" width="640" height="360">
            <rect width="100%" height="100%" fill="#222"/>
            <text x="50%" y="50%" font-size="24" fill="#bbb" text-anchor="middle" dominant-baseline="middle">
              ${it.name}
            </text>
          </svg>
        `;
        mediaEl.src = 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
      }

      const badge = document.createElement('div');
      badge.className = 'badge';
      badge.textContent = it.type.toUpperCase();

      tile.appendChild(mediaEl);
      tile.appendChild(badge);
      tile.addEventListener('click', () => this.openLightbox(it.type, it.url));
      this.galleryEl.appendChild(tile);

      // Observe for lazy load (only for those with data-src)
      if (mediaEl.dataset && mediaEl.dataset.src) {
        this.lazyIO.observe(mediaEl);
      }
    }
  }

  // ===== Video helpers =====
  primeVideoOnce(videoEl) {
    // Ensure attributes for silent, inline playback
    videoEl.muted = true;
    videoEl.playsInline = true;
    videoEl.preload = 'metadata';

    const onLoaded = async () => {
      try {
        // Force paint a frame on some browsers
        if (videoEl.readyState >= 2) {
          videoEl.currentTime = 0.001;
        }
        // Brief autoplay to paint the first frame, then pause
        await videoEl.play().catch(() => {});
        setTimeout(() => {
          try { videoEl.pause(); } catch {}
        }, 60);
      } catch {}
      videoEl.removeEventListener('loadeddata', onLoaded);
    };
    videoEl.addEventListener('loadeddata', onLoaded);
  }

  unloadVideo(el) {
    try { el.pause(); } catch {}
    el.removeAttribute('src');
    el.load(); // fully unload buffer
  }

  // ===== Utils =====
  static formatEta(sec) {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
  }
}

// Bootstrap
new GalleryApp();