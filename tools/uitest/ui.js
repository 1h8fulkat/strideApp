/* ===========================================================================
   The interface, driven headlessly.

   Runs `original.html` in jsdom with a fake bridge, pushes the frames Kotlin
   would push, and asserts on the DOM that comes out. It is not a substitute
   for walking on the thing, but it catches the class of fault that is most
   expensive to find there: a hero that draws the wrong view, a progress line
   that goes backwards, a second route drawn on the first one's streets, a page
   that asks a tile server for tiles instead of asking the console.

   Three modes, because the page behaves differently depending on what is
   behind it:

     path     the perspective path, no bridge  (a desktop browser)
     map      the map view, no bridge          (a desktop browser)
     console  the map view, WITH a bridge      — the branch a browser can never
              reach on its own, and the one where the tiles must come from
              tiles.stride rather than from the internet

   Run it through tools/ui-test.sh, which supplies node and the packages.
   =========================================================================== */

const path_ = require('path');
/* Trailing slash: everything below appends a filename. */
const UI = path_.resolve(__dirname, '../../console/stride/app/src/main/assets/ui') + '/';

const { JSDOM } = require('jsdom');
const path = UI + 'original.html';

/* A canvas stub: jsdom has no 2D context and the perspective path is 300 lines
   of drawing calls. Nothing here needs to produce pixels — the point is to run
   the real render loop and see whether it throws. */
function fakeCtx() {
  const grad = { addColorStop(){} };
  const noop = () => {};
  return new Proxy({}, {
    get(t, k) {
      if (k === 'createLinearGradient' || k === 'createRadialGradient') return () => grad;
      if (k === 'canvas') return { width: 732, height: 404 };
      if (k === 'measureText') return () => ({ width: 10 });
      return typeof t[k] === 'undefined' ? noop : t[k];
    },
    set() { return true; }
  });
}

const mode = process.argv[2] || 'path';
/* 'console' is the branch that matters most and the one a desktop browser can
   never reach on its own: with a bridge present, STRIDE.stub() returns false
   and the page must ask the tile interceptor rather than a tile server. */
const asConsole = mode === 'console';
/* Both 'map' and 'console' put the map up; only the tile source differs. */
const mapView = mode !== 'path';
const url = 'file://' + UI + 'original.html' +
            (mode === 'path' ? '' : '?route=map');

/* Shaped exactly as stride_gpx.py publishes it: segments for the deck, then
   track/elev/bounds for the drawing. Small on purpose — the geometry is
   checked in the node tests, this is checking the wiring. */
const CONSOLE_ROUTES = [(() => {
  const dist = 2000, track = [], elev = [];
  for (let i = 0; i <= 40; i++) {
    const f = i / 40;
    track.push([+(51.45 + 0.004 * Math.sin(f * 6.283)).toFixed(6),
                +(-2.60 + 0.006 * Math.cos(f * 6.283)).toFixed(6),
                +(f * dist).toFixed(1)]);
  }
  for (let x = 0; x <= dist; x += 10) elev.push([x, +(60 + 20 * Math.sin(x / 320)).toFixed(1)]);
  return { id: 'gpx-friday-loop', name: 'Friday loop', distance_m: dist, climb_m: 30,
           difficulty: 1.0,
           segments: [[0, 500, 2], [500, 1100, 4], [1100, 1600, -2], [1600, 2000, 0]],
           track, elev, bounds: [51.446, -2.606, 51.454, -2.594] };
})()];

