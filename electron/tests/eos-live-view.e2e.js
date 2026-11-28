#!/usr/bin/env node
/**
 * Windows end-to-end test of the live view window selection: real windows,
 * the real gdigrab and the ffmpeg.exe that ships with the app.
 *
 * usage:
 *   node electron/tests/eos-live-view.e2e.js <compiled ffmpeg-capture.js> <ffmpeg.exe> <out dir>
 *
 * eos-windows.ps1 must have opened its two windows first. They stand in for
 * Imaging Edge: the start page ("Imaging Edge", light) and the remote
 * shooting window ("Remote", green). The module learns their titles the way
 * desktopCapturer reports them, and must
 *   1. capture "Remote" by itself — never the start page — and
 *   2. capture exactly the window whose title was picked by hand.
 * The colour of the middle of the streamed frame tells which window it is.
 */
'use strict'
const assert = require('assert')
const { execFileSync } = require('child_process')
const fs = require('fs')
const http = require('http')
const Module = require('module')
const path = require('path')

const [modPath, ffmpeg, outDir] = process.argv.slice(2)
if (!modPath || !ffmpeg || !outDir) {
  console.error('usage: node electron/tests/eos-live-view.e2e.js <compiled ffmpeg-capture.js> <ffmpeg.exe> <out dir>')
  process.exit(2)
}
if (process.platform !== 'win32') {
  console.error('Windows only — gdigrab does not exist here')
  process.exit(2)
}
fs.mkdirSync(outDir, { recursive: true })

// ── stubs ───────────────────────────────────────────────────────────────
// The titles of the windows eos-windows.ps1 created, as desktopCapturer
// would list them; BrowserWindow is only used for the "ended" push.
const WINDOWS = [
  { id: 'window:101:0', name: 'Program Manager' },
  { id: 'window:102:0', name: 'Imaging Edge' },
  { id: 'window:103:0', name: 'Remote' },
]
const electronStub = {
  BrowserWindow: { getAllWindows: () => [] },
  desktopCapturer: {
    getSources: async () => WINDOWS.map((w) => ({ ...w, thumbnail: { isEmpty: () => true } })),
  },
}
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request === 'electron') return electronStub
  return originalLoad.call(this, request, parent, isMain)
}
const cap = require(path.resolve(modPath))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** First complete JPEG of the MJPEG stream. */
function firstFrame(url, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      let buf = Buffer.alloc(0)
      res.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk])
        const bodyStart = buf.indexOf('\r\n\r\n')
        const m = bodyStart >= 0 ? buf.subarray(0, bodyStart).toString('latin1').match(/content-length:\s*(\d+)/i) : null
        if (!m) return
        const len = Number(m[1])
        const start = bodyStart + 4
        if (buf.length < start + len) return
        req.destroy()
        resolve(Buffer.from(buf.subarray(start, start + len)))
      })
      res.on('error', () => {})
    })
    req.on('error', reject)
    setTimeout(() => {
      req.destroy()
      reject(new Error(`no frame within ${timeoutMs} ms`))
    }, timeoutMs).unref()
  })
}

/** Average colour of the middle quarter of a JPEG image, decoded by ffmpeg. */
function centerColor(jpegFile) {
  const rgb = execFileSync(ffmpeg, [
    '-hide_banner', '-loglevel', 'error', '-i', jpegFile,
    '-vf', 'crop=iw/2:ih/2:iw/4:ih/4,scale=1:1',
    '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1',
  ])
  return { r: rgb[0], g: rgb[1], b: rgb[2] }
}

/** Starts a capture, fetches one frame and reports its colour. */
async function captureAndMeasure(label, windowTitle) {
  let result = null
  let last = null
  // the windows may still be opening, or a previous ffmpeg still releasing the title
  for (let attempt = 1; attempt <= 8 && !result; attempt++) {
    last = windowTitle === undefined ? await cap.startWindowCapture() : await cap.startWindowCapture(windowTitle)
    if (last.success) result = last
    else {
      console.log(`  attempt ${attempt}: ${last.error} — ${last.detail}`)
      await sleep(1000)
    }
  }
  assert(result, `${label}: capture never started (${last && last.error}: ${last && last.detail})`)
  const frame = await firstFrame(result.url)
  const file = path.join(outDir, `${label}.jpg`)
  fs.writeFileSync(file, frame)
  const color = centerColor(file)
  console.log(`${label}: title "${result.title}", frame ${frame.length} bytes → ${file}, centre rgb(${color.r},${color.g},${color.b})`)
  return { result, color }
}

async function main() {
  // 1. by itself: both windows are open → "Remote", never the start page
  const auto = await captureAndMeasure('automatic')
  assert.strictEqual(auto.result.title, 'Remote', 'the start page was captured instead of the live view')
  assert(auto.color.g >= 120 && auto.color.r <= 70 && auto.color.b <= 70,
    `automatic capture does not show the "Remote" window: ${JSON.stringify(auto.color)}`)
  cap.stopWindowCapture()

  // 2. picked by hand: the start page's title → exactly that window
  const picked = await captureAndMeasure('picked-launcher', 'Imaging Edge')
  assert.strictEqual(picked.result.title, 'Imaging Edge')
  assert(picked.color.r >= 200 && picked.color.g >= 200 && picked.color.b >= 200,
    `picked capture does not show the "Imaging Edge" window: ${JSON.stringify(picked.color)}`)
  cap.stopWindowCapture()

  console.log('PASS  EOS live view: "Remote" captured by itself, a picked title captures that window')
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    cap.stopWindowCapture()
    console.error(`FAIL  ${e && e.message ? e.message : e}`)
    process.exit(1)
  })

setTimeout(() => { console.error('TIMEOUT — the test hung'); process.exit(3) }, 90000).unref()
