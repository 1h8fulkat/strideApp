/* ===========================================================================
   Nothing in the interface may use JavaScript this console cannot run.

   The ceiling is ES2015 — see the header in stride-core.js for how that was
   measured and why it is not the Chromium 83 the comment used to claim. A `?.`
   or an object spread parses fine everywhere it is likely to be typed and
   throws on the treadmill, where the failure is a blank screen on a machine
   with a motor attached.

   Two passes: acorn at ecmaVersion 2015 for syntax, and a scan for the things
   that parse at that level and simply do not exist in Chromium 51. Comments
   are stripped first, or every `/**` doc marker reads as an exponentiation
   operator.

   Run it through tools/ui-test.sh.
   =========================================================================== */

const fs=require('fs'), path=require('path');
const acorn=require('acorn');
const UI = require('path').resolve(__dirname, '../../console/stride/app/src/main/assets/ui') + '/';

/* Chromium 51 is ES2015-complete but nothing beyond. These are the things that
   parse in a modern engine and simply do not exist there. */
const RUNTIME = [
  [/\?\./g,                       'optional chaining ?. (Chrome 80)'],
  [/\?\?/g,                       'nullish coalescing ?? (Chrome 80)'],
  [/\.\.\.[A-Za-z_$][\w$]*\s*[,}]/g, 'possible object spread {...x} (Chrome 60)'],
  [/\basync\s+(function|\()/g,    'async function (Chrome 55)'],
  [/\bawait\s/g,                  'await (Chrome 55)'],
  [/Object\.(entries|values|fromEntries)\b/g, 'Object.entries/values (Chrome 54/73)'],
  [/\.padStart\(|\.padEnd\(/g,    'String.padStart (Chrome 57)'],
  [/\.replaceAll\(/g,             'String.replaceAll (Chrome 85)'],
  [/\.flat\(|\.flatMap\(/g,       'Array.flat (Chrome 69)'],
  [/\.at\(/g,                     'Array/String.at (Chrome 92)'],
  // Guarded uses are neutralised in strip(); anything left is a bare one.
  [/\bglobalThis\b/g, 'globalThis, unguarded (Chrome 71)'],
  [/[\w\)\]]\s*\*\*\s*[\w\(]/g,   'exponentiation ** (Chrome 52)'],
  [/\.finally\(/g,                'Promise.finally (Chrome 63)'],
  [/catch\s*\{/g,                 'optional catch binding (Chrome 66)'],
  [/\.matchAll\(/g,               'String.matchAll (Chrome 73)'],
  [/\.trimStart\(|\.trimEnd\(/g, 'trimStart/trimEnd (Chrome 66)'],
];

/* Comments are stripped first. Without it every `/**` doc marker reads as an
   exponentiation operator and the report is 70 false positives deep. */
function strip(code) {
  return code
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/^(\s*)\/\/.*$/gm, '$1')
    /* `"undefined" != typeof globalThis ? globalThis : this` is the standard
       UMD fallback and is safe on an engine that has no globalThis: `typeof`
       never throws on an undeclared name, and the guarded branch is never
       evaluated. Leaflet uses exactly this. Neutralised here so the audit can
       come out clean — and so a *bare* use of globalThis still shows up.
       Renamed rather than deleted: cutting the expression out orphans the
       `: this` half of the ternary and the file then fails to parse, which is
       a louder and more confusing lie than the one being fixed. */
    .replace(/(['\"])undefined\1\s*[!=]==?\s*typeof\s+globalThis\s*\?\s*globalThis/g,
             m => m.replace(/globalThis/g, '__guardedGlobal'))
    .replace(/typeof\s+globalThis\s*[!=]==?\s*(['\"])undefined\1\s*\?\s*globalThis/g,
             m => m.replace(/globalThis/g, '__guardedGlobal'));
}

function scan(name, raw) {
  const code = strip(raw);
  const problems = [];
  try { acorn.parse(code, { ecmaVersion: 2015, sourceType: 'script' }); }
  catch (e) {
    // Retry at a modern level to tell "syntax error" from "too new".
    try { acorn.parse(code, { ecmaVersion: 2022, sourceType: 'script' });
          problems.push('SYNTAX NEWER THAN ES2015: ' + e.message); }
    catch (e2) { problems.push('BROKEN: ' + e2.message); }
  }
  for (const [re, why] of RUNTIME) {
    const hits = code.match(re);
    if (hits) {
      const lines = code.split('\n').map((l,i)=>[i+1,l]).filter(([n,l])=>re.test(l)).slice(0,3);
      problems.push(why + ' ×' + hits.length + ' at line(s) ' + lines.map(([n])=>n).join(', '));
    }
  }
  console.log((problems.length ? 'X  ' : 'ok ') + name);
  problems.forEach(p => console.log('     ' + p));
  return problems.length;
}

/* ---- CSS this console cannot run either ----------------------------------
   The scan above is about JavaScript, and a `?.` at least throws where you can
   see it. CSS is worse: an unsupported property is *ignored*, silently, so a
   layout built on one looks exactly right on the desktop it was written on and
   almost right on the treadmill — spacing that came from somewhere else, a box
   whose caption has quietly rendered outside its own border.

   That is not hypothetical. The age prompt shipped on 21 September 2026 with
   `gap:18px` and no explicit heights, and what reached the console was a
   two-line caption sitting on top of the row of buttons underneath it. It read
   as overlapping buttons. The buttons were fine.

   Only the properties actually reached for and actually missing are listed.
   This is not a full Chromium 51 audit and does not pretend to be one. */
const CSS = [
  [/(^|[;{\s])(gap|row-gap|column-gap)\s*:/g,
   'flexbox gap (Chrome 84) — use margins'],
  [/position\s*:\s*sticky/g,
   'position:sticky (Chrome 56)'],
  [/:\s*(is|where)\s*\(/g,
   ':is() / :where() (Chrome 88)'],
  [/aspect-ratio\s*:/g,
   'aspect-ratio (Chrome 88)'],
  [/clamp\s*\(|min\s*\([^)]*,|max\s*\([^)]*,/g,
   'CSS clamp()/min()/max() (Chrome 79)'],
  [/inset\s*:/g,
   'inset shorthand (Chrome 87)'],
  [/backdrop-filter\s*:/g,
   'backdrop-filter (Chrome 76)'],
];

/* Three uses of `gap` in cluster.html predate this check.
   
   Grandfathered rather than fixed here, because fixing them is a change to a
   layout nobody has looked at on the machine and this commit is about a
   different screen. They are cosmetic — the affected rows get their spacing
   from `button{margin:0 8px}` instead and read as slightly tight — but they
   are real, and cluster.html is one of the five interfaces somebody may be
   using. Worth a pass of its own.
   
   Anything NOT in here fails. */
const CSS_KNOWN = { 'cluster.html': 3 };

function scanCss(name, raw) {
  /* Comments first, or a `/* … gap: … *\/` note reads as a declaration. */
  const css = raw.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '));
  let problems = [], total = 0;
  for (const [re, why] of CSS) {
    const hits = css.match(re);
    if (!hits) continue;
    total += hits.length;
    const lines = css.split('\n').map((l, i) => [i + 1, l])
      .filter(([, l]) => { re.lastIndex = 0; return re.test(l); })
      .slice(0, 3).map(([n]) => n);
    problems.push(why + ' ×' + hits.length + ' at line(s) ' + lines.join(', '));
  }
  const allowed = CSS_KNOWN[name] || 0;
  if (total <= allowed) {
    console.log('ok ' + name + ' (css)' +
      (allowed ? '  — ' + allowed + ' known, see CSS_KNOWN' : ''));
    return 0;
  }
  console.log('X  ' + name + ' (css)');
  problems.forEach(p => console.log('     ' + p));
  if (allowed) console.log('     ' + allowed + ' of these are grandfathered; the rest are new');
  return 1;
}

let bad = 0;
for (const f of ['stride-core.js','stride-route.js','stride-settings.js','vendor/leaflet.js']) {
  bad += scan(f, fs.readFileSync(UI+f,'utf8'));
}
/* stride-settings.js carries its own stylesheet as an array of strings, so it
   is scanned as CSS as well as as JavaScript. That is where the birthday
   picker's `gap` was. */
bad += scanCss('stride-settings.js', fs.readFileSync(UI+'stride-settings.js','utf8'));
for (const f of ['original.html','ember.html','cluster.html','daylight.html','pacer.html']) {
  const html = fs.readFileSync(UI+f,'utf8');
  const scripts = [...html.matchAll(/<script(?![^>]*src)[^>]*>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
  scripts.forEach((s,i) => bad += scan(f + ' (inline script ' + (i+1) + ')', s));
  const styles = [...html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map(m=>m[1]).join('\n');
  bad += scanCss(f, styles);
}
console.log(bad ? '\n' + bad + ' issue(s)' : '\nnothing past ES2015');
/* Exits non-zero, so this is a gate and not a report nobody reads. */
process.exitCode = bad ? 1 : 0;
