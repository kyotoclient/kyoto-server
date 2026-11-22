// Kyoto in-game extras: daily login reward, weekly challenges, emotes + pets near you, live location
// sharing with friends, and proximity voice rooms per Minecraft server. Loaded by server.js after community.js.
'use strict';

module.exports = function extras(ctx) {
  const { routes, db, send, fail, nameOf, byName, areFriends, isBanned, save, q, SOCIAL } = ctx;
  const now = () => Date.now();
  const day = (t = now()) => new Date(t).toISOString().slice(0, 10);
  const addCoins = (u, n) => { db.coins = db.coins || {}; db.coins[u] = (db.coins[u] || 0) + n; };
  const hostOf = h => String(h || '').toLowerCase().replace(/[^a-z0-9.\-:]/g, '').slice(0, 60);

  /* ================================================================ daily login reward ================================================================ */
  db.daily = db.daily || {}; // uuid -> { last: 'YYYY-MM-DD', streak }
  routes['POST /daily'] = async (req, res, body, me) => {
    if (isBanned(me)) return fail(res, 403, 'Banned');
    const d = db.daily[me] || { last: '', streak: 0 }, today = day();
    if (d.last === today) return send(res, 200, { claimed: false, streak: d.streak, coins: (db.coins || {})[me] || 0 });
    const yesterday = day(now() - 86400000);
    d.streak = d.last === yesterday ? d.streak + 1 : 1; d.last = today; db.daily[me] = d;
    const reward = 10 + Math.min(d.streak - 1, 6) * 5 + (d.streak % 7 === 0 ? 50 : 0);
    addCoins(me, reward); save();
    send(res, 200, { claimed: true, reward, streak: d.streak, coins: db.coins[me] });
  };

  /* ================================================================ weekly challenges ================================================================ */
  // The game sends counters (only things it can see: blocks, kills, distance, minutes, fish). 3 challenges a week, the same for everyone.
  const POOL = [
    ['play', 'Play for 5 hours', 'mins', 300], ['play2', 'Play for 10 hours', 'mins', 600],
    ['mine', 'Mine 3,000 blocks', 'blocks', 3000], ['mine2', 'Mine 8,000 blocks', 'blocks', 8000],
    ['build', 'Place 2,000 blocks', 'placed', 2000], ['walk', 'Travel 25 km', 'dist', 25000],
    ['fight', 'Get 40 kills (mobs or players)', 'kills', 40], ['fight2', 'Get 150 kills (mobs or players)', 'kills', 150],
    ['fish', 'Catch 25 fish', 'fish', 25], ['jump', 'Travel 60 km', 'dist', 60000],
  ];
  const KEYS = ['mins', 'blocks', 'placed', 'dist', 'kills', 'fish'];
  function weekId(t = now()) { const d = new Date(t); d.setUTCHours(0, 0, 0, 0); d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7)); return d.toISOString().slice(0, 10); }
  function weekChallenges(w) {
    let h = 0; for (const c of w) h = (h * 31 + c.charCodeAt(0)) >>> 0;
    const pick = [], used = new Set();
    while (pick.length < 3) { h = (h * 1103515245 + 12345) >>> 0; const c = POOL[h % POOL.length]; if (!used.has(c[2])) { used.add(c[2]); pick.push(c); } }
    return pick;
  }
  db.weekly = db.weekly || {}; // uuid -> { week, p: {mins..}, done: [ids], bonus }
  function weeklyOf(u) {
    const w = weekId(); let s = db.weekly[u];
    if (!s || s.week !== w) s = db.weekly[u] = { week: w, p: {}, done: [], bonus: false };
    return s;
  }
  function weeklyOut(u) {
    const s = weeklyOf(u), ends = new Date(s.week + 'T00:00:00Z').getTime() + 7 * 86400000;
    return { week: s.week, ends, bonus: s.bonus, list: weekChallenges(s.week).map(c => ({ id: c[0], name: c[1], key: c[2], goal: c[3], have: Math.min(c[3], Math.floor(s.p[c[2]] || 0)), done: s.done.includes(c[0]), reward: 50 })) };
  }
  routes['GET /challenges'] = async (req, res, body, me) => send(res, 200, weeklyOut(me));
  const progRate = new Map();
  routes['POST /challenges/progress'] = async (req, res, body, me) => {
    const last = progRate.get(me) || 0; if (now() - last < 50000) return send(res, 200, Object.assign(weeklyOut(me), { finished: [] }));
    progRate.set(me, now());
    const s = weeklyOf(me);
    // caps per report (reports come every ~5 min): stops silly numbers
    const CAP = { mins: 12, blocks: 2500, placed: 2500, dist: 9000, kills: 300, fish: 40 };
    for (const k of KEYS) { const v = Math.max(0, Math.min(CAP[k], Number(body[k]) || 0)); if (v) s.p[k] = (s.p[k] || 0) + v; }
    const finished = [];
    for (const c of weekChallenges(s.week)) if (!s.done.includes(c[0]) && (s.p[c[2]] || 0) >= c[3]) { s.done.push(c[0]); addCoins(me, 50); finished.push(c[1]); }
    if (s.done.length >= 3 && !s.bonus) { s.bonus = true; addCoins(me, 150); finished.push('All 3 this week (+150 bonus)'); }
    save();
    send(res, 200, Object.assign(weeklyOut(me), { finished, coins: db.coins[me] || 0 }));
  };

  /* ================================================================ pets + emotes (seen by Kyoto players near you) ================================================================ */
  const PETS = ['none', 'fox', 'axolotl', 'bee', 'frog', 'cat', 'ghost', 'dragon', 'slime', 'star', 'bunny'];
  // 3D wardrobe pieces (hat / halo / back slots) the mod draws on players
  const WEAR = { hat: ['kasa', 'crown', 'cat_ears', 'bunny_ears', 'flower_crown', 'top_hat', 'beanie', 'goat_horns', 'sakura_horns'], halo: ['halo_gold', 'halo_sakura'], back: ['wings_sakura', 'wings_angel', 'wings_butterfly', 'backpack'] };
  const EMOTES = ['hi', 'gg', 'lol', 'love', 'cry', 'mad', 'wow', 'cool', 'sleep', 'think', 'party', 'fire', 'yes', 'no', 'w', 'l', 'clap', 'rip', 'pog', 'brb', 'gn', 'sus', 'flex', 'shy'];
  const EM = new Map(); // uuid -> { e, at }
  routes['POST /pet'] = async (req, res, body, me) => {
    const p = String(body.pet || 'none'); if (!PETS.includes(p)) return fail(res, 400, 'Unknown pet');
    const u = db.users[me]; if (u) { u.pet = p === 'none' ? '' : p; u.petName = String(body.name || '').replace(/[^\w ]/g, '').slice(0, 16); save(); }
    send(res, 200, { ok: true });
  };
  routes['POST /wear'] = async (req, res, body, me) => {
    const out = {};
    for (const slot of Object.keys(WEAR)) { const v = String((body.wear || {})[slot] || ''); if (WEAR[slot].includes(v)) out[slot] = v; }
    const u = db.users[me]; if (u) { u.wear = out; save(); }
    send(res, 200, { ok: true, wear: out });
  };
  // dance emotes (the mod animates the player model); they last longer than stickers
  const DANCES = ['wave', 'floss', 'groove', 'robot', 'cheer', 'bow', 'hype', 'shuffle', 'spin', 'clap', 'stop'].map(d => 'dance:' + d);
  routes['POST /emote'] = async (req, res, body, me) => {
    const e = String(body.e || ''); if (!EMOTES.includes(e) && !DANCES.includes(e)) return fail(res, 400, 'Unknown emote');
    const last = EM.get(me); if (last && now() - last.at < 1500) return fail(res, 429, 'Slow down');
    EM.set(me, { e, at: now() }); send(res, 200, { ok: true });
  };
  // the game sends the names of players it can see; we answer with pets + recent emotes of the ones that use Kyoto
  routes['GET /near'] = async (req, res, body, me) => {
    const names = String(q(req).get('names') || '').split(',').filter(n => /^[A-Za-z0-9_]{2,16}$/.test(n)).slice(0, 60);
    const pets = {}, emotes = {}, wear = {};
    for (const n of names) {
      const u = byName(n); if (!u || isBanned(u)) continue;
      const usr = db.users[u] || {};
      if (usr.pet) pets[usr.name || n] = { pet: usr.pet, name: usr.petName || '' };
      if (usr.wear && Object.keys(usr.wear).length) wear[usr.name || n] = usr.wear;
      const e = EM.get(u); if (e && now() - e.at < (String(e.e || '').startsWith('dance:') ? 30000 : 6000)) emotes[usr.name || n] = e;
    }
    send(res, 200, { pets, emotes, wear });
  };
  setInterval(() => { for (const [u, e] of EM) if (now() - e.at > 60000) EM.delete(u); }, 60000).unref();

  /* ================================================================ live location sharing with friends ================================================================ */
  // Only friends on the SAME server see you, only while you have it on, and it's gone 20 s after you stop.
  const POS = new Map(); // uuid -> { host, x, y, z, dim, at, with: 'friends' | [names] }
  routes['POST /live/pos'] = async (req, res, body, me) => {
    const host = hostOf(body.host);
    if (!host || body.off) { POS.delete(me); return send(res, 200, { ok: true }); }
    const n = v => Math.max(-3e7, Math.min(3e7, Math.round(Number(v) || 0)));
    const only = Array.isArray(body.only) ? body.only.filter(x => /^[A-Za-z0-9_]{2,16}$/.test(x)).slice(0, 20).map(x => x.toLowerCase()) : null;
    POS.set(me, { host, x: n(body.x), y: n(body.y), z: n(body.z), dim: String(body.dim || '').slice(0, 40), at: now(), only });
    send(res, 200, { ok: true });
  };
  routes['GET /live/friends'] = async (req, res, body, me) => {
    const host = hostOf(q(req).get('host')), out = [];
    const myName = String(nameOf(me)).toLowerCase();
    for (const [u, p] of POS) {
      if (u === me || p.host !== host || now() - p.at > 20000) continue;
      if (!areFriends(me, u)) continue;
      if (p.only && p.only.length && !p.only.includes(myName)) continue;
      out.push({ name: nameOf(u), x: p.x, y: p.y, z: p.z, dim: p.dim, age: now() - p.at });
    }
    send(res, 200, { friends: out });
  };
  setInterval(() => { for (const [u, p] of POS) if (now() - p.at > 60000) POS.delete(u); }, 30000).unref();

  /* ================================================================ proximity voice per server ================================================================ */
  routes['POST /voice/server'] = async (req, res, body, me) => {
    if (isBanned(me)) return fail(res, 403, 'Banned');
    const id = SOCIAL.serverVoiceRoom(body.host, me);
    if (!id) return fail(res, 400, 'Join a server first');
    send(res, 200, { id });
  };

  return { PETS, EMOTES };
};
