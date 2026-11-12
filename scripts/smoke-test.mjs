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

  await step('divider', async () => {
    // Dragging the bar between the screen and the inspectors resizes
    // them; a double-click puts it back.
    const inspectorWidth = () => page.locator('.inspector').evaluate(e => e.getBoundingClientRect().width);
    const w0 = await inspectorWidth();
    const bar = await page.locator('.splitter').boundingBox();
    await page.mouse.move(bar.x + bar.width / 2, bar.y + bar.height / 2);
    await page.mouse.down();
    await page.mouse.move(bar.x + bar.width / 2 - 200, bar.y + bar.height / 2, {steps: 5});
    await page.mouse.up();
    const w1 = await until(async () => { const w = await inspectorWidth(); return w > w0 + 150 && w; });
    await page.locator('.splitter').dblclick();
    await until(async () => Math.abs(await inspectorWidth() - w0) < 2);
    check(true, `divider: dragging it resizes the inspectors (${Math.round(w0)}px → ${Math.round(w1)}px), double-click resets`);
  });

  // Each sample ROM should draw something: more than one color on screen.
  // ("Hello" isn't a file: the page assembles it from ClojureScript data.)
  for (const [rom, file] of [['Hello', 'hello'], ['Thwaite', 'thwaite'], ['240p Test Suite', '240pee'],
                             ['Concentration Room', 'croom']]) {
    await step(`${rom} draws`, async () => {
      await page.click('button:text-is("Sample ROMs ▾")');
      await page.click(`button:text-is("${rom}")`);
      await page.waitForSelector(`.rom-name:has-text("${file}")`);
      const colors = await until(async () => { const n = await colorsOnScreen(); return n > 1 && n; });
      check(true, `${rom} draws to the screen (${colors} colors)`);
    });
    if (rom === 'Hello') await step('Hello responds to the controller', async () => {
      // The face is the only yellow on the canvas. Its left edge moves
      // right while Right is held.
      const faceLeft = () => page.evaluate(() => {
        const c = document.querySelector('canvas');
        const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
        let left = null;
        for (let i = 0; i < d.length; i += 4) {
          if (d[i] > 180 && d[i + 1] > 150 && d[i + 2] < 120) {
            const x = (i / 4) % c.width;
            if (left === null || x < left) left = x;
          }
        }
        return left;
      });
      const x0 = await until(async () => { const v = await faceLeft(); return v !== null && v; });
      await page.keyboard.down('ArrowRight');
      const x1 = await until(async () => { const v = await faceLeft(); return v > x0 + 8 && v; });
      await page.keyboard.up('ArrowRight');
      check(true, `Hello: holding Right moves the face (x ${x0} → ${x1})`);
    });
    if (rom === 'Hello') await step('Hello brings its names', async () => {
      // A game built here knows its variables and labels: they're a lens,
      // and the disassembly uses them.
      await page.click('button:text-is("Lens")');
      await page.waitForSelector('td:text-is("player")');
      await page.click('button:text-is("CPU")');
      await page.waitForSelector('text=wait-for-frame');
      check(true, 'Hello: its variables are a lens, and the disassembly shows its names');
    });
  }

  await step('the ladder', async () => {
    // The smallest cartridge loads and runs (doing nothing); the next rung
    // up colors the screen.
    await page.click('button:text-is("Sample ROMs ▾")');
    await page.click('button:text-is("nil")');
    await page.waitForSelector('.rom-name:has-text("nil")');
    await page.click('button:text-is("CPU")');
    const f0 = await frame();
    await until(async () => (await frame()) > f0 + 10);
    check(true, 'the ladder: nil runs');
    await page.click('button:text-is("Sample ROMs ▾")');
    await page.click('button:text-is("color")');
    await page.waitForSelector('.rom-name:has-text("color")');
    const blue = await until(() => page.evaluate(() => {
      const c = document.querySelector('canvas');
      const d = c.getContext('2d').getImageData(128, 120, 1, 1).data;
      return d[2] > 150 && d[2] > d[0] && [d[0], d[1], d[2]].join(',');
    }));
    check(true, `the ladder: color turns the screen blue (rgb ${blue})`);
  });

  await step('overlays', async () => {
    // Shapes drawn over the picture from the game's own variables.
    await page.click('button:text-is("Sample ROMs ▾")');
    await page.click('button:text-is("Catch the Stars")');
    await page.waitForSelector('.rom-name:has-text("catch")');
    await page.keyboard.down('Enter');
    await page.waitForTimeout(150);
    await page.keyboard.up('Enter');
    await page.click('.overlay-chooser label:has-text("Touching")');
    await page.waitForSelector('.overlay-layer text:text-is("player")');
    const boxes = await page.locator('.overlay-layer rect').count();
    check(boxes >= 4, `overlays: the game's touch boxes are drawn (${boxes} boxes)`);
    await page.click('.overlay-chooser label:has-text("Sprites")');
    const more = await until(async () => { const n = await page.locator('.overlay-layer rect').count(); return n >= boxes + 12 && n; });
    check(true, `overlays: and a box for each sprite (${more} boxes)`);
  });

  await step('painting a tile', async () => {
    // In the Tiles tab, paint the top left pixel of the Hello ROM's face
    // tile: the tile's text changes, and the cartridge is marked changed.
    await page.click('button:text-is("Sample ROMs ▾")');
    await page.click('button:text-is("Hello")');
    await page.waitForSelector('.rom-name:has-text("hello")');
    await page.click('button:text-is("Tiles (CHR)")');
    // tile 1 is the second tile of pattern table 0 (each is 16 pixels wide here)
    await page.locator('canvas').nth(1).click({position: {x: 24, y: 8}});
    await page.waitForSelector('text=Paint tile $01');
    const text = () => page.locator('.tile-text').textContent();
    const before = await text();
    await page.locator('.tile-color-on').waitFor();
    await page.click('.tile-editor button:text-is("3")');
    await page.locator('.tile-paint > div').first().dispatchEvent('pointerdown', {buttons: 1, pointerId: 1});
    const after = await until(async () => { const t = await text(); return t !== before && t; });
    check(after.startsWith('["3'), `painting a tile: the pixel changes (${after.slice(0, 12)}…)`);
    await page.waitForSelector('text=Changed in the cartridge too');
    await page.click('button:text-is("Cartridge")');
    await page.waitForSelector('text=Download the changed ROM');
    check(true, 'painting a tile: the change goes into the cartridge');
  });

  await step('Catch the Stars plays', async () => {
    // A whole game built in the page: the title screen draws, and Start
    // begins play (the game's own variables say so, in the Lens tab).
    await page.click('button:text-is("Sample ROMs ▾")');
    await page.click('button:text-is("Catch the Stars")');
    await page.waitForSelector('.rom-name:has-text("catch")');
    await until(async () => (await colorsOnScreen()) > 3);
    await page.click('button:text-is("Lens")');
    const stateRow = page.locator('tr', {has: page.locator('td:text-is("rt.state")')});
    await until(async () => (await stateRow.textContent()).includes('title'));
    await page.keyboard.down('Enter');
    await until(async () => (await stateRow.textContent()).includes('playing'));
    await page.keyboard.up('Enter');
    check(true, 'Catch the Stars: title screen, then Start begins play');
  });
  // (the steps below expect Concentration Room to be the game loaded)
  await step('back to Concentration Room', async () => {
    await page.click('button:text-is("Sample ROMs ▾")');
    await page.click('button:text-is("Concentration Room")');
    await page.waitForSelector('.rom-name:has-text("croom")');
  });

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

  await step('ClojureScript CPU', async () => {
    // Swap in NES.cljs's own 6502: the game keeps running, and a short
    // run with each CPU gives identical pictures and RAM.
    await page.click('button:text-is("CPU")');
    await page.click('text=NES.cljs\'s own 6502');
    const f0 = await frame();
    await until(async () => (await frame()) > f0 + 30);
    await page.click('button:text-is("Compare the two CPUs")');
    const verdict = await until(async () => {
      const t = await page.locator('text=/identical|they differ/').first().textContent().catch(() => '');
      return t.trim();
    }, 120000);
    check(verdict.includes('identical'), `ClojureScript CPU runs, and matches jsnes (${verdict.slice(0, 60)})`);
    await page.click('text=jsnes\'s 6502');
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

  await step('touch controller', async () => {
    // A phone: the on-screen controller appears, and pressing it presses
    const phone = await browser.newContext({viewport: {width: 412, height: 915}, hasTouch: true, isMobile: true});
    const p = await phone.newPage();
    p.on('pageerror', e => errors.push(e.message));
    await p.goto(url);
    await p.click('button:text-is("Sample ROMs ▾")');
    await p.click('button:text-is("Concentration Room")');
    await p.waitForSelector('.touchpad');
    // a finger on the middle of A (the controller works out what's under it)
    const touch = (type) => p.locator('.tp-a').evaluate((el, type) => {
      const r = el.getBoundingClientRect();
      el.dispatchEvent(new PointerEvent(type, {pointerId: 1, pointerType: 'touch', bubbles: true, cancelable: true,
                                               clientX: r.left + r.width / 2, clientY: r.top + r.height / 2}));
    }, type);
    await touch('pointerdown');
    // wait for the page to redraw the button as pressed
    const held = await until(async () => {
      const t = await p.locator('.tp-on').allTextContents();
      return t.includes('A') && t;
    }).catch(() => []);
    await touch('pointerup');
    check(held.includes('A'), `touch controller: shown on a phone, A presses (${held.join(' ')})`);
    await phone.close();
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