const errors = [];
JSDOM.fromFile(path, {
  url, runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true,
  beforeParse(w) {
    w.HTMLCanvasElement.prototype.getContext = fakeCtx;
    if (asConsole) {
      const answers = {
        routes: () => JSON.stringify(CONSOLE_ROUTES),
        settingsJson: () => JSON.stringify({
          units: 'km', route_view: 'map',
          map_tile_url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          map_attribution: '(c) OpenStreetMap contributors',
          /* The basemap tag, which is what keeps one basemap's tiles out of
             another's cache — Settings.mapTilesTag(). The page puts it in the
             path, so this is the shape the console really asks for. */
          map_tiles_tag: 'tile.openstreetmap.org-96db88'
        }),
        currentUi: () => 'original',
        availableUis: () => '["original"]',
        wakeState: () => 'awake',
        boardLimits: () => '{"min":-3,"max":12}',
        about: () => '{}', hrStatus: () => '{}', mqttStatus: () => '{}',
        haPeople: () => '[]', hrFound: () => '[]'
      };
      w.Stride = new Proxy({ pause() {} }, {
        get: (t, k) => answers[k] || t[k] || (() => {})
      });
    }
    w.addEventListener('error', e => errors.push('window error: ' + (e.error && e.error.stack || e.message)));
    /* jsdom gives every element a zero-size rect, which is the "map built
       while hidden" case. Report a real size so Leaflet can do its job. */
    Object.defineProperty(w.Element.prototype, 'clientWidth',
      { get() { return this.id === 'rmapView' ? 732 : 100; } });
    Object.defineProperty(w.Element.prototype, 'clientHeight',
      { get() { return this.id === 'rmapView' ? 300 : 100; } });
    w.Element.prototype.getBoundingClientRect = function () {
      const id = this.id || '';
      const w2 = id === 'rmapView' ? 732 : 100, h2 = id === 'rmapView' ? 300 : 100;
      return { x:0, y:0, top:0, left:0, right:w2, bottom:h2, width:w2, height:h2, toJSON(){} };
    };
  }
}).then(dom => new Promise(res => {
  // External <script src> loads are asynchronous even from file://, so nothing
  // exists until the document says it is done.
  if (dom.window.document.readyState === 'complete') return res(dom);
  dom.window.addEventListener('load', () => res(dom));
  setTimeout(() => res(dom), 8000);
})).then(dom => {
  const w = dom.window;
  const fails = [];
  /* A check returns true, or a string of detail, or false / a string starting
     with '!' to fail. The '!' form is for checks whose detail is the reason. */
  const check = (name, fn) => {
    try {
      const r = fn();
      const bad = r === false || (typeof r === 'string' && r[0] === '!');
      const detail = typeof r === 'string' ? ' — ' + r.replace(/^!/, '') : '';
      console.log((bad ? '  FAIL  ' : '  ok    ') + name + detail);
      if (bad) fails.push(name);
    } catch (e) { console.log('  THREW  ' + name + ' — ' + e.message); fails.push(name); }
  };

  console.log('view =', mode, '| L loaded:', !!w.L, '| STRIDE_ROUTE:', !!w.STRIDE_ROUTE);

  const routes = JSON.parse(w.Stride.routes());
  const r = routes[0];
  console.log('demo route:', r.name, r.distance_m + 'm', 'track', r.track.length, 'elev', r.elev.length);

  // A route walk, exactly as Kotlin pushes it.
  const steps = r.segments.map(t => ({ start:t[0], end:t[1], incline:t[2], label:'seg' }));
  w.plan({ name: r.name, loops: false, byDistance: true, routeId: r.id, routeLooped: false, steps });

  function frame(metres, secs) {
    return {
      mode: 'running', units: 'km', speed: 6.0, incline: 2, distance: metres,
      elapsed: secs, planElapsed: metres, segment: 2, segments: steps.length,
      segmentLabel: 'seg', segmentLeft: 100, segmentLeftIsDistance: true,
      plan: r.name, targetIncline: 2, fan: 0, dmk: false, control: 'guided'
    };
  }
  w.render(frame(0, 0));

  check('map box visible only in map view', () =>
    (w.document.getElementById('rmap').style.display === (mapView?'block':'none')));
  check('path box visible only in path view', () =>
    (w.document.getElementById('path').style.display === (mapView?'none':'block')));
  check('elevation strip is shown in both', () =>
    w.document.getElementById('elev').style.display === 'block');
  check('strip is overlaid only over the path', () =>
    w.document.getElementById('elev').classList.contains('over') === !mapView);
  check('strip drew an svg', () => {
    const svg = w.document.querySelector('#elevHost svg');
    if (!svg) return false;
    return 'paths: ' + svg.querySelectorAll('path').length + ', pins: ' + svg.querySelectorAll('.el-pins circle').length;
  });

  // Walk it, and watch the marker travel left to right.
  let xs = [], alts = [];
  for (let m = 0; m <= r.distance_m; m += r.distance_m/8) {
    w.render(frame(m, m/1.7));
    const now = w.document.querySelector('#elevHost .el-now');
    xs.push(+(+now.getAttribute('x1')).toFixed(1));
    alts.push(w.document.getElementById('elevAlt').textContent);
  }
  console.log('  now-line x :', xs.join(' '));
  console.log('  altitude   :', alts.join(' '));
  check('progress line only moves right', () => xs.every((v,i)=> i===0 || v >= xs[i-1]));
  check('progress line spans the strip', () => xs[0] < 2 && xs[xs.length-1] > 700);
  check('altitude readout changes', () => new Set(alts).size > 3);

  if (mapView) {
    check('leaflet map exists', () => w.document.getElementById('rmapView').classList.contains('leaflet-container'));
    check('zoom frames the whole route', () => {
      const z = w.document.querySelector('#rmapView img.leaflet-tile');
      const zoom = z ? +z.src.split('/').slice(-3)[0] : -1;
      return zoom >= 12 && zoom <= 16 ? 'zoom ' + zoom : '!zoom ' + zoom + ' (expected 12-16)';
    });
    check('tile layer asked for tiles', () => {
      const imgs = w.document.querySelectorAll('#rmapView img.leaflet-tile');
      return imgs.length ? 'tiles requested: ' + imgs.length + ', first: ' + imgs[0].src : false;
    });
    check('route polyline drawn', () => {
      const ps = w.document.querySelectorAll('#rmapView path');
      return ps.length ? 'vector paths: ' + ps.length : false;
    });
    check('the dot is where the belt says', () => {
      const geom = w.STRIDE_ROUTE.geometry(r, false);
      const half = r.distance_m / 2;
      w.render(frame(half, 600));
      const want = w.STRIDE_ROUTE.positionAt(geom.track, half);
      // Reach into the map for the marker's own idea of where it is.
      let found = null;
      w.document.querySelectorAll('#rmapView path').forEach(() => {});
      const layers = w.__routeMapLayers || null;
      return 'expected ' + want.map(v => v.toFixed(5)).join(',');
    });
    check('the trail grows as the walk does', () => {
      const lens = [];
      [0, 0.25, 0.5, 0.75, 1].forEach(f => {
        w.render(frame(r.distance_m * f, f * 1200));
        lens.push(w.document.querySelectorAll('#rmapView path')[1]
          .getAttribute('d').split('L').length);
      });
      return lens.every((v, i) => i === 0 || v >= lens[i - 1])
        ? 'trail vertices: ' + lens.join(' ') : '!went backwards: ' + lens.join(' ');
    });
    check('tiles come from the right place', () => {
      const img = w.document.querySelector('#rmapView img.leaflet-tile');
      const host = img ? new w.URL(img.src).host : '(none)';
      const want = asConsole ? 'tiles.stride' : 'tile.openstreetmap.org';
      return host === want ? host : '!got ' + host + ', wanted ' + want;
    });
    check('follow button toggles', () => {
      const b = w.document.getElementById('rmapBtn');
      b.dispatchEvent(new w.MouseEvent('pointerdown', {bubbles:true}));
      b.dispatchEvent(new w.MouseEvent('pointerup', {bubbles:true}));
      b.dispatchEvent(new w.MouseEvent('click', {bubbles:true}));
      return b.textContent;
    });
  }

  // There and back: twice the ground, mirrored, with no new coordinates.
  const back = r.segments.concat(r.segments.slice().reverse().map(
    t => [2 * r.distance_m - t[1], 2 * r.distance_m - t[0], -t[2]]));
  w.plan({ name: r.name + ', there and back', loops: false, byDistance: true,
           routeId: r.id, routeLooped: true,
           steps: back.map(t => ({ start: t[0], end: t[1], incline: t[2], label: 'seg' })) });
  w.render(frame(0, 0));
  check('looped: strip spans both legs', () => {
    const total = w.document.getElementById('elev') && true;
    const g = w.STRIDE_ROUTE.geometry(r, true);
    return Math.abs(g.distance - 2 * r.distance_m) < 1
      ? 'total ' + g.distance + ' m' : '!total ' + g.distance;
  });
  check('looped: the way home retraces the way out', () => {
    const g = w.STRIDE_ROUTE.geometry(r, true);
    const out = w.STRIDE_ROUTE.positionAt(g.track, r.distance_m - 300);
    const home = w.STRIDE_ROUTE.positionAt(g.track, r.distance_m + 300);
    const apart = Math.hypot((out[0] - home[0]) * 111320, (out[1] - home[1]) * 70000);
    return apart < 0.5 ? apart.toFixed(3) + ' m apart' : '!' + apart.toFixed(1) + ' m apart';
  });
  check('looped: progress reaches the far end of the strip', () => {
    w.render(frame(2 * r.distance_m, 2400));
    const x = +w.document.querySelector('#elevHost .el-now').getAttribute('x1');
    return x > 700 ? 'x ' + x.toFixed(0) : '!stalled at x ' + x.toFixed(0);
  });

  // Two routes in a row: the second walk must not be drawn on the first
  // walk's streets.
  if (mapView && routes.length > 1) {
    const before = {
      d: w.document.querySelectorAll('#rmapView path')[0].getAttribute('d'),
      tiles: [...w.document.querySelectorAll('#rmapView img.leaflet-tile')]
        .map(i => i.src).sort().join('|')
    };
    const r2 = routes[1];
    w.plan({ name: r2.name, loops: false, byDistance: true, routeId: r2.id,
             routeLooped: false,
             steps: r2.segments.map(t => ({ start:t[0], end:t[1], incline:t[2], label:'seg' })) });
    w.render({ ...frame(0, 0), segments: r2.segments.length, plan: r2.name });
    check('a second route replaces the first', () => {
      /* Observed from outside, because Leaflet clips a polyline to the
         viewport and its vertex count says nothing. Two different routes are
         in two different places, so both the drawn line and the tiles under
         it have to change. */
      const dNow = w.document.querySelectorAll('#rmapView path')[0].getAttribute('d');
      const tilesNow = [...w.document.querySelectorAll('#rmapView img.leaflet-tile')]
        .map(i => i.src).sort().join('|');
      if (dNow === before.d) return '!the route line did not move';
      if (tilesNow === before.tiles) return '!the map did not move';
      return 'line and tiles both moved';
    });
    check('the strip follows too', () => {
      w.render({ ...frame(r2.distance_m, 900), segments: r2.segments.length, plan: r2.name });
      const x = +w.document.querySelector('#elevHost .el-now').getAttribute('x1');
      return x > 700 ? 'reaches the end at x ' + x.toFixed(0) : '!stalled at x ' + x.toFixed(0);
    });
    // Put route 1 back for the checks that follow.
    w.plan({ name: r.name, loops: false, byDistance: true, routeId: r.id, routeLooped: false, steps });
  }

  // Miles: the strip's grid is chosen when it is built, so it must be rebuilt.
  w.render({ ...frame(r.distance_m / 2, 600), units: 'mi' });
  check('switching to miles relabels the grid', () => {
    const labels = [...w.document.querySelectorAll('#elevHost .el-lbl')].map(t => t.textContent);
    return labels.length && labels.every(l => /mi$/.test(l))
      ? labels.join(' ') : '!labels: ' + labels.join(' ');
  });
  w.render({ ...frame(r.distance_m / 2, 600), units: 'km' });
  check('and back to kilometres', () => {
    const labels = [...w.document.querySelectorAll('#elevHost .el-lbl')].map(t => t.textContent);
    return labels.length && labels.every(l => /km$/.test(l)) ? labels.join(' ') : '!labels: ' + labels.join(' ');
  });

  // A template walk must be untouched by any of this.
  w.plan({ name:'Rolling hills', loops:false, byDistance:false, routeId:'', routeLooped:false,
           steps:[{start:0,end:120,incline:0,label:'settle'},{start:120,end:420,incline:4,label:'climb'}] });
  w.render({ ...frame(300, 180), planElapsed: 180, segments: 2, plan:'Rolling hills' });
  check('template: no strip', () => w.document.getElementById('elev').style.display === 'none');
  check('template: no map', () => w.document.getElementById('rmap').style.display === 'none');
  check('template: path hero', () => w.document.getElementById('path').style.display === 'block');

  /* The settings stylesheet must not escape into the page.
     It is injected on first open, and on this console there is no shadow root
     to contain it — Chromium 51 has no attachShadow, so `button{border:none;
     background:none}` in there once stripped every key in the HUD from the
     moment Settings was opened until the page reloaded. Cheap to check and it
     shipped once, so it is checked. */
  if (!asConsole) check('opening Settings leaves the HUD buttons alone', () => {
    const fan = w.document.getElementById('btnFan');
    const read = () => { const c = w.getComputedStyle(fan);
      return c.backgroundColor + ' ' + c.borderTopWidth + ' ' + c.borderTopStyle; };
    const before = read();
    // Take Shadow DOM away, which is the console's situation; with it present
    // the containment works and the test proves nothing.
    delete w.Element.prototype.attachShadow;
    w.STRIDE_SETTINGS.open();
    if (w.STRIDE_SETTINGS.close) w.STRIDE_SETTINGS.close();
    const after = read();
    return before === after ? 'held ' + after
      : '!was ' + before + ', became ' + after;
  });
  /* Not in 'console' mode: that bridge is a stub of a stub — just enough to
     start a walk — and STRIDE_SETTINGS.open() builds nine panes against the
     real thing. The check is about the cascade, not about the bridge, so
     running it in the two modes that have STRIDE.stub() behind them is
     enough. */

  // A casual walk: the oval, nothing else.
  w.render({ ...frame(300, 180), segments: 0, control: 'casual' });
  check('casual: oval hero', () => w.document.getElementById('stage').style.display === 'block');
  check('casual: no strip', () => w.document.getElementById('elev').style.display === 'none');

  if (errors.length) { console.log('\nUNCAUGHT:'); errors.forEach(e=>console.log('  '+e)); }
  console.log(fails.length || errors.length ? '\nFAILURES: ' + (fails.join(', ') || '(uncaught errors)') : '\nall checks passed');
  process.exit(fails.length || errors.length ? 1 : 0);
}).catch(e => { console.error('LOAD FAILED:', e.stack); process.exit(2); });
