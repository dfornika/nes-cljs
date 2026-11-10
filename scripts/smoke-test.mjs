// Smoke test: serve the built site, open it in headless Chromium, play the
// sample ROMs, visit every inspector tab, and try big-screen mode, failing
// on any page error.
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

// Where screenshots of failed steps go (CI uploads this folder on failure).
const shotDir = path.resolve(process.env.SMOKE_SHOTS || 'smoke-failures');

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {});
try {
  const page = await browser.newPage({viewport: {width: 1400, height: 1000}});
  // Waits are for things to happen, so they can be generous: a slow
  // machine takes longer, a fast one doesn't wait.
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  // Run one step of the test. If it throws (say, something never
  // appeared), record which step and why, save a screenshot, and go on.
  const step = async (name, fn) => {
    try {
      await fn();
    } catch (e) {
      check(false, `${name}: ${e.message.split('\n')[0]}`);
      fs.mkdirSync(shotDir, {recursive: true});
      await page.screenshot({path: path.join(shotDir, name.replace(/\W+/g, '-') + '.png')}).catch(() => {});
    }
  };

  // Wait until `fn` (run in Node) returns something truthy.
  const until = async (fn, timeout = 10000) => {
    const end = Date.now() + timeout;
    for (;;) {
      const v = await fn().catch(() => null);
      if (v) return v;
      if (Date.now() > end) throw new Error('timed out waiting');
      await page.waitForTimeout(100);
    }
  };

  const colorsOnScreen = () => page.evaluate(() => {
    const c = document.querySelector('canvas');
    const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    const seen = new Set();
    for (let i = 0; i < d.length; i += 4) seen.add((d[i] << 16) | (d[i + 1] << 8) | d[i + 2]);
    return seen.size;
  });
  // The frame number, from the CPU tab
  const frame = async () => parseInt((await page.locator('text=/^Frame \\d+/').first().textContent()).match(/\d+/)[0]);

  await step('page loads', async () => {
    await page.goto(url);
    await page.waitForSelector('text=Sample ROMs');
    check(true, 'page loads');
  });

  await step('page fits the window', async () => {
    // The page itself shouldn't scroll (the inspector scrolls inside itself).
    const [scrollH, viewH] = await page.evaluate(() => [document.documentElement.scrollHeight, innerHeight]);
    check(scrollH <= viewH, `the page fits the window (${scrollH} <= ${viewH})`);
  });

  // Each sample ROM should draw something: more than one color on screen.
  for (const rom of ['Thwaite', '240p Test Suite', 'Concentration Room']) {
    await step(`${rom} draws`, async () => {
      await page.click('button:text-is("Sample ROMs ▾")');
      await page.click(`button:text-is("${rom}")`);
      await page.waitForSelector(`.rom-name:has-text("${rom === 'Thwaite' ? 'thwaite' : rom === '240p Test Suite' ? '240pee' : 'croom'}")`);
      const colors = await until(async () => { const n = await colorsOnScreen(); return n > 1 && n; });
      check(true, `${rom} draws to the screen (${colors} colors)`);
    });
  }

  await step('emulation runs', async () => {
    // Frames should be advancing: 30 more within a few seconds.
    await page.click('button:text-is("CPU")');
    const f0 = await frame();
    const f1 = await until(async () => { const f = await frame(); return f > f0 + 30 && f; });
    check(true, `emulation runs (frame ${f0} → ${f1})`);
  });

  // Every tab renders without errors.
  for (const tab of ['Schematic', 'Cartridge', 'CPU', 'PPU', 'Code', 'Instructions', 'Memory', 'Lens',
                     'Tiles (CHR)', 'Sprites (OAM)', 'Background', 'Palettes']) {
    await step(`${tab} tab`, async () => {
      const before = errors.length;
      await page.click(`button:text-is("${tab}")`);
      await page.waitForSelector('.inspector-body > *');
      await page.waitForTimeout(200);   // let it draw a frame or two
      check(errors.length === before, `${tab} tab renders`);
    });
  }

  await step('pause / step / run', async () => {
    await page.click('button:text-is("Pause")');
    await page.waitForSelector('button:text-is("Run")');
    await page.click('button:text-is("Step instruction")');
    await page.click('button:text-is("Step line")');
    await page.click('button:text-is("Step frame")');
    await page.click('button:text-is("Run")');
    await page.waitForSelector('button:text-is("Pause")');
    check(true, 'pause / step / run');
  });

  await step('breakpoint', async () => {
    // Stop at the start of the NMI handler, which runs every frame.
    await page.click('button:text-is("CPU")');
    const nmi = (await page.locator('tr:has(td:text-is("NMI")) td').nth(1).textContent()).match(/\$[0-9A-F]{4}/)[0];
    await page.selectOption('form select', 'exec');
    await page.fill('form input[placeholder="$addr"]', nmi);
    await page.click('form button:text-is("Add")');
    await page.waitForSelector('text=Stopped at a breakpoint:');
    const pc = (await page.locator('[title^="Program counter"] div').nth(1).textContent()).trim();
    check(pc === nmi, `breakpoint: stops at the NMI handler (${nmi}, PC ${pc})`);
    await page.click('button[title="Remove"]');
    await page.click('button:text-is("Continue")');
    await page.waitForSelector('button:text-is("Pause")');
  });

  await step('code log', async () => {
    // Record some play, then follow the code: routines turn up.
    await page.click('button:text-is("Code")');
    await page.click('button:text-is("● Record")');
    await until(async () => {
      const t = await page.locator('text=/frames recorded/').textContent();
      return parseInt(t) > 60;
    });
    await page.click('button:text-is("■ Stop recording")');
    await page.click('button:has-text("Find routines")');
    const rows = page.locator('h4:text("Routines") + div table tbody tr');
    const n = await until(async () => { const c = await rows.count(); return c > 5 && c; });
    check(true, `code log: finds routines (${n} listed)`);
  });

  await step('lens editing', async () => {
    // Pause, set Concentration Room's tries-left to 42.
    await page.click('button:text-is("Pause")');
    await page.click('button:text-is("Lens")');
    await page.click('button:text-is("Pointer & score")');
    const tries = page.locator('tr', {has: page.locator('td:text-is("tries-left")')});
    await tries.locator('span[title^="Click to change"]').click();
    await tries.locator('input').fill('42');
    await tries.locator('input').press('Enter');
    const text = await until(async () => {
      const t = (await tries.locator('td').nth(2).textContent()).trim();
      return t.startsWith('42') && t;
    });
    check(true, `lens: editing a value writes it (${text})`);
    await page.click('button:text-is("Run")');
  });

  await step('big screen', async () => {
    // The inspectors move into a drawer at the bottom.
    await page.click('button:text-is("Big screen")');
    await page.click('.drawer-handle');
    await page.waitForSelector('.drawer .inspector');
    const drawerH = await page.locator('.drawer').evaluate(e => e.offsetHeight);
    check(drawerH > 100, `big screen: the inspector drawer opens (${drawerH}px)`);
    await page.click('.drawer-handle');
    await page.click('button:text-is("Exit big screen")');
    await page.waitForSelector('.app-main > .inspector');
    check(true, 'big screen: back to normal');
  });

  check(errors.length === 0, `no errors in the page${errors.length ? ':\n  ' + errors.join('\n  ') : ''}`);
} finally {
  await browser.close();
  server.close();
}

if (problems.length) {
  console.error(`\n${problems.length} problem(s)` +
                (fs.existsSync(shotDir) ? `; screenshots in ${shotDir}` : ''));
  process.exit(1);
}
console.log('\nsmoke test passed');
