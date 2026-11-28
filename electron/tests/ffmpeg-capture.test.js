#!/usr/bin/env node
/**
 * Tests for electron/ffmpeg-capture.ts.
 *
 * usage:
 *   node electron/tests/ffmpeg-capture.test.js <compiled ffmpeg-capture.js> [<ffmpeg binary>]
 *
 *   npx tsc -p electron/tsconfig.json        → electron/ffmpeg-capture.js
 *   node electron/tests/ffmpeg-capture.test.js electron/ffmpeg-capture.js
 *
 * Without a binary (or SAATIRIL_FFMPEG) the ffmpeg-static dev dependency of
 * this checkout is used — ffmpeg.exe on Windows, ffmpeg elsewhere.
 *
 * Runs under plain Node on any OS: the 'electron' module is replaced by a
 * stub (window list, "capture ended" notification) and every spawned process
 * is recorded. The ffmpeg binary stands in for ffmpeg.exe: the Windows argv
 * is checked structurally, then run with a synthetic video source in place
 * of the gdigrab window — same output options.
 *
 * eos-live-view.e2e.js (Windows only) repeats the window selection with two
 * real windows and the real gdigrab.
 */
'use strict'
const assert = require('assert')
const http = require('http')
const Module = require('module')
const path = require('path')

const modPath = process.argv[2]
// absolute: the module reports the path it resolved (candidates are made absolute)
const ffmpeg = path.resolve(process.env.SAATIRIL_FFMPEG || process.argv[3] || require('ffmpeg-static'))
if (!modPath) {
  console.error('usage: node electron/tests/ffmpeg-capture.test.js <compiled ffmpeg-capture.js> [<ffmpeg binary>]')
  process.exit(2)
}
// The module's first candidate is this override, so it runs the very binary
// named on the command line — on Windows as on Linux.
process.env.SAATIRIL_FFMPEG = ffmpeg

// ── stubs ───────────────────────────────────────────────────────────────
const sent = [] // 'ffmpeg-capture:ended' pushes to the renderer
let stubSources = [] // window list returned by the desktopCapturer stub
const sourceCalls = [] // getSources() options
const electronStub = {
  BrowserWindow: {
    getAllWindows: () => [{
      isDestroyed: () => false,
      webContents: { send: (channel, info) => sent.push({ channel, info }) },
    }],
  },
  desktopCapturer: {
    getSources: async (options) => {
      sourceCalls.push(options)
      if (stubSources instanceof Error) throw stubSources
      return stubSources
    },
  },
}
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request === 'electron') return electronStub
  return originalLoad.call(this, request, parent, isMain)
}
const cp = require('child_process')
const realSpawn = cp.spawn
const spawned = []
cp.spawn = function (cmd, args, opts) {
  const proc = realSpawn.call(cp, cmd, args, opts)
  spawned.push({ cmd, args, proc, opts })
  return proc
}

const cap = require(path.resolve(modPath))

