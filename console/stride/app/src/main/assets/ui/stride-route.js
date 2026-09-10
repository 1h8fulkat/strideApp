/* ===========================================================================
   STRIDE route view — the map, and the elevation strip under it.

   Only ever used while a **recorded route** is being walked. A template is a
   shape in time with no coordinates and no recorded ground; there is nothing
   here it could draw.

   Two ideas hold the whole file up:

   1. THE DOT IS PLACED BY THE BELT, NOT BY GPS. There is no receiver in a
      treadmill and there never will be. What the console knows is metres
      travelled, and every point in a published route carries the distance at
      which it is reached — so a position is a binary search and a lerp. This
      is why the geometry has to arrive with a distance column and why nothing
      here ever tries to be clever about time.

   2. THE STRIP DRAWS THE GROUND THAT WAS WALKED, NOT THE GROUND THE DECK CAN
      OFFER. The line comes from the route's own elevation samples. The deck's
      version of that hill is averaged, quantised and clamped at -3%, and
      drawing that instead is how a 6% descent and a 3% descent end up looking
      identical. See the note in stride-core's profile().

   Written in the same plain style as stride-core: `var`, no arrow functions,
   nothing past Chromium 83. Leaflet is vendored in `vendor/` and loaded from
   the APK — local, like every other asset here.
   =========================================================================== */

