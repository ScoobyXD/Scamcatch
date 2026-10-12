/* ScamCatch homepage: the vision map. People across the lower 48 on their phones. One scam at a time flies in from
   overseas, lands on a phone and takes it over (it turns red), and the card follows every one of them in four steps, as
   does the timeline beside the headline: 1. the scam message; 2. ScamCatch intervenes and warns the person; 3. reports
   fly to three icons in the middle of the country (a bank, the authorities, trusted contacts), and each card row shows
   "Sent" as its trace lands; 4. trace & investigate to help get back lost funds: a magenta-to-violet trail, labeled "Trace"
   at its start, hops from the phone straight back toward the ocean the scam came across, through three crypto wallets,
   and ends at a magnifying glass: investigate. Scams come across the Pacific (left) or the Atlantic (right), never from below. */
(() => {
  const stage = document.querySelector('.vision-map');
  if (!stage) return;
  const frame = stage.querySelector('.vision-frame');
  const map = frame.querySelector('.us-map');
  const canvas = frame.querySelector('canvas');
  const ctx = canvas.getContext('2d');
  const spot = stage.querySelector('.spot');
  const timeline = document.querySelector('.vision-head .timeline');   // steps 1 to 4 light up with the card's phase
  // step 3 shows the same bank, authorities and trusted contacts icons as the card's rows
  const trio = timeline && timeline.querySelector('.timeline-trio');
  if (trio) {
    trio.prepend(...[...spot.querySelectorAll('.spot-share li i')].map((icon) => {
      const copy = icon.cloneNode(true);
      copy.dataset.to = icon.closest('li').dataset.to;
      return copy;
    }));
  }
  const land = new Path2D(map.querySelector('.us-land').getAttribute('d'));
  const VW = 960, VH = 610;            // the map's viewBox
  const TAU = Math.PI * 2;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const scenarios = (window.ScamCatchDemo && window.ScamCatchDemo.scenarios) || {};

  // Metro areas in viewBox units, projected with the same Albers projection as the outline.
  const METROS = [[868,210],[73,359],[633,220],[503,508],[185,396],[852,232],[475,438],[710,402],[820,572],[84,38],[333,269],[907,160],[535,154],[594,299],[217,230],[660,351],[772,359],[709,201],[66,82],[140,324],[292,373],[462,375],[491,241],[21,256],[440,515],[290,123],[158,151],[478,111],[774,473],[810,339],[734,217],[516,291],[601,379],[608,499],[286,450],[339,237],[159,56],[371,171],[912,128],[776,177]];

  // The close-up card follows one person at a time. Scam text and alerts come from the demo's SCENARIOS (app.js).
  const STORIES = [
    { scam: 'romance', city: 'Phoenix, AZ', at: METROS[4] },
    { scam: 'false-charge', city: 'Atlanta, GA', at: METROS[7] },
    { scam: 'tech-support', city: 'Seattle, WA', at: METROS[9] },
    { scam: 'irs', city: 'Dallas, TX', at: METROS[6] },
    { scam: 'bank-favor', city: 'Chicago, IL', at: METROS[2] },
    { scam: 'celebrity', city: 'Miami, FL', at: METROS[8] },
    { scam: 'jury-duty', city: 'Los Angeles, CA', at: METROS[1] },
    { scam: 'lottery', city: 'Cleveland, OH', at: METROS[30] },
  ].filter((s) => scenarios[s.scam]);

  // Where reports go, in viewBox units around the geographic center of the lower 48 (Kansas). Same order as the card rows.
  const HUBS = [
    { id: 'bank', at: [390, 330], label: 'Bank' },
    { id: 'law', at: [470, 226], label: 'Authorities' },
    { id: 'contacts', at: [550, 330], label: 'Trusted contacts' },
  ];

  // The four steps, in seconds after the scam lands on the phone.
  const POSSESSED = 0.9;               // the phone stays taken over this long before ScamCatch catches the scam
  const CATCH = 0.6;                   // the catch
  const SHARE_AT = POSSESSED + CATCH + 0.9;                               // the reports start going out
  const SHARE_GAP = 0.4, SHARE_FLIGHT = 0.8;                              // seconds between reports, and each one's flight
  const TRACE_AT = SHARE_AT + 0.15 + SHARE_GAP * (HUBS.length - 1) + SHARE_FLIGHT + 0.65;   // tracing the crypto starts, half a second after the last report lands
  const HOP = 0.35;                    // seconds per hop: phone, three wallets, the investigation
  const TRACE_STOPS = [0.3, 0.53, 0.77, 1];      // each stop's share of the way from the phone to the map's edge (the last is the investigation)
  const STORY_END = TRACE_AT + 0.15 + TRACE_STOPS.length * HOP + 1.5;   // when the card hides
  const STORY_HOLD = STORY_END - POSSESSED - CATCH;                       // the ScamCatch logo stays up this long

  // The zone the three icons and their labels sit in, in viewBox units. No person is placed in it and the card avoids it.
  const HUB_ZONE = {
    l: Math.min(...HUBS.map((hb) => hb.at[0])) - 82, r: Math.max(...HUBS.map((hb) => hb.at[0])) + 82,
    t: Math.min(...HUBS.map((hb) => hb.at[1])) - 52, b: Math.max(...HUBS.map((hb) => hb.at[1])) + 66,
  };
  // a person stands at (x, y) by their feet and reaches about 40 units up and 25 to the side, counting the phone
  const nearHub = (x, y) => x + 18 > HUB_ZONE.l && x - 28 < HUB_ZONE.r && y > HUB_ZONE.t && y - 44 < HUB_ZONE.b;
  const COLORS = { person: '196,204,232', screen: '244,194,122', scam: '255,46,76', scamDeep: '255,122,31', catch: '122,176,255', trace: '236,56,255', traceDeep: '124,92,255' };

  const hash = (a, b) => { const s = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453; return s - Math.floor(s); };
  const ease = (p) => 1 - (1 - p) ** 3;
  // a report's flight: eases off the phone and still has speed when it hits the icon, so the landing reads as one moment
  const flightEase = (p) => 0.5 * p + 0.5 * p * p * (3 - 2 * p);
  const clamp01 = (v) => Math.max(0, Math.min(1, v));

  // People, in viewBox units, placed the same way on every visit: clustered around metros, spread across the country.
  const SEEDS = METROS.map(([x, y], i) => ({ x, y, delay: 0.1 + hash(i, 3) * 1.6 }));
  const people = (() => {
    const probe = document.createElement('canvas').getContext('2d');
    const out = STORIES.map((s) => ({ x: s.at[0], y: s.at[1] + 10, story: s }));
    for (let i = 1; out.length < 34 && i < 6000; i++) {
      let x, y;
      if (hash(i, 1) < 0.62) {
        const m = METROS[Math.floor(hash(i, 2) * METROS.length)];
        const r = 60 * Math.sqrt(hash(i, 3)), a = hash(i, 4) * TAU;
        x = m[0] + Math.cos(a) * r; y = m[1] + Math.sin(a) * r;
      } else { x = hash(i, 5) * VW; y = hash(i, 6) * VH; }
      if (!probe.isPointInPath(land, x, y) || !probe.isPointInPath(land, x, y - 26) || nearHub(x, y)) continue;
      if (out.some((p) => (p.x - x) ** 2 + (p.y - y) ** 2 < 80 * 80)) continue;
      out.push({ x, y });
    }
    for (const p of out) {
      let at = Infinity;
      for (const s of SEEDS) at = Math.min(at, s.delay + Math.hypot(s.x - p.x, s.y - p.y) / 70);
      p.appear = at + hash(p.x, p.y) * 0.3;
      p.seed = hash(p.y, p.x) * 100;
      p.ev = null;
    }
    return out.sort((a, b) => a.y - b.y);
  })();
  const storyPeople = STORIES.map((s) => people.find((p) => p.story === s));

  let w = 0, h = 0, k = 1, mx = 0, my = 0, dpr = 1, S = 18;
  let maxRight = 0;                    // the frame's right edge, short of the floating section index
  let sprites = {};
  let threats = [], sparks = [], links = [], clock = 0, nextStory = 1.4, storyIndex = 0, raf = 0, last = 0, onScreen = false;
  let shown = null;                    // the person the card is following

  const X = (x) => mx + x * k;
  const Y = (y) => my + y * k;

  function layout() {
    const st = frame.getBoundingClientRect();
    const sr = map.getBoundingClientRect();
    if (!st.width || !st.height) return false;
    w = st.width; h = st.height;
    const index = document.querySelector('.index');
    maxRight = index && getComputedStyle(index).display !== 'none' ? Math.min(w, index.getBoundingClientRect().left - st.left - 10) : w;
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    k = Math.min(sr.width / VW, sr.height / VH);
    mx = sr.left - st.left + (sr.width - VW * k) / 2;
    my = sr.top - st.top + (sr.height - VH * k) / 2;
    S = Math.max(13, Math.min(28, 38 * k));
    sprites = { screen: glow(COLORS.screen, 0.32), scam: glow(COLORS.scam, 0.65), catch: glow(COLORS.catch, 0.5), trace: glow(COLORS.trace, 0.6) };
    rasterLogo();
    if (shown) placeSpot(shown, true);
    return true;
  }

  function glow(rgb, alpha) {
    const r = Math.ceil(S * 1.2), c = document.createElement('canvas');
    c.width = c.height = r * 2 * dpr;
    const g = c.getContext('2d');
    g.scale(dpr, dpr);
    const grad = g.createRadialGradient(r, r, 0, r, r, r);
    grad.addColorStop(0, `rgba(${rgb},${alpha})`);
    grad.addColorStop(1, `rgba(${rgb},0)`);
    g.fillStyle = grad;
    g.fillRect(0, 0, r * 2, r * 2);
    c.r = r;
    return c;
  }
  const stamp = (sprite, x, y, scale = 1, alpha = 1) => {
    const r = sprite.r * scale;
    ctx.globalAlpha = alpha;
    ctx.drawImage(sprite, x - r, y - r, r * 2, r * 2);
    ctx.globalAlpha = 1;
  };

  // Where a person's phone is, in S units from the bottom center of the person: held at chest height on their left.
  const PHONE_DX = -0.38, PHONE_DY = -0.5;
  const phoneAt = (p) => [X(p.x) + S * PHONE_DX, Y(p.y) + S * PHONE_DY];

  // The ScamCatch logo, drawn crisp once per size so the canvas can pop it on every phone it saves.
  const logoImage = new Image();
  let logoTile = null;
  logoImage.onload = () => rasterLogo();
  logoImage.src = 'logo.svg';
  function rasterLogo() {
    if (!logoImage.complete || !logoImage.naturalWidth || !S) return;
    const size = Math.ceil(S * 1.3 * dpr);
    logoTile = document.createElement('canvas');
    logoTile.width = logoTile.height = size;
    logoTile.getContext('2d').drawImage(logoImage, 0, 0, size, size);
  }
  function drawLogo(x, y, size, alpha) {
    if (!logoTile) { drawAlert(x, y, size / (S * 0.8), alpha); return; }
    ctx.globalAlpha = alpha;
    ctx.drawImage(logoTile, x - size / 2, y - size / 2, size, size);
    ctx.globalAlpha = 1;
  }

  function roundRect(x, y, rw, rh, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y); ctx.arcTo(x + rw, y, x + rw, y + rh, r); ctx.arcTo(x + rw, y + rh, x, y + rh, r);
    ctx.arcTo(x, y + rh, x, y, r); ctx.arcTo(x, y, x + rw, y, r); ctx.closePath();
  }

  function drawPerson(p, t) {
    const show = clamp01((t - p.appear) / 0.5);
    if (show <= 0) return;
    const ev = p.ev;
    const since = ev ? t - ev.land : -1;
    const taken = ev && since >= 0 && since < POSSESSED;
    const caught = ev && since >= POSSESSED;
    const hold = STORY_HOLD;
    const warned = caught && since < POSSESSED + CATCH + hold;
    const jitter = taken ? Math.sin(t * 57 + p.seed) * S * 0.05 : 0;
    const lift = (1 - ease(show)) * S * 0.3;
    const x = X(p.x) + jitter, y = Y(p.y) - lift;
    const px = x + S * PHONE_DX, py = y + S * PHONE_DY;

    // the phone lights the person's face: warm normally, red once a scam takes it over, blue when ScamCatch has it
    if (taken) stamp(sprites.scam, px, py, 1.1 + Math.sin(t * 18 + p.seed) * 0.12, show);
    else if (warned) stamp(sprites.catch, px, py, 1, 1 - clamp01((since - POSSESSED - CATCH - hold + 0.6) / 0.6));
    else stamp(sprites.screen, px, py, 0.85, show);

    // the person: shoulders and head
    ctx.globalAlpha = show;
    const skin = taken ? `rgba(255,150,160,${0.6 + 0.3 * Math.sin(t * 22 + p.seed) ** 2})` : `rgba(${COLORS.person},.8)`;
    ctx.fillStyle = skin;
    ctx.beginPath();
    ctx.moveTo(x - S * 0.36, y);
    ctx.quadraticCurveTo(x - S * 0.36, y - S * 0.5, x, y - S * 0.5);
    ctx.quadraticCurveTo(x + S * 0.36, y - S * 0.5, x + S * 0.36, y);
    ctx.closePath(); ctx.fill();
    ctx.beginPath(); ctx.arc(x + S * 0.02, y - S * 0.76, S * 0.17, 0, TAU); ctx.fill();

    // the smartphone: dark body with a light rim, a lit screen, a camera notch, two lines of chat
    const fw = S * 0.36, fh = S * 0.62, fx = px - fw / 2, fy = py - fh / 2;
    ctx.fillStyle = '#0a0e1f';
    roundRect(fx, fy, fw, fh, S * 0.07); ctx.fill();
    ctx.strokeStyle = 'rgba(225,232,255,.6)';
    ctx.lineWidth = Math.max(0.8, S * 0.035);
    ctx.stroke();
    let screen = `rgba(${COLORS.screen},.95)`;
    if (taken) screen = `rgba(${COLORS.scam},${0.8 + 0.2 * Math.sin(t * 30 + p.seed) ** 2})`;
    else if (warned) screen = 'rgba(156,195,255,1)';
    const inset = Math.max(1, S * 0.045);
    ctx.fillStyle = screen;
    roundRect(fx + inset, fy + inset, fw - inset * 2, fh - inset * 2, S * 0.045); ctx.fill();
    ctx.fillStyle = 'rgba(10,14,31,.85)';
    roundRect(px - fw * 0.16, fy + inset * 1.6, fw * 0.32, Math.max(1, S * 0.045), S * 0.02); ctx.fill();
    ctx.fillStyle = 'rgba(10,14,31,.32)';
    ctx.fillRect(fx + inset * 2, py - fh * 0.06, fw * 0.5, Math.max(0.8, S * 0.04));
    ctx.fillRect(fx + inset * 2, py + fh * 0.1, fw * 0.36, Math.max(0.8, S * 0.04));
    // the hand holding it
    ctx.fillStyle = skin;
    ctx.beginPath(); ctx.ellipse(px + fw * 0.42, py + fh * 0.2, S * 0.09, S * 0.13, -0.3, 0, TAU); ctx.fill();
    ctx.globalAlpha = 1;

    if (!ev || since < 0) return;
    if (taken) {
      // the scam swirling around the phone it took over
      ctx.fillStyle = `rgba(${COLORS.scam},.9)`;
      for (let i = 0; i < 3; i++) {
        const a = t * 7 + p.seed + (i * TAU) / 3;
        ctx.beginPath(); ctx.arc(px + Math.cos(a) * S * 0.5, py + Math.sin(a) * S * 0.42, Math.max(1, S * 0.06), 0, TAU); ctx.fill();
      }
      return;
    }

    // ScamCatch catches it: the ScamCatch logo pops up over the phone and pulls the scam into it
    const b = since - POSSESSED;
    if (b >= CATCH + hold) return;
    const lx = px, ly = py - S * 1.05;
    let size = S * 0.85;
    if (b < CATCH * 0.5) size *= 1.55 * ease(b / (CATCH * 0.5));
    else if (b < CATCH) size *= 1.55 - 0.55 * clamp01((b - CATCH * 0.5) / (CATCH * 0.5));
    const fade = 1 - clamp01((b - CATCH - hold + 0.4) / 0.4);
    stamp(sprites.catch, lx, ly, size / (S * 0.85), fade);
    drawLogo(lx, ly, size, fade);
    const c = clamp01(b / CATCH);
    if (c < 1) {
      const e = ease(c);
      ctx.fillStyle = `rgba(${COLORS.scam},${1 - c * 0.5})`;
      ctx.beginPath(); ctx.arc(px + (lx - px) * e, py + (ly - py) * e, S * 0.13 * (1 - c * 0.8), 0, TAU); ctx.fill();
    } else if (!ev.popped) {
      ev.popped = true;
      for (let i = 0; i < 7; i++) sparks.push({ x: lx, y: ly, a: (i / 7) * TAU + Math.random() * 0.5, v: S * (1.8 + Math.random() * 1.6), start: t });
    }
  }

  /* ---- Reports to the bank, the authorities, and trusted contacts ---- */
  // One of the card person's reports: it flies from the ScamCatch logo over their phone to a hub, and the trace stays drawn
  // until the card moves on.
  function sendReport(p, hub, start, dur) {
    links.push({ p, hub, start, dur, until: p.ev.land + STORY_END });
  }
  const logoAt = (p) => { const [px, py] = phoneAt(p); return [px, py - S * 1.05]; };
  const hubSize = () => Math.max(24, S * 1.75);
  // the Authorities icon: the card row's SVG paths (index.html) in a 200 grid. An eagle spread across a shield, an olive
  // branch on each side. Paths marked data-ink are drawn in the tile's own color; one with a stroke is the eagle's outline.
  const LAW_ICON = [...spot.querySelectorAll('li[data-to="law"] svg path')].map((el) => ({
    path: new Path2D(el.getAttribute('d')), ink: el.hasAttribute('data-ink'), line: Number(el.getAttribute('stroke-width')) || 0,
  }));

  function drawLinks(t) {
    links = links.filter((l) => {
      const u = (t - l.start) / l.dur;
      if (u < 0) return true;
      if (u >= 1 && !l.landed) {
        l.landed = true;
        l.hub.hitAt = t;
        markSent(l.hub.id);
      }
      if (t >= l.until) return false;
      const [x0, y0] = logoAt(l.p), hx1 = X(l.hub.at[0]), hy1 = Y(l.hub.at[1]);
      const cx = (x0 + hx1) / 2, cy = Math.min(y0, hy1) - Math.hypot(hx1 - x0, hy1 - y0) * 0.22;
      // the trace ends at the edge of the icon's tile, where it visibly lands, not hidden under the tile at its center
      const tx = hx1 - cx, ty = hy1 - cy, tl = Math.hypot(tx, ty) || 1;
      const edge = (hubSize() / 2 + 2) / Math.max(Math.abs(tx / tl), Math.abs(ty / tl));
      const x1 = hx1 - (tx / tl) * edge, y1 = hy1 - (ty / tl) * edge;
      const fade = 1 - clamp01((t - l.until + 0.5) / 0.5);
      const e = flightEase(clamp01(u)), v = 1 - e;
      // the trace, drawn up to the report's head
      ctx.strokeStyle = `rgba(156,195,255,${0.75 * fade})`;
      ctx.lineWidth = 1.8;
      ctx.setLineDash([5, 4]);
      ctx.beginPath();
      ctx.moveTo(x0, y0);
      const steps = 24;
      for (let i = 1; i <= steps; i++) {
        const q = (e * i) / steps, w0 = 1 - q;
        ctx.lineTo(w0 * w0 * x0 + 2 * w0 * q * cx + q * q * x1, w0 * w0 * y0 + 2 * w0 * q * cy + q * q * y1);
      }
      ctx.stroke();
      ctx.setLineDash([]);
      if (u < 1) {
        const hx = v * v * x0 + 2 * v * e * cx + e * e * x1, hy = v * v * y0 + 2 * v * e * cy + e * e * y1;
        stamp(sprites.catch, hx, hy, 0.55, 1);
        ctx.fillStyle = '#e6eeff';
        ctx.beginPath(); ctx.arc(hx, hy, Math.max(2.6, S * 0.12), 0, TAU); ctx.fill();
      }
      return true;
    });
  }

  function drawHubs(t) {
    const s = hubSize();
    ctx.font = `600 ${Math.max(10, Math.min(14, S * 0.5))}px "Hanken Grotesk", system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    // labels only when no two in a row overlap (small screens, where the card below the map names the icons)
    const tws = HUBS.map((hub) => ctx.measureText(hub.label).width + 14);
    const labels = HUBS.every((a, i) => HUBS.every((b, j) => j <= i || a.at[1] !== b.at[1] || Math.abs(a.at[0] - b.at[0]) * k >= (tws[i] + tws[j]) / 2 + 6));
    for (const hub of HUBS) {
      const x = X(hub.at[0]), y = Y(hub.at[1]);
      const hit = clamp01((t - (hub.hitAt ?? -9)) / 0.7);
      stamp(sprites.catch, x, y, (s / S) * 0.9 * (1 + 0.25 * (1 - hit)), 0.55 + 0.45 * (1 - hit));
      if (hit < 1) {
        ctx.strokeStyle = `rgba(156,195,255,${1 - hit})`;
        ctx.lineWidth = 2;
        roundRect(x - s / 2 - 6 * ease(hit), y - s / 2 - 6 * ease(hit), s + 12 * ease(hit), s + 12 * ease(hit), s * 0.3); ctx.stroke();
      }
      const tile = hit < 0.5 ? '#5f8dff' : '#3d6df0';
      ctx.fillStyle = tile;
      roundRect(x - s / 2, y - s / 2, s, s, s * 0.26); ctx.fill();
      ctx.strokeStyle = 'rgba(255,255,255,.28)';
      ctx.lineWidth = 1;
      ctx.stroke();
      ctx.fillStyle = '#fff';
      GLYPHS[hub.id](x, y, s, tile);
      if (!labels) continue;
      const ty = y + s / 2 + Math.max(10, S * 0.5), tw = ctx.measureText(hub.label).width + 14, th = Math.max(16, S * 0.7);
      ctx.fillStyle = 'rgba(12,17,36,.9)';
      roundRect(x - tw / 2, ty - th / 2, tw, th, th / 2); ctx.fill();
      ctx.fillStyle = 'rgba(223,232,255,.95)';
      ctx.fillText(hub.label, x, ty + 0.5);
    }
    ctx.textBaseline = 'alphabetic';
  }

  // White glyphs on the hub tiles, drawn to the tile size s around (x, y).
  const GLYPHS = {
    bank(x, y, s) {
      ctx.beginPath(); ctx.moveTo(x - s * 0.33, y - s * 0.1); ctx.lineTo(x, y - s * 0.31); ctx.lineTo(x + s * 0.33, y - s * 0.1); ctx.closePath(); ctx.fill();
      ctx.fillRect(x - s * 0.31, y - s * 0.08, s * 0.62, s * 0.06);
      for (const dx of [-0.23, -0.077, 0.077, 0.23]) ctx.fillRect(x + s * dx - s * 0.034, y - s * 0.0, s * 0.068, s * 0.2);
      ctx.fillRect(x - s * 0.34, y + s * 0.22, s * 0.68, s * 0.08);
    },
    // an eagle head inside an olive wreath, drawn from the same path as the card row's icon (index.html)
    law(x, y, s, tile) {
      const k2 = (s * 0.94) / 200;
      ctx.save();
      ctx.translate(x - 100 * k2, y - 100 * k2);
      ctx.scale(k2, k2);
      ctx.lineJoin = 'round';
      for (const { path, ink, line } of LAW_ICON) {
        ctx.fillStyle = ink ? tile : '#fff';
        if (line) { ctx.strokeStyle = tile; ctx.lineWidth = line; ctx.stroke(path); }
        ctx.fill(path);
      }
      ctx.restore();
    },
    // a small group of people: two behind, one in front
    contacts(x, y, s) {
      const person = (cx, cy, k2) => {
        ctx.beginPath(); ctx.arc(cx, cy - s * 0.15 * k2, s * 0.085 * k2, 0, TAU); ctx.fill();
        ctx.beginPath();
        ctx.moveTo(cx - s * 0.15 * k2, cy + s * 0.14 * k2);
        ctx.quadraticCurveTo(cx - s * 0.15 * k2, cy - s * 0.03 * k2, cx, cy - s * 0.03 * k2);
        ctx.quadraticCurveTo(cx + s * 0.15 * k2, cy - s * 0.03 * k2, cx + s * 0.15 * k2, cy + s * 0.14 * k2);
        ctx.closePath(); ctx.fill();
      };
      ctx.globalAlpha = 0.72;
      person(x - s * 0.17, y + s * 0.02, 0.85);
      person(x + s * 0.17, y + s * 0.02, 0.85);
      ctx.globalAlpha = 1;
      ctx.strokeStyle = '#3d6df0';
      ctx.lineWidth = s * 0.05;
      ctx.beginPath(); ctx.arc(x, y - s * 0.06, s * 0.1, 0, TAU); ctx.stroke(); ctx.fill();
      ctx.beginPath();
      ctx.moveTo(x - s * 0.19, y + s * 0.27);
      ctx.quadraticCurveTo(x - s * 0.19, y + s * 0.07, x, y + s * 0.07);
      ctx.quadraticCurveTo(x + s * 0.19, y + s * 0.07, x + s * 0.19, y + s * 0.27);
      ctx.closePath(); ctx.stroke(); ctx.fill();
    },
  };

  function drawAlert(x, y, scale, alpha) {
    const bw = S * 0.62 * scale, bh = S * 0.46 * scale;
    ctx.globalAlpha = alpha;
    ctx.fillStyle = '#3d6df0';
    roundRect(x - bw / 2, y - bh / 2, bw, bh, bh * 0.32); ctx.fill();
    ctx.beginPath(); ctx.moveTo(x - bw * 0.22, y + bh / 2 - 0.5); ctx.lineTo(x - bw * 0.36, y + bh / 2 + S * 0.16 * scale); ctx.lineTo(x - bw * 0.02, y + bh / 2 - 0.5); ctx.fill();
    ctx.fillStyle = '#fff';
    ctx.fillRect(x - S * 0.035 * scale, y - bh * 0.3, S * 0.07 * scale, bh * 0.38);
    ctx.beginPath(); ctx.arc(x, y + bh * 0.25, S * 0.045 * scale, 0, TAU); ctx.fill();
    ctx.globalAlpha = 1;
  }

  // A scam in flight, in viewBox units: from overseas, across the Pacific (the left edge) or the Atlantic (the right
  // edge), never from below, to one person's phone. The side is picked first (usually the nearer ocean), then the card's
  // spot, clear of the trace that later runs straight back toward that side, then a route clear of the card and icons.
  function launch(p, t, dur) {
    const left = -mx / k, right = (w - mx) / k, top = -my / k, bottom = (h - my) / k;
    const ex = p.x + (S * PHONE_DX) / k, ey = p.y + (S * PHONE_DY) / k;
    const side = p.x < 360 ? 'west' : p.x > 620 ? 'east' : (Math.random() < 0.5 ? 'west' : 'east');
    const card = floating() ? spotBox(p, side) : null;
    const icons = { l: X(HUB_ZONE.l), r: X(HUB_ZONE.r), t: Y(HUB_ZONE.t), b: Y(HUB_ZONE.b) };
    let best = null;
    for (let i = 0; i < 6; i++) {
      const sx = side === 'west' ? left - 8 : right + 8;
      const sy = top + ((i + 0.25 + Math.random() * 0.5) / 6) * (bottom - top) * 0.85;   // spread along that edge
      const dx = ex - sx, dy = ey - sy;
      const bend = (0.14 + Math.random() * 0.14) * (side === 'west' ? -1 : 1);
      const q = { sx, sy, ex, ey, cx: sx + dx / 2 - dy * bend, cy: sy + dy / 2 + dx * bend };
      let score = Math.abs(dy) * 0.6 + Math.random() * 40;   // flatter routes read as coming from across the ocean
      for (let u = 0.04; u < 0.96; u += 0.04) {
        const [x, y] = along(q, u), px = X(x), py = Y(y);
        if (card && px > card.l - 14 && px < card.r + 14 && py > card.t - 14 && py < card.b + 14) score += 400;
        if (px > icons.l && px < icons.r && py > icons.t && py < icons.b) score += 60;
      }
      if (!best || score < best.score) best = { q, score };
    }
    const q = best.q;
    const speed = Math.max(190, w * 0.24);
    const flight = dur || Math.min(2.6, Math.max(1.1, (Math.hypot(q.ex - q.sx, q.ey - q.sy) * k) / speed));
    Object.assign(q, { start: t, dur: flight });
    p.ev = { land: t + flight, popped: false, q, side, card };   // side: the ocean the scam came across, where the trace heads
    threats.push(q);
  }

  // One scam at a time: nothing new starts while a scam is in flight or a phone is still red.
  const scamOnMap = () => threats.length > 0 || people.some((p) => p.ev && clock >= p.ev.land && clock < p.ev.land + POSSESSED);

  const along = (q, u) => {
    const v = 1 - u;
    return [v * v * q.sx + 2 * v * u * q.cx + u * u * q.ex, v * v * q.sy + 2 * v * u * q.cy + u * u * q.ey];
  };

  function drawThreat(q, u) {
    const u0 = Math.max(0, u - 0.3), n = 12;
    const enter = Math.min(1, u / 0.12);
    let [px, py] = along(q, u0);
    ctx.lineCap = 'round';
    // the tail runs from orange into red at the head, each piece over a soft glow
    const tail = COLORS.scamDeep.split(',').map(Number), head = COLORS.scam.split(',').map(Number);
    for (let i = 1; i <= n; i++) {
      const [nx, ny] = along(q, u0 + ((u - u0) * i) / n), f = i / n;
      const rgb = tail.map((c, j) => Math.round(c + (head[j] - c) * f)).join(',');
      [[3.2, 0.2], [1, 0.95]].forEach(([wide, alpha]) => {
        ctx.strokeStyle = `rgba(${rgb},${f * alpha * enter})`;
        ctx.lineWidth = (0.7 + f * 2.2) * wide;
        ctx.beginPath(); ctx.moveTo(X(px), Y(py)); ctx.lineTo(X(nx), Y(ny)); ctx.stroke();
      });
      px = nx; py = ny;
    }
    // the head is the scam message itself: a red chat bubble
    const hx = X(px), hy = Y(py);
    const bw = Math.max(7, S * 0.46), bh = bw * 0.72;
    stamp(sprites.scam, hx, hy, 0.8, enter);
    ctx.globalAlpha = enter;
    ctx.fillStyle = `rgb(${COLORS.scam})`;
    roundRect(hx - bw / 2, hy - bh / 2, bw, bh, bh * 0.38); ctx.fill();
    ctx.beginPath(); ctx.moveTo(hx - bw * 0.2, hy + bh / 2 - 0.5); ctx.lineTo(hx - bw * 0.34, hy + bh / 2 + bh * 0.35); ctx.lineTo(hx + bw * 0.02, hy + bh / 2 - 0.5); ctx.fill();
    ctx.fillStyle = 'rgba(255,236,232,.95)';
    for (let i = -1; i <= 1; i++) { ctx.beginPath(); ctx.arc(hx + i * bw * 0.24, hy, Math.max(0.8, bw * 0.07), 0, TAU); ctx.fill(); }
    ctx.globalAlpha = 1;
  }


  /* ---- Trace & investigate: straight back toward the ocean the scam came across ---- */


  // Step 4: trace & investigate. From the phone through three of the scammer's crypto wallets to a magnifying glass.
  function traceStops(p) {
    // from the phone, level across the map toward the side the scam came from; the magnifying glass sits near that edge,
    // with room for its label. The stops step up and down so the trail reads as hops.
    const [px, py] = phoneAt(p), m = S * 1.3;
    const edge = p.ev.side === 'west' ? m : maxRight - m;
    return TRACE_STOPS.map((f, i) => {
      const off = i === TRACE_STOPS.length - 1 ? 0 : (i % 2 ? 1 : -1) * S * 0.75;
      return [px + (edge - px) * f, Math.max(m, Math.min(h - m * 1.4, py + off))];
    });
  }
  // a dark pill with a magenta outline and text, for the trail's "Trace" start and "Investigate" end
  function tag(label, x, y) {
    ctx.font = `700 ${Math.max(10, Math.min(13, S * 0.46))}px "Hanken Grotesk", system-ui, sans-serif`;
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    const th = Math.max(16, S * 0.65), tw = ctx.measureText(label).width + 16;
    const tx = Math.max(tw / 2 + 2, Math.min(w - tw / 2 - 2, x)), ty = Math.max(th / 2 + 2, Math.min(h - th / 2 - 2, y));
    ctx.fillStyle = 'rgba(12,17,36,.92)';
    ctx.strokeStyle = `rgba(${COLORS.trace},.7)`;
    ctx.lineWidth = 1;
    roundRect(tx - tw / 2, ty - th / 2, tw, th, th / 2); ctx.fill(); ctx.stroke();
    ctx.fillStyle = `rgb(${COLORS.trace})`;
    ctx.fillText(label, tx, ty + 0.5);
    ctx.textBaseline = 'alphabetic';
  }
  function drawTrace(p, t) {
    const a = t - p.ev.land - TRACE_AT - 0.15;
    if (a < 0) return;
    const fade = 1 - clamp01((t - p.ev.land - STORY_END + 0.5) / 0.5);
    if (fade <= 0) return;
    const pts = [phoneAt(p), ...traceStops(p)];
    const done = Math.min(TRACE_STOPS.length, Math.floor(a / HOP));   // stops reached so far
    const part = done < TRACE_STOPS.length ? flightEase(clamp01((a - done * HOP) / HOP)) : 0;
    for (let i = 0; i < done; i++) traceDots[i].classList.add('is-lit');
    if (done === TRACE_STOPS.length && !p.ev.traced) { p.ev.traced = true; spot.classList.add('has-traced'); }
    // the trail so far: a soft glow under a dashed line that runs from magenta at the phone into violet at the edge
    const [ex, ey] = pts[pts.length - 1];
    const ink = ctx.createLinearGradient(pts[0][0], pts[0][1], ex, ey);
    ink.addColorStop(0, `rgba(${COLORS.trace},${fade})`); ink.addColorStop(1, `rgba(${COLORS.traceDeep},${fade})`);
    let head = null;
    if (part > 0) {
      const [x0, y0] = pts[done], [x1, y1] = pts[done + 1];
      head = [x0 + (x1 - x0) * part, y0 + (y1 - y0) * part];
    }
    const trail = [...pts.slice(0, done + 1), ...(head ? [head] : [])];
    ctx.strokeStyle = ink;
    ctx.lineCap = 'round';
    [[6, 0.22, []], [2.2, 1, [5, 4]]].forEach(([lw, alpha, dash]) => {
      ctx.globalAlpha = alpha; ctx.lineWidth = lw; ctx.setLineDash(dash);
      ctx.beginPath(); trail.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.stroke();
    });
    ctx.setLineDash([]); ctx.globalAlpha = 1;
    // its start, labeled once the trail sets off: below the line and past the person, on the side it heads to
    const [px, py] = pts[0];
    ctx.globalAlpha = fade * clamp01(a / 0.25);
    tag('Trace', px + (p.ev.side === 'west' ? -1 : 1) * S * 1.4, py + S * 0.95);
    ctx.globalAlpha = 1;
    // the wallets: small diamonds that light up as the trail reaches them
    for (let i = 0; i < TRACE_STOPS.length - 1; i++) {
      const [x, y] = pts[i + 1], lit = i < done, r = S * 0.2;
      if (lit) stamp(sprites.trace, x, y, 0.45, fade);
      ctx.globalAlpha = fade * (lit ? 1 : 0.55);
      ctx.fillStyle = lit ? `rgb(${COLORS.trace})` : '#10162b';
      ctx.strokeStyle = `rgb(${COLORS.trace})`;
      ctx.lineWidth = 1.4;
      ctx.beginPath(); ctx.moveTo(x, y - r); ctx.lineTo(x + r, y); ctx.lineTo(x, y + r); ctx.lineTo(x - r, y); ctx.closePath(); ctx.fill(); ctx.stroke();
      ctx.globalAlpha = 1;
    }
    // the investigation: a tile with a magnifying glass that lights up magenta into violet when the trail reaches it
    const es = Math.max(18, S * 0.95), got = done === TRACE_STOPS.length;
    const since = a - TRACE_STOPS.length * HOP;
    if (got) {
      stamp(sprites.trace, ex, ey, (es / S) * 0.8, fade);
      const ring = clamp01(since / 0.7);
      if (ring < 1) {
        ctx.strokeStyle = `rgba(${COLORS.trace},${1 - ring})`;
        ctx.lineWidth = 2;
        roundRect(ex - es / 2 - 6 * ease(ring), ey - es / 2 - 6 * ease(ring), es + 12 * ease(ring), es + 12 * ease(ring), es * 0.3); ctx.stroke();
      }
    }
    ctx.globalAlpha = fade * (got ? 1 : 0.6);
    const lit = ctx.createLinearGradient(ex - es / 2, ey - es / 2, ex + es / 2, ey + es / 2);
    lit.addColorStop(0, `rgb(${COLORS.trace})`); lit.addColorStop(1, `rgb(${COLORS.traceDeep})`);
    ctx.fillStyle = got ? lit : '#10162b';
    ctx.strokeStyle = got ? lit : `rgb(${COLORS.trace})`;
    ctx.lineWidth = 1.4;
    roundRect(ex - es / 2, ey - es / 2, es, es, es * 0.26); ctx.fill(); ctx.stroke();
    ctx.strokeStyle = got ? '#fff' : `rgb(${COLORS.trace})`;
    ctx.lineWidth = Math.max(1.4, es * 0.09);
    ctx.lineCap = 'round';
    ctx.beginPath(); ctx.arc(ex - es * 0.06, ey - es * 0.06, es * 0.19, 0, TAU); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(ex + es * 0.08, ey + es * 0.08); ctx.lineTo(ex + es * 0.24, ey + es * 0.24); ctx.stroke();
    ctx.globalAlpha = fade;
    if (got) {
      // its label once the trail gets there, below the tile unless that runs off the map
      const gap = es / 2 + Math.max(10, S * 0.45);
      tag('Investigate', ex, ey + gap + Math.max(16, S * 0.65) / 2 > h - 2 ? ey - gap : ey + gap);
    }
    ctx.globalAlpha = 1;
    // the money moving between stops
    if (head) {
      stamp(sprites.trace, head[0], head[1], 0.55, 1);
      ctx.fillStyle = '#ffe2ff';
      ctx.beginPath(); ctx.arc(head[0], head[1], Math.max(2.6, S * 0.12), 0, TAU); ctx.fill();
    }
  }

  function drawSparks(t) {
    sparks = sparks.filter((s) => {
      const age = t - s.start;
      if (age > 0.5) return false;
      const d = s.v * age * (1 - age);
      ctx.fillStyle = `rgba(170,205,255,${1 - age / 0.5})`;
      ctx.beginPath(); ctx.arc(s.x + Math.cos(s.a) * d, s.y + Math.sin(s.a) * d, Math.max(1, S * 0.06), 0, TAU); ctx.fill();
      return true;
    });
  }

  /* ---- The close-up card ---- */
  const floating = () => getComputedStyle(spot).position === 'absolute';
  let spotScale = 1;                   // the card shrinks to fit when the map is shorter than the card (short laptop screens)

  // Where the card goes for person p, in screen units, at its full height with every step unfolded.
  function spotBox(p, side) {
    // the card's full height, counting the steps that are still folded away (the catch's padding and border only show
    // once it opens: .55rem above and below, and 1px each)
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
    const folded = (el) => (el.offsetHeight < 4 ? el.scrollHeight + (el.matches('.spot-catch') ? rem * 1.1 + 2 : 0) : 0);
    const fullH = spot.offsetHeight + ['.spot-catch', '.spot-share', '.spot-trace'].reduce((sum, sel) => sum + folded(spot.querySelector(sel)), 0);
    const scale = Math.max(0.55, Math.min(1, (h - 2) / fullH));
    const cw = spot.offsetWidth * scale, ch = fullH * scale;
    const x = X(p.x), y = Y(p.y) - S * 0.6;
    const gap = S * 1.5;
    const box = { l: X(HUB_ZONE.l), r: X(HUB_ZONE.r), t: Y(HUB_ZONE.t), b: Y(HUB_ZONE.b) };
    const me = { l: x - S * 0.7, r: x + S * 0.6, t: y - S * 0.9, b: y + S * 0.8 };      // the person and their phone
    // the lane the trace runs along, from the phone straight toward the side the scam came from
    const [tx, ty] = phoneAt(p);
    const lane = side ? { l: side === 'west' ? 0 : tx, r: side === 'west' ? tx : w, t: ty - S * 1.6, b: ty + S * 1.6 } : null;
    const cover = (a, l, t) => Math.max(0, Math.min(l + cw, a.r) - Math.max(l, a.l)) * Math.max(0, Math.min(t + ch, a.b) - Math.max(t, a.t));
    const near = p.x < VW / 2 ? [x - gap - cw, x + gap] : [x + gap, x - gap - cw];
    // beside the person first, then centered just above or below them, then out at the edges of the map
    const minL = Math.min(0, spot.closest('.card').getBoundingClientRect().left - frame.getBoundingClientRect().left + 12);
    // and never under the floating section index on the right
    const maxR = maxRight;
    const centered = Math.max(minL, Math.min(maxR - cw, x - cw / 2));
    const lefts = [...near, centered, p.x < VW / 2 ? 0 : maxR - cw, p.x < VW / 2 ? maxR - cw : 0];
    const tops = [y - ch / 2, y + S * 1.2, y - S * 1.6 - ch, box.b, box.t - ch, 0, h - ch].map((t) => Math.max(0, Math.min(h - ch, t)));
    let best = null;
    lefts.forEach((l, i) => {
      if (l < minL || l + cw > maxR) return;   // the card may reach into the section's left margin
      tops.forEach((t, j) => {
        const score = (cover(box, l, t) + cover(me, l, t) + (lane ? cover(lane, l, t) : 0)) * 10 + i * 40 + Math.abs(t + ch / 2 - y) * 0.6 + Math.abs(l + cw / 2 - x) * 0.1 + j;
        if (!best || score < best.score) best = { l, t, score };
      });
    });
    if (!best) best = { l: Math.max(0, Math.min(maxR - cw, lefts[0])), t: Math.max(0, Math.min(h - ch, y - ch / 2)) };
    return { l: best.l, t: best.t, r: best.l + cw, b: best.t + ch, scale };
  }

  // Puts the card where launch() planned it, so the scam's route stays clear of it. After a resize, plans it again.
  function placeSpot(p, fresh) {
    if (!floating()) { spot.style.left = spot.style.top = spot.style.scale = ''; spotScale = 1; return; }
    const box = (!fresh && p.ev && p.ev.card) || spotBox(p, p.ev && p.ev.side);
    spotScale = box.scale;
    spot.style.scale = spotScale < 1 ? String(spotScale) : '';
    spot.style.left = `${Math.round(box.l)}px`;
    spot.style.top = `${Math.round(box.t)}px`;
  }

  const shareRows = Object.fromEntries([...spot.querySelectorAll('.spot-share li')].map((li) => [li.dataset.to, li]));
  const traceDots = [...spot.querySelectorAll('.trace-chain i')];
  function resetSent() {
    for (const id in shareRows) shareRows[id].classList.remove('is-sent');
    if (trio) for (const icon of trio.querySelectorAll('i')) icon.classList.remove('is-hit');
    traceDots.forEach((d) => d.classList.remove('is-lit'));
    spot.classList.remove('has-traced');
  }
  // a report lands: its card row shows Sent, and the matching icon in step 3 of the timeline hops and lights up
  function markSent(id) {
    if (shareRows[id]) shareRows[id].classList.add('is-sent');
    const icon = trio && trio.querySelector(`i[data-to="${id}"]`);
    if (icon) { icon.classList.remove('is-hit'); void icon.offsetWidth; icon.classList.add('is-hit'); }
  }

  function fill(p) {
    const s = scenarios[p.story.scam];
    const label = document.querySelector(`.scam[data-scenario="${p.story.scam}"] .scam-name`);
    spot.querySelector('.spot-city').textContent = p.story.city;
    spot.querySelector('.spot-kind').textContent = label ? label.textContent : '';
    spot.querySelector('.spot-msg').textContent = s.flagged;
    spot.querySelector('.spot-alert').textContent = s.alert.split(/(?<=\.)\s/)[0];
  }

  function follow(p, phase) {
    if (shown !== p) {
      spot.classList.add('is-resetting');           // swap in the new person with every section already folded
      spot.dataset.phase = phase;
      fill(p);
      resetSent();
      void spot.offsetHeight;
      spot.classList.remove('is-resetting');
      shown = p;
      placeSpot(p);
    }
    spot.dataset.phase = phase;
    spot.classList.add('is-shown');
    if (timeline) timeline.dataset.active = phase;
  }

  function updateSpot(t) {
    if (!shown || !shown.ev) return;
    const since = t - shown.ev.land;
    if (since < 0) return;
    if (since < POSSESSED) follow(shown, 'scam');
    else if (since < SHARE_AT) follow(shown, 'caught');
    else if (since < TRACE_AT) {
      follow(shown, 'shared');
      if (!shown.ev.shared) {
        shown.ev.shared = true;
        HUBS.forEach((hub, i) => {
          const at = t + 0.15 + i * SHARE_GAP;
          sendReport(shown, hub, at, SHARE_FLIGHT);
        });
      }
    } else if (since < STORY_END) follow(shown, 'traced');
    else { spot.classList.remove('is-shown'); if (timeline) delete timeline.dataset.active; }
  }

  function nextStoryLaunch() {
    const p = storyPeople[storyIndex++ % storyPeople.length];
    if (!p) return;
    launch(p, clock, 1.5);
    pending = p;
  }
  let pending = null;                  // the next person the card will follow, once the scam lands

  // a thin line from the card to the person it is following
  function drawLeader() {
    if (!shown || !spot.classList.contains('is-shown') || !floating()) return;
    const [px, py] = phoneAt(shown);
    const left = spot.offsetLeft, top = spot.offsetTop, cw = spot.offsetWidth * spotScale, ch = spot.offsetHeight * spotScale;
    const ex = left + cw / 2 < px ? left + cw : left;
    const ey = Math.max(top + 14, Math.min(top + ch - 14, py));
    ctx.strokeStyle = spot.dataset.phase !== 'scam' ? 'rgba(122,176,255,.65)' : `rgba(${COLORS.scam},.7)`;
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath(); ctx.moveTo(ex, ey); ctx.lineTo(px + (ex < px ? -S * 0.3 : S * 0.3), py); ctx.stroke();
    ctx.setLineDash([]);
  }

  function draw() {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    for (const p of people) drawPerson(p, clock);
    drawLinks(clock);
    drawHubs(clock);
    if (shown && shown.ev) drawTrace(shown, clock);
    threats = threats.filter((q) => {
      const u = (clock - q.start) / q.dur;
      if (u >= 1) return false;
      drawThreat(q, u ** 1.15);
      return true;
    });
    drawSparks(clock);
    drawLeader();
  }

  function tick(now) {
    clock += Math.min(0.05, Math.max(0, (now - last) / 1000));
    last = now;
    // every scam gets the card: the next one starts once the card has closed
    if (clock > nextStory && !pending && !spot.classList.contains('is-shown') && !scamOnMap()) { nextStoryLaunch(); nextStory = clock + STORY_END; }
    if (pending && clock >= pending.ev.land) { follow(pending, 'scam'); pending = null; }
    updateSpot(clock);
    draw();
    raf = requestAnimationFrame(tick);
  }

  function start() {
    if (raf || reduceMotion || document.hidden || !onScreen || !w) return;
    last = performance.now();
    raf = requestAnimationFrame(tick);
  }
  function stop() { cancelAnimationFrame(raf); raf = 0; }
  function restart() {
    clock = 0; nextStory = 1.4; storyIndex = 0; threats = []; sparks = []; links = []; shown = null; pending = null;
    for (const hub of HUBS) hub.hitAt = -9;
    for (const p of people) p.ev = null;
    resetSent();
    spot.classList.remove('is-shown');
  }

  // Reduced motion: one still frame with the card on one person and all four steps done.
  function still() {
    if (!layout()) return;
    clock = 30;
    people.forEach((p) => { p.appear = 0; p.ev = null; });
    const p = storyPeople[0];
    links = [];
    if (p) {
      launch(p, clock - STORY_END + 1 - 1.5, 1.5);
      threats = [];
      Object.assign(p.ev, { popped: true, shared: true });
      shown = null;
      follow(p, 'traced');
      HUBS.forEach((hub) => { links.push({ p, hub, start: 0, dur: 1, until: Infinity, landed: true }); markSent(hub.id); });
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    for (const q of people) drawPerson(q, clock);
    drawLinks(clock);
    drawHubs(clock);
    if (p) drawTrace(p, clock);
    drawLeader();
  }

  if (storyPeople[0]) fill(storyPeople[0]);  // so the card has its size before the first story lands

  new ResizeObserver(() => { if (reduceMotion) still(); else if (layout()) start(); }).observe(frame);

  new IntersectionObserver(([entry]) => {
    if (entry.isIntersecting) {
      if (!onScreen && !reduceMotion) restart();
      onScreen = true;
      start();
    } else {
      onScreen = false;
      stop();
    }
  }, { threshold: 0 }).observe(stage);

  document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));
})();