// ── tiny test runner ────────────────────────────────────────────────────
let passed = 0
let failed = 0
let skipped = 0
async function test(name, fn) {
  const t0 = Date.now()
  try {
    const outcome = await fn()
    if (outcome === 'skip') {
      skipped++
      console.log(`SKIP  ${name} (${skipReason})`)
      return
    }
    passed++
    console.log(`PASS  ${name} (${Date.now() - t0} ms)`)
  } catch (e) {
    failed++
    console.log(`FAIL  ${name}: ${e && e.message ? e.message : e}`)
  }
}
let skipReason = ''
/** fn() → 'skip' with the reason set here (a test that cannot run on this OS). */
function skip(reason) {
  skipReason = reason
  return 'skip'
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── helpers ─────────────────────────────────────────────────────────────
const SOI = Buffer.from([0xff, 0xd8])
const EOI = Buffer.from([0xff, 0xd9])

/** A desktopCapturer window source as the module uses it. */
function source(id, name, withThumbnail = true) {
  return {
    id,
    name,
    thumbnail: withThumbnail
      ? { isEmpty: () => false, toJPEG: () => Buffer.concat([SOI, Buffer.from(id), EOI]) }
      : { isEmpty: () => true, toJPEG: () => Buffer.alloc(0) },
  }
}

/** Runs `fn` with process.platform replaced (the module is written for Windows). */
async function withPlatform(value, fn) {
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform')
  Object.defineProperty(process, 'platform', { value })
  try {
    return await fn()
  } finally {
    Object.defineProperty(process, 'platform', descriptor)
  }
}

/** JPEG-shaped frame whose "entropy data" never contains FF D8 / FF D9. */
function fakeFrame(seed, size) {
  const body = Buffer.alloc(size)
  let x = seed
  for (let i = 0; i < size; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff
    body[i] = x % 200 // 0..199 — never 0xFF
  }
  return Buffer.concat([SOI, Buffer.from([0xff, 0xc0]), body, EOI])
}

/** Reads the MJPEG stream for `ms` → { status, frames, firstAfterMs }. */
function readStream(url, ms, headers = {}) {
  return new Promise((resolve, reject) => {
    const frames = []
    let buf = Buffer.alloc(0)
    let status = 0
    let firstAfterMs = null
    const t0 = Date.now()
    const req = http.get(url, { headers }, (res) => {
      status = res.statusCode
      res.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk])
        for (;;) {
          // part = "--frame\r\n" + headers + "\r\n\r\n" + body + "\r\n"
          const bodyStart = buf.indexOf('\r\n\r\n')
          if (bodyStart < 0) return
          const m = buf.subarray(0, bodyStart).toString('latin1').match(/content-length:\s*(\d+)/i)
          if (!m) return
          const len = Number(m[1])
          const start = bodyStart + 4
          if (buf.length < start + len) return
          frames.push(Buffer.from(buf.subarray(start, start + len)))
          if (firstAfterMs === null) firstAfterMs = Date.now() - t0
          buf = buf.subarray(start + len)
        }
      })
      res.on('error', () => {})
    })
    req.on('error', reject)
    setTimeout(() => {
      req.destroy()
      resolve({ status, frames, firstAfterMs })
    }, ms)
  })
}

/** Status code of a plain request (the 404 guard). Resolves on the headers — the stream itself never ends. */
function statusCode(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { headers }, (res) => {
      res.resume()
      resolve(res.statusCode)
      req.destroy()
    })
    req.on('error', reject)
  })
}

function refused(url) {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => { res.resume(); resolve(false) })
    req.on('error', (e) => resolve(e.code === 'ECONNREFUSED'))
  })
}

/** Width/height from the SOF marker of a JPEG image. */
function jpegSize(buf) {
  assert(buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8, 'not a JPEG (no SOI)')
  let i = 2
  while (i + 9 < buf.length) {
    assert.strictEqual(buf[i], 0xff, 'marker prefix')
    const marker = buf[i + 1]
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) }
    }
    i += 2 + buf.readUInt16BE(i + 2)
  }
  throw new Error('no SOF marker')
}

const lastSpawned = () => spawned[spawned.length - 1].proc
const lastTitle = () => (spawned[spawned.length - 1].args.find((a) => a.startsWith('title=')) || '').slice(6)
const waitUntil = async (predicate, timeoutMs) => {
  const t0 = Date.now()
  while (!predicate() && Date.now() - t0 < timeoutMs) await sleep(25)
  return predicate()
}

// ── the Windows argv, run with a synthetic source instead of a window ───
const winArgs = cap.gdigrabArgs('Remote Live View')
function withSource(sourceArgs) {
  const out = []
  for (let i = 0; i < winArgs.length; i++) {
    const a = winArgs[i]
    if (a === '-f' && winArgs[i + 1] === 'gdigrab') { out.push('-re', '-f', 'lavfi'); i++; continue }
    if (a === '-draw_mouse' || a === '-framerate') { i++; continue } // gdigrab-only input options
    if (a === '-i' && winArgs[i + 1].startsWith('title=')) { out.push('-i', sourceArgs); i++; continue }
    out.push(a)
  }
  return out
}
const TESTSRC = 'testsrc=size=1920x1080:rate=15'

