/* ===========================================================================
   The interface on an engine seven years older than the one you are reading
   this on.

   This console's WebView is Chromium 51.0.2704.91 — AOSP, shipped with the
   machine, no Play Store to update it. Two consequences are worth a test
   rather than a comment:

     * There is no `globalThis`. Leaflet's UMD wrapper reaches for it behind a
       `typeof` guard and is supposed to fall back to `this`. "Supposed to" is
       not evidence, and if it throws there is no map at all.

     * The hero box can be measured before it is laid out. Leaflet queues every
       layer added before the map has a view, and cannot be given one until the
       container has a size — so a map built a moment too early draws nothing,
       silently, with six polylines waiting inside it.

   Run it through tools/ui-test.sh.
   =========================================================================== */

const path_ = require('path');
const UI = path_.resolve(__dirname, '../../console/stride/app/src/main/assets/ui') + '/';

const { JSDOM } = require('jsdom');

/* Two things this console does that a modern browser does not:
   1. It has no `globalThis` (Chromium 51). Leaflet's UMD wrapper is meant to
      fall back to `this`, but "meant to" is not evidence, and if it throws
      there is no map at all.
   2. Its hero box can be measured before it is laid out, and Leaflet queues
      every layer added before it has a view — so a map that is built a moment
      too early draws nothing, silently, forever. */
let size = 0;   // the container reports nothing until the test says otherwise

JSDOM.fromFile(UI + 'original.html', {
  url: 'file://' + UI + 'original.html?route=map',
  runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true,
  beforeParse(w) {
    w.HTMLCanvasElement.prototype.getContext = () => new Proxy({}, {
      get: (t,k) => k === 'createLinearGradient' || k === 'createRadialGradient'
        ? () => ({addColorStop(){}}) : (t[k] === undefined ? ()=>{} : t[k]), set: ()=>true });
    Object.defineProperty(w.Element.prototype, 'clientWidth',
      { get() { return this.id === 'rmapView' ? size : 100; } });
    Object.defineProperty(w.Element.prototype, 'clientHeight',
      { get() { return this.id === 'rmapView' ? Math.round(size * 0.41) : 100; } });
    w.Element.prototype.getBoundingClientRect = function () {
      const n = this.id === 'rmapView' ? size : 100;
      return {x:0,y:0,top:0,left:0,right:n,bottom:n,width:n,height:n,toJSON(){}};
    };
    console.log('globalThis removed:', delete w.globalThis, '| typeof:', typeof w.globalThis);
  }
}).then(dom => new Promise(r => dom.window.addEventListener('load', () => r(dom))))
  .then(dom => {
    const w = dom.window;
    const fails = [];
    const check = (name, fn) => {
      let r; try { r = fn(); } catch (e) { r = '!threw ' + e.message; }
      const bad = r === false || (typeof r === 'string' && r[0] === '!');
      console.log((bad?'  FAIL  ':'  ok    ')+name+(typeof r==='string'?' — '+r.replace(/^!/,''):''));
      if (bad) fails.push(name);
    };

    check('Leaflet loads with no globalThis', () =>
      typeof w.L === 'object' && typeof w.L.map === 'function' ? 'version ' + w.L.version : false);

    const r = JSON.parse(w.Stride.routes())[0];
    const steps = r.segments.map(t => ({start:t[0],end:t[1],incline:t[2],label:'x'}));
    const frame = m => ({mode:'running',units:'km',speed:6,incline:2,distance:m,elapsed:m/1.7,
      planElapsed:m,segment:2,segments:steps.length,plan:r.name,control:'guided'});

    // The route arrives while the box still measures nothing.
    w.plan({name:r.name, byDistance:true, routeId:r.id, routeLooped:false, steps});
    w.render(frame(500));
    check('an unlaid-out box draws nothing rather than a wrong view', () =>
      w.document.querySelectorAll('#rmapView path').length === 0
        ? 'no layers, no view' : '!drew into a 0x0 box');
    check('the strip drew anyway', () =>
      w.document.querySelector('#elevHost svg') ? 'yes — it needs no layout' : false);

    // Now the box has a size, as it does one frame after being shown.
    size = 732;
    w.render(frame(1000));
    check('the map heals itself on the next frame', () => {
      const paths = w.document.querySelectorAll('#rmapView path').length;
      const tiles = w.document.querySelectorAll('#rmapView img.leaflet-tile').length;
      return paths >= 6 && tiles > 0
        ? paths + ' vector layers, ' + tiles + ' tiles' : '!still blank: ' + paths + ' layers';
    });
    check('and keeps tracking after healing', () => {
      const before = w.document.querySelectorAll('#rmapView path')[1].getAttribute('d');
      w.render(frame(2500));
      return w.document.querySelectorAll('#rmapView path')[1].getAttribute('d') !== before
        ? 'the trail moved' : '!the trail froze';
    });

    console.log(fails.length ? '\nFAILURES: ' + fails.join(', ') : '\nall checks passed');
    process.exit(fails.length ? 1 : 0);
  }).catch(e => { console.error('LOAD FAILED:', e.stack); process.exit(2); });
