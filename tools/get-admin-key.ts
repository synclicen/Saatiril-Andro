#!/usr/bin/env npx tsx
/**
 * SAATIRIL — Get Admin Key for Web License Generator
 *
 * Prints the admin key needed to access /admin license generator page.
 * Run this tool to get the key, then use it in the web interface.
 *
 * Usage:
 *   npx tsx tools/get-admin-key.ts
 */

// Fixed, user-friendly developer key (case-insensitive when entered).
// Matches the Android LicenseManager, electron/license.ts, and route.ts.
const ADMIN_KEY = 'fajribowo2026'

console.log('')
console.log('╔══════════════════════════════════════════════════╗')
console.log('║    SAATIRIL — Admin Key for Web Generator       ║')
console.log('╚══════════════════════════════════════════════════╝')
console.log('')
console.log(`  Admin Key: ${ADMIN_KEY}`)
console.log('')
console.log('  Gunakan key ini di halaman /admin untuk generate kode aktivasi.')
console.log('  (Case-insensitive — boleh diketik huruf besar/kecil.)')
console.log('')
