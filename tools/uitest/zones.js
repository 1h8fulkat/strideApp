/* ===========================================================================
   The page's zone arithmetic, held to the same numbers as Kotlin's.

   `stride-core.js` carries its own copy of the maximum-heart-rate and zone
   formulas, and the copy is deliberate: the settings screen draws the zones of
   whichever person is being edited rather than of whoever is walking, and the
   five interfaces are developed standalone in a desktop browser with no bridge
   behind them. Neither can call into Kotlin.

   Two implementations of the same physiology is a thing that drifts. This file
   asserts the JavaScript side against exactly the values
   `HrZonesTest.kt` asserts the Kotlin side against — same ages, same resting
   rates, same expected floors. Change a boundary in one and this goes red
   until you have changed it in the other.

   What this cannot catch: a value both files get wrong the same way. That is
   what the doc comments and the worked examples in HrZones.kt are for.

   Run it through tools/ui-test.sh.
   =========================================================================== */

const fs = require('fs');
const path_ = require('path');
const UI = path_.resolve(__dirname, '../../console/stride/app/src/main/assets/ui') + '/';

/* stride-core.js is a plain script that assigns window.STRIDE, not a module.
   Evaluated against a minimal window rather than loaded in jsdom: nothing here
   touches the DOM, and a full document costs a second per run for no reason. */
const sandbox = { window: {}, document: { createElement: () => ({ style: {} }) } };
sandbox.window.document = sandbox.document;
new Function('window', 'document', fs.readFileSync(UI + 'stride-core.js', 'utf8'))
  (sandbox.window, sandbox.document);
const S = sandbox.window.STRIDE;

let fail = 0;
function check(label, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g === w) { console.log('  ok   ' + label); return; }
  console.log('  FAIL ' + label + '\n         got  ' + g + '\n         want ' + w);
  fail = 1;
}

console.log('maximum heart rate — Tanaka, 208 - 0.7 x age');
check('13',  S.maxPulse(13),  199);
check('25',  S.maxPulse(25),  191);   // 190.5 rounds up
check('40',  S.maxPulse(40),  180);
check('60',  S.maxPulse(60),  166);
check('100', S.maxPulse(100), 138);
check('meets 220-age at 40', S.maxPulse(40), 220 - 40);
check('6 beats above it at 60', S.maxPulse(60) - (220 - 60), 6);

console.log('an age the formula does not cover is no maximum at all');
check('0',   S.maxPulse(0),   0);
check('12',  S.maxPulse(12),  0);
check('101', S.maxPulse(101), 0);

console.log('floors — percent of maximum, no resting rate');
check('age 40', S.zoneFloorsFor(40, 0), [0, 90, 108, 126, 144, 162]);

console.log('floors — Karvonen, on the reserve');
check('age 40, resting 55', S.zoneFloorsFor(40, 55), [55, 118, 130, 143, 155, 168]);

console.log('an implausible resting rate is disbelieved, not used');
check('resting 29',  S.zoneFloorsFor(40, 29),  S.zoneFloorsFor(40, 0));
check('resting 101', S.zoneFloorsFor(40, 101), S.zoneFloorsFor(40, 0));

console.log('no age means no zones at all, not default ones');
check('age 0',  S.zoneFloorsFor(0, 55),  []);
check('age 12', S.zoneFloorsFor(12, 55), []);

const f = S.zoneFloorsFor(40, 0);
console.log('a pulse lands in the zone its floor says');
check('89',  S.zoneOf(89,  f), 0);
check('90',  S.zoneOf(90,  f), 1);   // exactly on a floor is in
check('107', S.zoneOf(107, f), 1);
check('108', S.zoneOf(108, f), 2);
check('154', S.zoneOf(154, f), 4);
check('162', S.zoneOf(162, f), 5);
check('200', S.zoneOf(200, f), 5);   // above max is still zone 5

/* The distinction the whole feature rests on. If this ever returns 0, a strap
   that drops out paints the live graph grey and the belt's targeting loop
   reads "you have stopped working" and speeds up. */
console.log('no reading is -1, not zone 0');
check('0',        S.zoneOf(0,   f),  -1);
check('-1',       S.zoneOf(-1,  f),  -1);
check('1',        S.zoneOf(1,   f),   0);
check('no floors', S.zoneOf(140, []), -1);

console.log('zoneColour leaves a no-reading alone rather than painting it grey');
check('no reading', S.zoneColour(0, f), '');
check('zone 0',     S.zoneColour(1, f), S.ZONES[0].colour);
check('zone 4',     S.zoneColour(154, f), S.ZONES[4].colour);

console.log('vo2 max — 15.3 x max / resting');
check('40/55', Math.round(S.vo2max(40, 55) * 100) / 100, 50.07);
check('40/75', Math.round(S.vo2max(40, 75) * 100) / 100, 36.72);
console.log('it needs both numbers');
check('no resting', S.vo2max(40, 0),  0);
check('no age',     S.vo2max(0,  55), 0);
check('bad resting', S.vo2max(40, 20), 0);

/* The palette is duplicated in HrZones.ZONE_COLOURS for anything Kotlin
   publishes. Read the Kotlin out of the source rather than restating it here:
   a third copy in a test file is a third thing to forget. */
console.log('the palette matches HrZones.ZONE_COLOURS');
const kt = fs.readFileSync(
  path_.resolve(__dirname, '../../console/stride/app/src/main/java/dev/stride/hud/HrZones.kt'),
  'utf8');
const block = /val ZONE_COLOURS = arrayOf\(([\s\S]*?)\)/.exec(kt);
if (!block) { console.log('  FAIL could not find ZONE_COLOURS in HrZones.kt'); fail = 1; }
else {
  const ktColours = block[1].match(/#[0-9a-f]{6}/g) || [];
  check('colours', S.ZONES.map(z => z.colour), ktColours);
}

console.log('the names match HrZones.ZONE_NAMES');
const nblock = /val ZONE_NAMES = arrayOf\(([\s\S]*?)\)/.exec(kt);
if (!nblock) { console.log('  FAIL could not find ZONE_NAMES in HrZones.kt'); fail = 1; }
else {
  const ktNames = (nblock[1].match(/"([^"]+)"/g) || []).map(s => s.slice(1, -1));
  check('names', S.ZONES.map(z => z.name), ktNames);
}

/* ASSUMED_AGE reaches the belt: it is what SKIP on the welcome screen sends,
   and a page and a console that disagree about it would build the walk's
   zones on one number and the summary's on another. */
console.log('ASSUMED_AGE matches HrZones.ASSUMED_AGE');
const a = /const val ASSUMED_AGE = (\d+)/.exec(kt);
check('value', S.ASSUMED_AGE, a ? parseInt(a[1], 10) : null);

console.log();
if (fail) { console.error('>>> zone arithmetic has drifted between Kotlin and the page'); }
else { console.log('the page and Kotlin agree'); }
process.exit(fail);
