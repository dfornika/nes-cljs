// Smoke test: serve the built site, open it in headless Chromium, play the
// sample ROM, and visit every inspector tab, failing on any page error.
//
//   npm run release && npm run smoke
//
// Options (environment variables):
//   SITE_DIR      directory to serve (default: resources/public)
//   CHROMIUM_PATH use this Chromium instead of Playwright's own
//                 (which `npx playwright install chromium` downloads)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {chromium} from 'playwright';

const siteDir = path.resolve(process.env.SITE_DIR || 'resources/public');
const types = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
               '.nes': 'application/octet-stream', '.ico': 'image/x-icon', '.json': 'application/json'};

// A tiny static file server, so the test needs nothing else running.
const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  let file = path.join(siteDir, urlPath);
  if (!file.startsWith(siteDir)) { res.writeHead(403).end(); return; }
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404).end(); return; }
    res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'}).end(data);
  });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}/`;

const problems = [];
const check = (ok, message) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${message}`);
  if (!ok) problems.push(message);
};

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {});
try {
  const page = await browser.newPage({viewport: {width: 1400, height: 1000}});
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(url);
  await page.waitForSelector('text=Concentration Room');
  check(true, 'page loads');

  await page.click('text=Concentration Room');
  await page.waitForTimeout(2500);

  // The game should have drawn something: more than one color on screen.
  const colors = await page.evaluate(() => {
    const c = document.querySelector('canvas');
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    const seen = new Set();
    for (let i = 0; i < d.length; i += 4) seen.add((d[i] << 16) | (d[i + 1] << 8) | d[i + 2]);
    return seen.size;
  });
  check(colors > 1, `the game draws to the screen (${colors} colors)`);

  // Frames should be advancing. The CPU tab shows the frame number.
  await page.click('button:text-is("CPU")');
  const frame = async () => parseInt((await page.locator('text=/^Frame \\d+/').first().textContent()).match(/\d+/)[0]);
  const f0 = await frame();
  await page.waitForTimeout(1000);
  const f1 = await frame();
  check(f1 - f0 > 30, `emulation runs (${f1 - f0} frames in 1 s)`);

  // Every tab renders without errors.
  const tabs = ['Schematic', 'Cartridge', 'CPU', 'Memory', 'Lens', 'Tiles (CHR)',
                'Sprites (OAM)', 'Background', 'Palettes'];
  for (const tab of tabs) {
    const before = errors.length;
    await page.click(`button:text-is("${tab}")`);
    await page.waitForTimeout(400);
    check(errors.length === before, `${tab} tab renders`);
  }

  // Pause, step, run.
  await page.click('button:text-is("Pause")');
  await page.click('button:text-is("Step instruction")');
  await page.click('button:text-is("Step frame")');
  await page.click('button:text-is("Run")');
  check(true, 'pause / step / run');

  check(errors.length === 0, `no errors in the page${errors.length ? ':\n  ' + errors.join('\n  ') : ''}`);
} finally {
  await browser.close();
  server.close();
}

if (problems.length) {
  console.error(`\n${problems.length} problem(s)`);
  process.exit(1);
}
console.log('\nsmoke test passed');
