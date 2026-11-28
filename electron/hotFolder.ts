import fs from 'fs'
import path from 'path'

// ── Hot Folder: complete-file reads ──────────────────────────────────────
//
// The camera control software (EOS Utility / Imaging Edge) downloads each
// shot into the watched folder PROGRESSIVELY: the .JPG appears in readdir
// with its mtime the moment it is created, and only reaches its final size
// some hundreds of milliseconds later. A reader that grabs the file at that
// moment gets a TRUNCATED JPEG — the renderer's <img> then "loads" a broken
// image, drawImage paints nothing over the canvas' black prefill and the
// capture pipeline saves a pure-black photo (canvas black + frame overlay
// ≈ 39 KB at 1920px), marks the file processed and never retries it. That
// is exactly the black ~39 KB photos found in the hasil-foto folder while
// the camera's own folder was always clean.
//
// Guard: only hand out a file that ENDS with the JPEG EOI marker (FF D9).
// Every camera JPEG ends with it, and while the writer is still streaming
// the marker cannot sit at the file tail yet (entropy data is byte-stuffed
// so FF D9 never occurs inside it). Anything else is reported 'incomplete'
// and the renderer retries on its next poll — a half-written file is NEVER
// imported, whatever happens to the writer (slow, stalled, dead mid-file).

export type HotFolderRead =
  | { status: 'complete'; base64: string; size: number }
  | { status: 'incomplete' }
  | { status: 'error'; message: string }

const JPEG_EOI = Buffer.from([0xff, 0xd9])
const SETTLE_MS = 250

const endsWithJpegEoi = (buffer: Buffer): boolean =>
  buffer.length >= 2 && buffer.subarray(buffer.length - 2).equals(JPEG_EOI)

export async function readCompleteJpeg(
  folderPath: string,
  filename: string,
  settleMs: number = SETTLE_MS,
): Promise<HotFolderRead> {
  const filePath = path.join(folderPath, filename)
  const readOnce = (): Buffer | null => {
    try {
      return fs.readFileSync(filePath)
    } catch {
      return null
    }
  }

  // Fast path: the EOI marker is at the tail — the writer has finished.
  const first = readOnce()
  if (first === null) return { status: 'error', message: `tidak dapat membaca ${filename}` }
  if (endsWithJpegEoi(first)) {
    return { status: 'complete', base64: first.toString('base64'), size: first.length }
  }

  // No EOI at the tail yet — most likely the camera software is still
  // writing the file. Wait out the grace window and look once more: the
  // write often finishes right inside it.
  await new Promise((resolve) => setTimeout(resolve, settleMs))
  const after = readOnce()
  if (after !== null && endsWithJpegEoi(after)) {
    return { status: 'complete', base64: after.toString('base64'), size: after.length }
  }

  // Still no EOI — still (or no longer) in flight. Never import it
  // half-written; the renderer retries on its next poll.
  return { status: 'incomplete' }
}