(function (global) {
'use strict';

/* --------------------------------------------------------------------------
   1. GEOMETRY
   -------------------------------------------------------------------------- */

/**
 * The route as it will actually be walked.
 *
 * `looped` is the there-and-back the picker offers, and it is the reason this
 * function exists rather than the caller using `route.track` directly. The
 * return leg has no coordinates of its own — Route.outAndBack in Kotlin
 * reverses and inverts the *segments* and never touches the track — so metre
 * `2d - x` of the walk is standing exactly where metre `x` was, facing the
 * other way. Mirroring the columns here means everything downstream can go on
 * believing a route is one list of points with distances that only increase.
 *
 * @return {{track: Array, elev: Array, distance: number, bounds: Array}|null}
 */
function geometry(route, looped) {
  if (!route) return null;
  var track = route.track || [];
  var elev = route.elev || [];
  if (track.length < 2) return null;

  var oneWay = track[track.length - 1][2] || route.distance_m || 0;
  if (oneWay <= 0) return null;

  if (looped) {
    track = track.slice();
    for (var i = route.track.length - 2; i >= 0; i--) {
      var p = route.track[i];
      track.push([p[0], p[1], 2 * oneWay - p[2]]);
    }
    if (elev.length > 1) {
      elev = elev.slice();
      for (var j = route.elev.length - 2; j >= 0; j--) {
        var e = route.elev[j];
        elev.push([2 * oneWay - e[0], e[1]]);
      }
    }
  }

  var south = track[0][0], north = track[0][0];
  var west = track[0][1], east = track[0][1];
  for (var k = 1; k < track.length; k++) {
    if (track[k][0] < south) south = track[k][0];
    if (track[k][0] > north) north = track[k][0];
    if (track[k][1] < west) west = track[k][1];
    if (track[k][1] > east) east = track[k][1];
  }

  return {
    track: track,
    elev: elev,
    distance: looped ? oneWay * 2 : oneWay,
    bounds: [[south, west], [north, east]]
  };
}

/**
 * Where on the track you are at `m` metres, as [lat, lon].
 *
 * Binary search rather than a walk from the start: this is called five times a
 * second against a track that can be a thousand points long, and the walk was
 * measurably the most expensive thing on screen when the map first went on a
 * console.
 */
function positionAt(track, m) {
  var last = track.length - 1;
  if (!(m > track[0][2])) return [track[0][0], track[0][1]];
  if (m >= track[last][2]) return [track[last][0], track[last][1]];

  var lo = 0, hi = last;
  while (hi - lo > 1) {
    var mid = (lo + hi) >> 1;
    if (track[mid][2] > m) hi = mid; else lo = mid;
  }
  var a = track[lo], b = track[hi];
  var span = b[2] - a[2];
  var f = span > 0 ? (m - a[2]) / span : 0;
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
}

/**
 * The value of a [distance, value] series at `x`, interpolated.
 *
 * Exported because two things want it and neither should own it: the strip
 * plots the series, and the perspective path in `original.html` reads the same
 * numbers to decide how high the ground is a hundred metres ahead. Before this
 * existed that view integrated the deck's clamped segments instead, and drew a
 * different hill from the one in the strip six inches below it.
 */
function sampleAt(series, x) {
  if (!series || !series.length) return 0;
  if (x <= series[0][0]) return series[0][1];
  var last = series.length - 1;
  if (x >= series[last][0]) return series[last][1];
  var lo = 0, hi = last;
  while (hi - lo > 1) {
    var mid = (lo + hi) >> 1;
    if (series[mid][0] > x) hi = mid; else lo = mid;
  }
  var span = series[hi][0] - series[lo][0];
  var f = span > 0 ? (x - series[lo][0]) / span : 0;
  return series[lo][1] + (series[hi][1] - series[lo][1]) * f;
}

/* --------------------------------------------------------------------------
   2. THE MAP
   -------------------------------------------------------------------------- */

/**
 * A route on a basemap, with a dot on it.
 *
 * Deliberately not an interactive map. Every gesture is off: no drag, no pinch,
 * no double-tap zoom. A HUD that can be scrolled away from the route by a
 * sweaty palm mid-stride is worse than no map, and there is nothing to look for
 * off-screen anyway — the whole route is framed on arrival.
 *
 * One tap toggles between that overview and a close follow view, which is the
 * one thing worth having on a long route where the whole thing framed at once
 * makes the streets unreadable.
 *
 * @param el     the container element, already sized by CSS
 * @param opts   {tileUrl, attribution, followZoom, colours}
 */
function map(el, opts) {
  opts = opts || {};
  if (!global.L) return null;

  var colours = opts.colours || {};
  var ahead = colours.ahead || '#e2564a';
  var done = colours.done || '#39e0ff';

  var m = global.L.map(el, {
    zoomControl: false,
    attributionControl: true,
    dragging: false,
    touchZoom: false,
    scrollWheelZoom: false,
    doubleClickZoom: false,
    boxZoom: false,
    keyboard: false,
    tap: false,
    /* Leaflet's fade-in on every tile costs a composite per tile on this
       hardware and buys nothing on a map that does not move. */
    fadeAnimation: false,
    zoomAnimation: false,
    inertia: false
  });
  /* The OpenStreetMap credit stays — their terms ask for it and it belongs on
     screen. Leaflet's own "Leaflet" prefix goes: it is a link nothing on this
     console can follow, in a corner 732px wide. */
  m.attributionControl.setPrefix('');

  /* `https`, and the host does not exist.
     Nothing ever connects: Tiles.kt answers this from the WebView's
     shouldInterceptRequest, before DNS and before any socket. The scheme is
     https rather than http purely so no mixed-content or insecure-origin rule
     anywhere in the browser gets an opinion about it — there is no certificate
     to check because there is no connection to make. */
  var tiles = global.L.tileLayer(opts.tileUrl || 'https://tiles.stride/{z}/{x}/{y}.png', {
    minZoom: 3,
    maxZoom: 18,
    attribution: opts.attribution || '',
    /* The tiles come off the console's own disk cache more often than not, so
       there is no reason to wait for the pan to settle before asking. */
    updateWhenIdle: false,
    keepBuffer: 2
  }).addTo(m);

  var line = null, trail = null, startPin = null, endPin = null;
  var halo = null, dot = null;
  var geom = null, following = false, followZoom = opts.followZoom || 16;
  /* Whether the map has ever been given a view.
     Leaflet queues layers added before one — `whenReady` — so a map that never
     gets a centre and zoom draws nothing at all, silently, however many
     polylines have been added to it. And it cannot be given one until its
     container has a size. So this is retried on every frame until it takes,
     which is what makes the view survive being built a moment too early. */
  var framed = false;
  /* The last position drawn, so a frame that has not moved a metre can be
     skipped. -1 rather than 0: zero is a real position and the first frame of
     a walk has to draw. */
  var lastM = -1;

  /**
   * Frame the whole route, so the overview can always be restored.
   *
   * Does nothing if the container has no size yet, and says so. Leaflet reads
   * its size from the element's `clientWidth`, and fitting bounds into a box
   * of zero pins the zoom to `maxZoom` — a map of one street somewhere in the
   * middle of the route, with no way back. That is what a map built while its
   * screen was still hidden looks like, and it is worth failing to draw rather
   * than drawing that.
   */
  function frame() {
    if (!geom) return false;
    var size = m.getSize();
    if (!size.x || !size.y) {
      /* Leaflet caches the container size and only re-measures when
         `_sizeChanged` is set. The public way to set it is `invalidateSize()`,
         which returns early on a map that has no view yet — precisely the case
         here — so a box that has since been laid out stays remembered as 0x0
         and the map never draws. Setting the flag is what invalidateSize does
         once it gets past that guard.

         A private field, knowingly: Leaflet is vendored in this repo at a
         version we control, `getSize` has read this flag since 1.0, and the
         alternative is a map that silently never appears. */
      m._sizeChanged = true;
      size = m.getSize();
      if (!size.x || !size.y) return false;
    }
    /* Capped: a 400 m loop fitted to a 732px box would otherwise go to street
       level, where a treadmill's worth of ground is off the edge. */
    m.fitBounds(geom.bounds, { padding: [24, 24], maxZoom: 17, animate: false });
    framed = true;
    return true;
  }

  function setRoute(g) {
    geom = g;
    [line, trail, startPin, endPin, halo, dot].forEach(function (layer) {
      if (layer) m.removeLayer(layer);
    });
    line = trail = startPin = endPin = halo = dot = null;
    lastM = -1;
    framed = false;
    if (!geom) return;

    var latlngs = geom.track.map(function (p) { return [p[0], p[1]]; });

    /* The route, then the part of it already walked drawn over the top. The
       same idea as the lap oval on this interface: what is behind you is lit
       and what is ahead of you is not. */
    line = global.L.polyline(latlngs, {
      color: ahead, weight: 7, opacity: 0.9, lineJoin: 'round', lineCap: 'round'
    }).addTo(m);
    trail = global.L.polyline([latlngs[0]], {
      color: done, weight: 7, opacity: 0.95, lineJoin: 'round', lineCap: 'round'
    }).addTo(m);

    startPin = global.L.circleMarker(latlngs[0], {
      radius: 7, color: '#0b1533', weight: 3, fillColor: '#5be08a', fillOpacity: 1
    }).addTo(m);
    endPin = global.L.circleMarker(latlngs[latlngs.length - 1], {
      radius: 7, color: '#0b1533', weight: 3, fillColor: '#f2f6ff', fillOpacity: 1
    }).addTo(m);

    halo = global.L.circleMarker(latlngs[0], {
      radius: 20, stroke: false, fillColor: done, fillOpacity: 0.22,
      interactive: false
    }).addTo(m);
    dot = global.L.circleMarker(latlngs[0], {
      radius: 9, color: '#04091c', weight: 3, fillColor: done, fillOpacity: 1,
      interactive: false
    }).addTo(m);

    frame();
  }

  function setPosition(metres) {
    if (!geom || !dot) return;
    /* Still no view — the container had no size when the route arrived. Try
       again now: this runs five times a second, and the frame after the box is
       laid out is the one that succeeds. */
    if (!framed && !frame()) return;
    // Five frames a second against a belt reporting whole metres: below a
    // metre of movement there is nothing to redraw, and redrawing a polyline
    // is not free.
    if (lastM >= 0 && Math.abs(metres - lastM) < 1) return;
    lastM = metres;

    var here = positionAt(geom.track, metres);
    dot.setLatLng(here);
    halo.setLatLng(here);

    /* The walked part, rebuilt from the vertices already passed plus the exact
       point you are standing on. Rebuilt rather than extended because the belt
       can go backwards: END on a summary and START again is a walk that begins
       at zero on ground that was already lit. */
    var upto = [];
    for (var i = 0; i < geom.track.length; i++) {
      if (geom.track[i][2] > metres) break;
      upto.push([geom.track[i][0], geom.track[i][1]]);
    }
    upto.push(here);
    trail.setLatLngs(upto);

    if (following) m.setView(here, followZoom, { animate: false });
  }

  function setFollowing(on) {
    following = !!on;
    if (!geom) return;
    if (following) {
      m.setView(positionAt(geom.track, lastM < 0 ? 0 : lastM), followZoom, { animate: false });
    } else {
      frame();
    }
    return following;
  }

  return {
    setRoute: setRoute,
    setPosition: setPosition,
    following: function () { return following; },
    setFollowing: setFollowing,
    toggleFollow: function () { return setFollowing(!following); },
    /** Leaflet measures its container when the map is made. A map built while
     *  its screen was hidden therefore thinks it is 0x0, and this is the call
     *  that fixes it — see the note where the HUD shows the route view. */
    resize: function () { m.invalidateSize(false); if (!following) frame(); },
    /** Whether the map has a view and is therefore drawing anything. */
    framed: function () { return framed; },
    tiles: tiles,
    leaflet: m,
    destroy: function () { try { m.remove(); } catch (e) { } }
  };
}

/* --------------------------------------------------------------------------
   3. THE ELEVATION STRIP
   -------------------------------------------------------------------------- */

/**
 * The route's ground, end to end, with a line showing where you are on it.
 *
 * Drawn once per walk and then only moved: the path, the fill, the grid and the
 * labels are built when the route arrives, and a frame updates four attributes
 * and two strings. At five frames a second on this hardware that distinction
 * is the difference between a HUD and a slideshow.
 *
 * Styling belongs to the interface, not here — every element carries a class
 * and no colours are set in this file, because five interfaces that deliberately
 * look nothing like each other cannot share a palette.
 *
 * @param host  an element to fill with the strip's <svg>
 * @param opts  {width, height, dist(m)->string, unit, ticks}
 */
function strip(host, opts) {
  opts = opts || {};
  var W = opts.width || 732;
  var H = opts.height || 96;
  /* Room at the top for the marker and at the bottom for the km labels, so
     neither is clipped by the box and neither sits on the line. */
  var PAD_T = opts.padTop == null ? 14 : opts.padTop;
  var PAD_B = opts.padBottom == null ? 16 : opts.padBottom;
  var plotH = H - PAD_T - PAD_B;

  var prof = null, geom = null, svg = null, nodes = {};

  function svgEl(tag, attrs) {
    var e = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (var k in attrs) if (attrs.hasOwnProperty(k)) e.setAttribute(k, attrs[k]);
    return e;
  }

  /** Vertical grid every whole kilometre (or mile), labelled. */
  function ticks(g, total, toDisplay) {
    var stepM = opts.tickEvery || 1000;
    // A 400 m loop with a line every kilometre has no grid at all, and a
    // 30 km route with one has thirty. Neither is a grid worth drawing.
    while (total / stepM > 8) stepM *= 2;
    while (total / stepM < 2 && stepM > 100) stepM /= 2;
    for (var d = stepM; d < total; d += stepM) {
      var x = (d / total) * W;
      // A tick within a label's width of the right edge draws a "5 km" that
      // runs off the box as "5 k". Skip it: the end of the strip is the end of
      // the route, which needs no label.
      if (x > W - 38) continue;
      g.appendChild(svgEl('line', {
        'class': 'el-grid', x1: x.toFixed(1), y1: PAD_T,
        x2: x.toFixed(1), y2: PAD_T + plotH
      }));
      var t = svgEl('text', {
        'class': 'el-lbl', x: (x + 5).toFixed(1), y: H - 4
      });
      t.textContent = toDisplay(d);
      g.appendChild(t);
    }
  }

  /**
   * @param steps    plan steps, for the crest pins
   * @param g        geometry() output, for the elevation samples
   * @param toKm     formats a distance in metres for the x axis
   * @param toAlt    formats an altitude in metres for the y axis
   */
  function setRoute(steps, g, toKm, toAlt) {
    geom = g;
    host.innerHTML = '';
    nodes = {};
    if (!g || !g.elev || g.elev.length < 2) { prof = null; return; }

    prof = global.STRIDE.profile(steps, {
      width: W, height: PAD_T + plotH, headroom: PAD_T, byDistance: true,
      samples: g.elev
    });
    if (prof.empty) { prof = null; return; }

    svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H });

    var grid = svgEl('g', {});
    svg.appendChild(grid);
    ticks(grid, prof.total, toKm);

    // The ground, then the line over it, then the part already walked over
    // that. Order is the whole of the layering here.
    svg.appendChild(svgEl('path', { 'class': 'el-fill', d: prof.fill }));
    svg.appendChild(svgEl('path', { 'class': 'el-line', d: prof.d, fill: 'none' }));
    nodes.done = svgEl('path', {
      'class': 'el-done', d: prof.d, fill: 'none',
      pathLength: 1000, 'stroke-dasharray': '0 1000'
    });
    svg.appendChild(nodes.done);

    var pins = svgEl('g', { 'class': 'el-pins' });
    for (var i = 0; i < prof.pins.length; i++) {
      pins.appendChild(svgEl('circle', {
        r: 2.5, cx: prof.pins[i].x.toFixed(1), cy: prof.pins[i].y.toFixed(1)
      }));
    }
    svg.appendChild(pins);

    /* Where you are, twice: a line down the whole strip, which is what makes
       progress legible at a glance from three feet away, and a dot on the
       curve, which is what says how high up you are. */
    nodes.now = svgEl('line', {
      'class': 'el-now', x1: 0, y1: 2, x2: 0, y2: PAD_T + plotH
    });
    svg.appendChild(nodes.now);
    nodes.dot = svgEl('circle', { 'class': 'el-dot', r: 5, cx: 0, cy: PAD_T });
    svg.appendChild(nodes.dot);

    // Highest and lowest, so the axis says what it is worth. Placed inside the
    // plot rather than in a gutter: 732px of width is all hero and none of it
    // is going to an axis.
    var hiL = svgEl('text', { 'class': 'el-ax', x: 4, y: PAD_T + 10 });
    hiL.textContent = toAlt(prof.hi);
    svg.appendChild(hiL);
    var loL = svgEl('text', { 'class': 'el-ax', x: 4, y: PAD_T + plotH - 3 });
    loL.textContent = toAlt(prof.lo);
    svg.appendChild(loL);

    host.appendChild(svg);
  }

  function setPosition(metres) {
    if (!prof) return null;
    var m = Math.max(0, Math.min(prof.total, metres || 0));
    var p = prof.atMetres(m);
    nodes.done.setAttribute('stroke-dasharray', prof.dashAt(m).toFixed(1) + ' 1000');
    nodes.now.setAttribute('x1', p.x.toFixed(1));
    nodes.now.setAttribute('x2', p.x.toFixed(1));
    nodes.dot.setAttribute('cx', p.x.toFixed(1));
    nodes.dot.setAttribute('cy', p.y.toFixed(1));
    return { altitude: prof.valueAt(m), x: p.x, total: prof.total };
  }

  return {
    setRoute: setRoute,
    setPosition: setPosition,
    profile: function () { return prof; },
    ready: function () { return !!prof; }
  };
}

global.STRIDE_ROUTE = {
  geometry: geometry,
  positionAt: positionAt,
  sampleAt: sampleAt,
  map: map,
  strip: strip
};

})(window);
