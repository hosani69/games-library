// Camera helpers: live barcode scanning and cover photos.

let zxingLoading;
function loadZxing() {
  if (window.ZXing) return Promise.resolve(window.ZXing);
  if (!zxingLoading) {
    zxingLoading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'vendor/zxing.min.js';
      s.onload = () => resolve(window.ZXing);
      s.onerror = () => reject(new Error('Could not load the barcode reader'));
      document.head.appendChild(s);
    });
  }
  return zxingLoading;
}

const NATIVE_FORMATS = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39', 'qr_code'];

async function nativeDetector() {
  if (!('BarcodeDetector' in window)) return null;
  try {
    const supported = await window.BarcodeDetector.getSupportedFormats();
    const formats = NATIVE_FORMATS.filter(f => supported.includes(f));
    return formats.length ? new window.BarcodeDetector({ formats }) : null;
  } catch { return null; }
}

// Opens the full-screen scanner. Resolves with the barcode text, or null if cancelled.
export function scanBarcode() {
  const overlay = document.getElementById('scanner');
  const video = overlay.querySelector('video');
  const msg = overlay.querySelector('.scan-msg');
  const manual = overlay.querySelector('#manual-code');
  overlay.hidden = false;
  msg.textContent = 'Starting camera…';
  manual.value = '';

  return new Promise(resolve => {
    let stream, stopped = false, zxReader, raf;

    const finish = code => {
      if (stopped) return;
      stopped = true;
      cancelAnimationFrame(raf);
      if (zxReader) try { zxReader.reset(); } catch {}
      if (stream) stream.getTracks().forEach(t => t.stop());
      video.srcObject = null;
      overlay.hidden = true;
      overlay.querySelector('.scan-cancel').onclick = null;
      overlay.querySelector('#manual-form').onsubmit = null;
      if (code && navigator.vibrate) navigator.vibrate(60);
      resolve(code || null);
    };

    overlay.querySelector('.scan-cancel').onclick = () => finish(null);
    overlay.querySelector('#manual-form').onsubmit = e => {
      e.preventDefault();
      finish(manual.value.trim() || null);
    };

    (async () => {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        msg.textContent = 'Camera not available here. Type the barcode number below.';
        return;
      }
      const constraints = { video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false };
      const detector = await nativeDetector();
      try {
        if (detector) {
          stream = await navigator.mediaDevices.getUserMedia(constraints);
          if (stopped) return finish(null);
          video.srcObject = stream;
          await video.play();
          msg.textContent = 'Point at the barcode on the back of the box';
          let busy = false;
          const tick = async () => {
            if (stopped) return;
            if (!busy && video.readyState >= 2) {
              busy = true;
              try {
                const codes = await detector.detect(video);
                if (codes.length) return finish(codes[0].rawValue);
              } catch {}
              busy = false;
            }
            raf = requestAnimationFrame(tick);
          };
          tick();
        } else {
          const ZXing = await loadZxing();
          if (stopped) return;
          const hints = new Map();
          hints.set(ZXing.DecodeHintType.POSSIBLE_FORMATS, [
            ZXing.BarcodeFormat.EAN_13, ZXing.BarcodeFormat.EAN_8, ZXing.BarcodeFormat.UPC_A,
            ZXing.BarcodeFormat.UPC_E, ZXing.BarcodeFormat.CODE_128, ZXing.BarcodeFormat.QR_CODE,
          ]);
          zxReader = new ZXing.BrowserMultiFormatReader(hints, 300);
          msg.textContent = 'Point at the barcode on the back of the box';
          await zxReader.decodeFromConstraints(constraints, video, result => {
            if (result) finish(result.getText());
          });
          stream = video.srcObject;
        }
      } catch (e) {
        msg.textContent = e && e.name === 'NotAllowedError'
          ? 'Camera permission was refused. Allow it in your browser settings, or type the barcode below.'
          : 'Could not start the camera. Type the barcode below.';
      }
    })();
  });
}

// Shrinks a photo (File/Blob) to a JPEG data URL so the library and backups stay small.
export async function imageToDataUrl(blob, max = 900, quality = 0.82) {
  const url = URL.createObjectURL(blob);
  try {
    const img = await new Promise((resolve, reject) => {
      const i = new Image();
      i.onload = () => resolve(i);
      i.onerror = () => reject(new Error('Not an image'));
      i.src = url;
    });
    const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
    const c = document.createElement('canvas');
    c.width = Math.round(img.naturalWidth * scale);
    c.height = Math.round(img.naturalHeight * scale);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    return c.toDataURL('image/jpeg', quality);
  } finally {
    URL.revokeObjectURL(url);
  }
}

// Downloads a cover from the internet and stores it locally. Falls back to the plain URL
// when the image host does not allow it (the picture still shows, it just isn't in backups).
export async function fetchCover(url) {
  if (!url) return '';
  let res;
  try {
    res = await fetch(url, { mode: 'cors' });
  } catch {
    return url;
  }
  if (!res.ok) return ''; // no cover exists at that address
  try {
    const blob = await res.blob();
    if (!blob.type.startsWith('image/')) return '';
    return await imageToDataUrl(blob, 700);
  } catch {
    return url;
  }
}