async function main() {
  // ── argv + pure helpers ───────────────────────────────────────────────
  await test('gdigrabArgs: exact-title gdigrab input, MJPEG output, no fixed size', () => {
    assert.deepStrictEqual(winArgs.slice(0, 4), ['-hide_banner', '-loglevel', 'error', '-f'])
    assert.strictEqual(winArgs[winArgs.indexOf('-f')], '-f')
    assert.strictEqual(winArgs[winArgs.indexOf('-f') + 1], 'gdigrab')
    assert.strictEqual(winArgs[winArgs.indexOf('-framerate') + 1], '15')
    assert.strictEqual(winArgs[winArgs.indexOf('-draw_mouse') + 1], '0')
    assert.strictEqual(winArgs[winArgs.indexOf('-i') + 1], 'title=Remote Live View')
    assert.strictEqual(winArgs[winArgs.indexOf('-vf') + 1], "scale='min(1280,iw)':-2")
    assert.strictEqual(winArgs[winArgs.lastIndexOf('-f') + 1], 'mjpeg')
    assert.strictEqual(winArgs[winArgs.length - 1], 'pipe:1')
    assert(!winArgs.includes('-s'), 'must not force a fixed size (distorts the aspect ratio)')
    assert(winArgs.includes('-an'))
  })

  await test('rankCameraWindows: live view, "Remote", camera software, brands — no foreign titles', () => {
    const ranked = cap.rankCameraWindows([
      'Notes', 'SAATIRIL', 'Canon EOS R5 review - Google Chrome', 'EOS Utility 3',
      'Remote Live View', 'Task Manager', '', 'Canon - Wikipedia - Mozilla Firefox',
    ])
    assert.deepStrictEqual(ranked, ['Remote Live View', 'EOS Utility 3'])
    const brands = cap.rankCameraWindows(['Canon webcam utility', 'Imaging Edge', 'Live View'])
    assert.deepStrictEqual(brands, ['Live View', 'Imaging Edge', 'Canon webcam utility'])
    // Imaging Edge: start page and remote shooting window
    const edge = cap.rankCameraWindows(['Remote', 'Imaging Edge', 'Program Manager', 'Remote Desktop Connection'])
    assert.deepStrictEqual(edge, ['Remote', 'Imaging Edge'])
    assert.deepStrictEqual(cap.rankCameraWindows(['Remote - ILCE-7M4', 'EOS Utility 3']), ['Remote - ILCE-7M4', 'EOS Utility 3'])
  })

  await test('automaticCameraWindows: only windows that show the live view, live view first', () => {
    assert.deepStrictEqual(cap.automaticCameraWindows(['Imaging Edge', 'Remote']), ['Remote'], 'the launcher must never be a candidate')
    assert.deepStrictEqual(cap.automaticCameraWindows(['Remote', 'Remote Live View']), ['Remote Live View', 'Remote'])
    assert.deepStrictEqual(cap.automaticCameraWindows(['Remote - ILCE-7M4']), ['Remote - ILCE-7M4'])
    assert.deepStrictEqual(cap.automaticCameraWindows(['Imaging Edge']), [], 'start page alone')
    assert.deepStrictEqual(cap.automaticCameraWindows(['EOS Utility 3']), [], 'EOS Utility main window alone')
    assert.deepStrictEqual(cap.automaticCameraWindows(['LUMIX Tether', 'Sony']), [], 'brand names are picked by hand')
    assert.deepStrictEqual(cap.automaticCameraWindows(['Remote Desktop Connection', 'RemotePC', 'Remote - Google Chrome']), [])
  })

  await test('explainNoWindow: says what is open and what to open', () => {
    const none = cap.explainNoWindow([])
    assert.strictEqual(none.success, false)
    assert.strictEqual(none.error, 'no-window')
    assert.match(none.detail, /tidak ditemukan/)
    const edge = cap.explainNoWindow(['Imaging Edge', 'Program Manager'])
    assert.strictEqual(edge.error, 'live-view-closed')
    assert.match(edge.detail, /"Remote"/)
    const eos = cap.explainNoWindow(['EOS Utility 3'])
    assert.strictEqual(eos.error, 'live-view-closed')
    assert.match(eos.detail, /remote shooting/i)
    const failed = cap.explainNoWindow(['Remote'], 'gdigrab: boom')
    assert.strictEqual(failed.error, 'no-window')
    assert.match(failed.detail, /Remote/)
    assert.match(failed.detail, /gdigrab: boom/)
  })

  await test('listCaptureWindows: ranked, own windows out, browsers unrelated, previews on request', async () => {
    stubSources = [
      source('window:1:0', 'Task Manager'),
      source('window:2:0', 'Remote'),
      source('window:3:0', 'SAATIRIL'),
      source('window:4:0', 'Imaging Edge'),
      source('window:5:0', 'Canon EOS R5 review - Google Chrome'),
      source('window:6:0', 'Remote'), // same title twice → one choice for gdigrab
      source('window:7:0', ''),
    ]
    sourceCalls.length = 0
    const titles = await cap.listCaptureWindows()
    // rank first, then by name: unrelated windows come last in alphabetical order
    assert.deepStrictEqual(titles.map((w) => w.name), ['Remote', 'Imaging Edge', 'Canon EOS R5 review - Google Chrome', 'Task Manager'])
    assert.deepStrictEqual(titles.map((w) => w.rank), [1, 3, 5, 5])
    assert.strictEqual(titles[0].id, 'window:2:0')
    assert.deepStrictEqual(sourceCalls[0], { types: ['window'], thumbnailSize: { width: 0, height: 0 }, fetchWindowIcons: false })
    assert(titles.every((w) => w.thumbnail === undefined), 'no previews without a width')

    const withPreviews = await cap.listCaptureWindows(320)
    assert.deepStrictEqual(sourceCalls[1].thumbnailSize, { width: 320, height: 180 })
    assert.strictEqual(withPreviews[0].thumbnail, `data:image/jpeg;base64,${Buffer.concat([SOI, Buffer.from('window:2:0'), EOI]).toString('base64')}`)
    assert(withPreviews.every((w) => typeof w.thumbnail === 'string'), 'preview for every window')

    stubSources[1] = source('window:2:0', 'Remote', false) // desktopCapturer returned no picture
    assert.strictEqual((await cap.listCaptureWindows(320))[0].thumbnail, undefined)
  })

  await test('listCaptureWindows: survives a failing desktopCapturer', async () => {
    stubSources = new Error('desktopCapturer is not ready')
    assert.deepStrictEqual(await cap.listCaptureWindows(), [])
    stubSources = []
  })

  // The platform flag is set by hand in both directions, so the suite means
  // the same on a Windows runner as on a Linux one.
  await test('startWindowCapture: honest error code outside Windows', async () => {
    const before = spawned.length
    const r = await withPlatform('linux', () => cap.startWindowCapture())
    assert.strictEqual(r.success, false)
    assert.strictEqual(r.error, 'unsupported-platform')
    assert.strictEqual(spawned.length, before)
  })

  await test('resolveFfmpeg: SAATIRIL_FFMPEG override is probed and cached', async () => {
    const a = await cap.resolveFfmpeg()
    assert(a && a.path === ffmpeg, JSON.stringify(a))
    assert(typeof a.version === 'string' && a.version.length > 0, 'version')
    const b = await cap.resolveFfmpeg()
    assert.strictEqual(a, b)
  })

  // The window decision with the Windows platform flag: ffmpeg is started
  // with the real gdigrab argv (it has no gdigrab device here / finds no such
  // window in CI, so the start fails afterwards — the title is what counts).
  await test('startWindowCapture (Windows): Imaging Edge open with "Remote" → ffmpeg targets "Remote"', async () => {
    stubSources = [source('window:1:0', 'Imaging Edge'), source('window:2:0', 'Remote'), source('window:3:0', 'Program Manager')]
    const before = spawned.length
    const r = await withPlatform('win32', () => cap.startWindowCapture())
    assert.strictEqual(r.success, false)
    assert.strictEqual(spawned.length, before + 1, 'exactly one ffmpeg start')
    assert.strictEqual(lastTitle(), 'Remote')
    assert.strictEqual(r.error, 'no-window', JSON.stringify(r))
    assert.match(r.detail, /Remote/)
    assert(await waitUntil(() => lastSpawned().exitCode !== null || lastSpawned().signalCode !== null, 3000), 'ffmpeg still running')
  })

  await test('startWindowCapture (Windows): only the start page is open → nothing started, says to open "Remote"', async () => {
    stubSources = [source('window:1:0', 'Imaging Edge'), source('window:3:0', 'Program Manager')]
    const before = spawned.length
    const r = await withPlatform('win32', () => cap.startWindowCapture())
    assert.strictEqual(spawned.length, before, 'the start page must never be captured')
    assert.strictEqual(r.success, false)
    assert.strictEqual(r.error, 'live-view-closed')
    assert.match(r.detail, /"Remote"/)
    console.log(`      detail: ${r.detail}`)
  })

  await test('startWindowCapture (Windows): no camera software at all', async () => {
    stubSources = [source('window:3:0', 'Program Manager')]
    const before = spawned.length
    const r = await withPlatform('win32', () => cap.startWindowCapture())
    assert.strictEqual(spawned.length, before)
    assert.strictEqual(r.error, 'no-window')
  })

  await test('IPC: window list for the picker; a picked title is captured as it is', async () => {
    const handlers = {}
    cap.registerFfmpegCaptureIpc({ handle: (channel, fn) => { handlers[channel] = fn } })
    assert(handlers['ffmpeg-capture:start'] && handlers['ffmpeg-capture:stop'] && handlers['ffmpeg-capture:url'] && handlers['ffmpeg-capture:status'] && handlers['ffmpeg-capture:check'], 'known handlers kept')
    assert.strictEqual(typeof handlers['ffmpeg-capture:windows'], 'function')

    stubSources = Array.from({ length: 40 }, (_, i) => source(`window:${i}:0`, `App ${String(i).padStart(2, '0')}`))
    stubSources.push(source('window:40:0', 'Imaging Edge'), source('window:41:0', 'Remote'))
    const list = await handlers['ffmpeg-capture:windows']()
    assert.strictEqual(list.length, 24, 'picker list is capped')
    assert.deepStrictEqual(list.slice(0, 2).map((w) => w.name), ['Remote', 'Imaging Edge'], 'likely live view windows first')
    assert(list.every((w) => /^data:image\/jpeg;base64,/.test(w.thumbnail)), 'preview for every window')

    const outside = await withPlatform('linux', () => handlers['ffmpeg-capture:start']({}))
    assert.strictEqual(outside.error, 'unsupported-platform')
    const before = spawned.length
    const failed = await withPlatform('win32', () => handlers['ffmpeg-capture:start']({}, '  Imaging Edge  '))
    assert.strictEqual(failed.success, false)
    assert.strictEqual(spawned.length, before + 1)
    assert.strictEqual(lastTitle(), 'Imaging Edge', 'a picked title is used as it is (trimmed)')
    cap.stopWindowCapture()
    stubSources = []
  })

  // ── JPEG parser ───────────────────────────────────────────────────────
  const f1 = fakeFrame(1, 5000)
  const f2 = fakeFrame(2, 700)
  await test('parser: whole stream in one chunk', () => {
    const out = new cap.JpegFrameParser().push(Buffer.concat([f1, f2]))
    assert.deepStrictEqual(out, [f1, f2])
  })

  await test('parser: every possible two-chunk split gives identical frames', () => {
    const stream = Buffer.concat([f1, f2])
    for (let i = 1; i < stream.length; i += 1) {
      const p = new cap.JpegFrameParser()
      const out = [...p.push(stream.subarray(0, i)), ...p.push(stream.subarray(i))]
      assert.strictEqual(out.length, 2, `split at ${i}`)
      assert.deepStrictEqual(out[0], f1, `split at ${i}`)
      assert.deepStrictEqual(out[1], f2, `split at ${i}`)
    }
  })

  await test('parser: byte by byte', () => {
    const stream = Buffer.concat([f2, f1])
    const p = new cap.JpegFrameParser()
    const out = []
    for (const byte of stream) out.push(...p.push(Buffer.from([byte])))
    assert.deepStrictEqual(out, [f2, f1])
  })

  await test('parser: garbage before the first frame is dropped', () => {
    const p = new cap.JpegFrameParser()
    assert.deepStrictEqual(p.push(Buffer.concat([Buffer.alloc(1000, 0x37), f2])), [f2])
  })

  await test('parser: a stream without frame markers never grows', () => {
    const p = new cap.JpegFrameParser()
    const junk = Buffer.alloc(64 * 1024, 0x37)
    for (let i = 0; i < 300; i++) assert.strictEqual(p.push(junk).length, 0)
    assert(p.pending.length <= 1, `pending buffer is ${p.pending.length} bytes`)
  })

  await test('parser: SOI split across chunks (lone FF at the end of a chunk)', () => {
    const p = new cap.JpegFrameParser()
    assert.strictEqual(p.push(Buffer.concat([Buffer.alloc(10, 1), SOI.subarray(0, 1)])).length, 0)
    assert.deepStrictEqual(p.push(f1.subarray(1)), [f1])
  })

  await test('parser: restart markers and FF 00 stuffing do not split a frame', () => {
    const inner = Buffer.concat([
      Buffer.from([0xff, 0xd0, 0x12, 0x34]), Buffer.from([0xff, 0x00, 0x56]), Buffer.from([0xff, 0xd7, 0x9a]),
    ])
    const f = Buffer.concat([SOI, Buffer.from([0xff, 0xda]), inner, EOI])
    const p = new cap.JpegFrameParser()
    const out = [...p.push(f.subarray(0, 5)), ...p.push(f.subarray(5))]
    assert.deepStrictEqual(out, [f])
  })

  await test('parser: a stray FF D8 does not swallow the next frame', () => {
    const p = new cap.JpegFrameParser()
    assert.strictEqual(p.push(Buffer.concat([SOI, Buffer.alloc(50, 7)])).length, 0)
    assert.deepStrictEqual(p.push(f2), [f2])
  })

  await test('parser: reset() drops an unfinished frame', () => {
    const p = new cap.JpegFrameParser()
    p.push(f1.subarray(0, 100))
    p.reset()
    assert.deepStrictEqual(p.push(f2), [f2])
  })

  // ── real ffmpeg pipeline ──────────────────────────────────────────────
  let captured = null
  await test('pipeline: first frame gate resolves with URL + title', async () => {
    const t0 = Date.now()
    captured = await cap.startMjpegCapture(ffmpeg, withSource(TESTSRC), 'testsrc')
    const dt = Date.now() - t0
    assert.strictEqual(captured.success, true, captured.detail)
    assert.match(captured.url, /^http:\/\/127\.0\.0\.1:\d+\/liveview$/)
    assert.strictEqual(captured.title, 'testsrc')
    assert(dt < 6000, `first frame took ${dt} ms`)
    assert.strictEqual(spawned[spawned.length - 1].opts.windowsHide, true)
    console.log(`      ffmpeg ${cap.getCaptureStatus().ffmpeg.version}, first frame after ${dt} ms`)
  })

  await test('pipeline: HTTP stream carries 15 fps JPEG frames of 1280x720', async () => {
    const { status, frames, firstAfterMs } = await readStream(captured.url, 3000)
    assert.strictEqual(status, 200)
    assert(frames.length >= 20 && frames.length <= 60, `got ${frames.length} frames in 3 s`)
    let bytes = 0
    for (const f of frames) {
      assert(f[0] === 0xff && f[1] === 0xd8 && f[f.length - 2] === 0xff && f[f.length - 1] === 0xd9, 'JPEG markers')
      assert.deepStrictEqual(jpegSize(f), { height: 720, width: 1280 })
      bytes += f.length
    }
    console.log(`      ${frames.length} frames in 3 s, first after ${firstAfterMs} ms, ~${Math.round(bytes / frames.length / 1024)} KB per frame`)
  })

  await test('pipeline: status reflects the running capture', () => {
    const s = cap.getCaptureStatus()
    assert.strictEqual(s.active, true)
    assert.strictEqual(s.title, 'testsrc')
    assert.strictEqual(s.url, captured.url)
    assert(s.frames > 0)
    assert.strictEqual(s.ffmpeg.path, ffmpeg)
  })

  await test('pipeline: two viewers at once, a stalled one does not block the other', async () => {
    const stalled = new Promise((resolve, reject) => {
      const req = http.get(captured.url, (res) => { res.pause(); resolve({ req, res }) })
      req.on('error', reject)
    })
    const slow = await stalled
    const { frames } = await readStream(captured.url, 1500)
    assert(frames.length >= 10, `second viewer got ${frames.length} frames`)
    slow.req.destroy()
    assert.strictEqual(cap.getCaptureStatus().active, true)
  })

  await test('server: loopback guard (other Host header / other path → 404)', async () => {
    assert.strictEqual(await statusCode(captured.url), 200)
    assert.strictEqual(await statusCode(captured.url, { Host: 'evil.example' }), 404)
    assert.strictEqual(await statusCode(captured.url.replace('/liveview', '/other')), 404)
  })

  await test('server: stream is CORS-clean (the renderer reads it into a canvas)', async () => {
    const headers = await new Promise((resolve, reject) => {
      const req = http.get(captured.url, (res) => { resolve(res.headers); req.destroy() })
      req.on('error', reject)
    })
    // the page (localhost:<httpPort>) and the stream (127.0.0.1:<port>) are
    // different origins: without this header a crossOrigin="anonymous" <img>
    // would not load and the margin measurement could not read the pixels
    assert.strictEqual(headers['access-control-allow-origin'], '*')
    assert.match(String(headers['content-type']), /multipart\/x-mixed-replace/)
  })

  await test('server: a second start replaces the first (old stream closed)', async () => {
    const oldUrl = captured.url
    const r = await cap.startMjpegCapture(ffmpeg, withSource('testsrc=size=640x480:rate=15'), 'second')
    assert.strictEqual(r.success, true, r.detail)
    assert.notStrictEqual(r.url, oldUrl)
    assert.strictEqual(await refused(oldUrl), true, 'old server still listening')
    const { frames } = await readStream(r.url, 1000)
    assert.deepStrictEqual(jpegSize(frames[0]), { height: 480, width: 640 })
    captured = r
  })

  await test('stop(): ffmpeg is killed, server closed, nothing left running', async () => {
    const proc = lastSpawned()
    const url = captured.url
    cap.stopWindowCapture()
    assert.strictEqual(cap.getCaptureStatus().active, false)
    assert.strictEqual(cap.getLiveViewUrl(), null)
    assert(await waitUntil(() => proc.exitCode !== null || proc.signalCode !== null, 3000), 'ffmpeg still running')
    assert.strictEqual(await refused(url), true, 'server still listening')
    cap.stopWindowCapture() // idempotent
  })

  await test('failure: ffmpeg exits with an error → success=false with its message', async () => {
    const t0 = Date.now()
    const r = await cap.startMjpegCapture(ffmpeg, withSource('nosuchfilter=size=64x64:rate=15'), 'x')
    assert.strictEqual(r.success, false)
    assert.strictEqual(r.error, 'capture-failed')
    assert(r.detail && r.detail.length > 0, 'detail')
    assert(Date.now() - t0 < 5000, 'not fast enough')
    assert.strictEqual(cap.getCaptureStatus().active, false)
    console.log(`      detail: ${r.detail}`)
  })

  await test('failure: missing ffmpeg binary → ffmpeg-not-found', async () => {
    const r = await cap.startMjpegCapture('/nonexistent/dir/ffmpeg', withSource(TESTSRC))
    assert.strictEqual(r.success, false)
    assert.strictEqual(r.error, 'ffmpeg-not-found')
    assert.strictEqual(cap.getCaptureStatus().active, false)
  })

  await test('failure: no frame at all → capture-failed after the first-frame timeout', async () => {
    const t0 = Date.now()
    const args = ['-hide_banner', '-loglevel', 'error', '-re', '-f', 'lavfi', '-i', TESTSRC, '-vf', 'select=0', '-f', 'mjpeg', 'pipe:1']
    const r = await cap.startMjpegCapture(ffmpeg, args, 'silent')
    assert.strictEqual(r.success, false)
    assert.strictEqual(r.error, 'capture-failed')
    const dt = Date.now() - t0
    assert(dt >= 5500 && dt < 11000, `resolved after ${dt} ms`)
    assert.strictEqual(cap.getCaptureStatus().active, false)
    const proc = lastSpawned()
    assert(await waitUntil(() => proc.exitCode !== null || proc.signalCode !== null, 3000), 'ffmpeg still running')
  })

  await test('ended: ffmpeg killed while streaming → "exited" pushed, capture inactive', async () => {
    sent.length = 0
    const r = await cap.startMjpegCapture(ffmpeg, withSource('testsrc=size=320x240:rate=15'), 'crash')
    assert.strictEqual(r.success, true, r.detail)
    const proc = lastSpawned()
    proc.kill('SIGKILL')
    assert(await waitUntil(() => sent.length > 0, 3000), 'no ended event')
    assert.deepStrictEqual(sent.map((s) => `${s.channel}:${s.info.reason}`), ['ffmpeg-capture:ended:exited'])
    assert.strictEqual(cap.getCaptureStatus().active, false)
    assert.strictEqual(cap.getLiveViewUrl(), null)
  })

  await test('ended: ffmpeg hangs mid-stream → "stalled" pushed after ~5 s', async () => {
    if (process.platform === 'win32') return skip('SIGSTOP does not exist on Windows')
    sent.length = 0
    const r = await cap.startMjpegCapture(ffmpeg, withSource('testsrc=size=320x240:rate=15'), 'stall')
    assert.strictEqual(r.success, true, r.detail)
    const proc = lastSpawned()
    proc.kill('SIGSTOP')
    try {
      assert(await waitUntil(() => sent.length > 0, 9000), 'no ended event')
      assert.deepStrictEqual(sent.map((s) => `${s.channel}:${s.info.reason}`), ['ffmpeg-capture:ended:stalled'])
      assert.strictEqual(cap.getCaptureStatus().active, false)
    } finally {
      proc.kill('SIGCONT')
    }
  })

  await test('race: stop() during start → settles, no ffmpeg spawned, nothing left', async () => {
    const before = spawned.length
    const pending = cap.startMjpegCapture(ffmpeg, withSource(TESTSRC), 'race')
    cap.stopWindowCapture()
    const r = await pending
    assert.strictEqual(r.success, false)
    assert.strictEqual(spawned.length, before, 'ffmpeg must not be spawned')
    assert.strictEqual(cap.getCaptureStatus().active, false)
  })

  await test('race: stop() while waiting for the first frame → aborted start settles', async () => {
    const pending = cap.startMjpegCapture(ffmpeg, withSource(TESTSRC), 'aborted')
    await sleep(0)
    cap.stopWindowCapture()
    const r = await pending
    assert.strictEqual(r.success, false)
    assert.strictEqual(r.detail, 'Capture dihentikan.')
    assert.strictEqual(cap.getCaptureStatus().active, false)
    const proc = lastSpawned()
    assert(await waitUntil(() => proc.exitCode !== null || proc.signalCode !== null, 3000), 'ffmpeg still running')
  })

  cap.stopWindowCapture()
  for (const s of spawned) {
    try { s.proc.kill('SIGKILL') } catch (e) { /* gone */ }
  }
  console.log(`\n${passed} passed, ${failed} failed, ${skipped} skipped`)
  process.exit(failed === 0 ? 0 : 1)
}

setTimeout(() => { console.log('TIMEOUT — a test hung'); process.exit(3) }, 120000).unref()
main().catch((e) => { console.error('fatal:', e); process.exit(2) })
