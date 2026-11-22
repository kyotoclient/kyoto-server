// Kyoto Client server - online count, friends, shared waypoints, cosmetics.
// No dependencies. Needs Node.js 18+.  Run:  node server.js   (PORT env var optional)
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const DATA = process.env.DATA_DIR || path.join(__dirname, 'data');
const CAPES = path.join(DATA, 'capes');
const MEDIA = path.join(DATA, 'profile_media');
const SKINS = path.join(DATA, 'skins');
const HUB_IMG = path.join(DATA, 'hub_images');
const DM_IMG = path.join(DATA, 'dm_images');
fs.mkdirSync(DM_IMG, { recursive: true });
fs.mkdirSync(CAPES, { recursive: true });
fs.mkdirSync(SKINS, { recursive: true });
fs.mkdirSync(HUB_IMG, { recursive: true });
fs.mkdirSync(MEDIA, { recursive: true });
// Kyoto staff (crown + "Owner of Kyoto Client"). Change with the OWNERS env var: "name1,name2"
const OWNERS = (process.env.OWNERS || 'heffm').toLowerCase().split(',').map(s => s.trim()).filter(Boolean);

// ---------------- storage ----------------
const DB_FILE = path.join(DATA, 'db.json');
let db = { users: {}, tokens: {}, friends: {}, requests: {}, inbox: {}, cosmetics: {}, profiles: {} };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(DB_FILE, 'utf8'))); } catch (e) { /* first run */ }
let dirty = false;
setInterval(() => {
  if (!dirty) return;
  dirty = false;
  fs.writeFile(DB_FILE + '.tmp', JSON.stringify(db), err => { if (!err) fs.rename(DB_FILE + '.tmp', DB_FILE, () => {}); });
}, 10000);
const save = () => { dirty = true; };

const challenges = new Map(); // serverId -> expiry
const presence = new Map();   // uuid -> { lastSeen, server }
const ONLINE_MS = 60000;

// ---------------- helpers ----------------
function dashed(id) {
  const h = id.replace(/-/g, '').toLowerCase();
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
function isOnline(uuid) { const p = presence.get(uuid); return !!p && Date.now() - p.lastSeen < ONLINE_MS; }
function onlineCount() { let n = 0; for (const [u] of presence) if (isOnline(u)) n++; return n; }
function byName(name) {
  const lower = String(name || '').toLowerCase();
  for (const [uuid, u] of Object.entries(db.users)) if (u.name.toLowerCase() === lower) return uuid;
  return null;
}
function list(obj, key) { if (!obj[key]) obj[key] = []; return obj[key]; }
function areFriends(a, b) { return list(db.friends, a).includes(b); }

function send(res, code, body, type) {
  const data = Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body));
  res.writeHead(code, { 'Content-Type': type || 'application/json', 'Content-Length': data.length });
  res.end(data);
}
const fail = (res, code, msg) => send(res, code, { error: msg });

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => { size += c.length; if (size > limit) { reject(new Error('too big')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      const raw = Buffer.concat(chunks).toString('utf8');
      if (/application\/x-www-form-urlencoded/i.test(req.headers['content-type'] || '')) return resolve(Object.fromEntries(new URLSearchParams(raw)));
      try { resolve(JSON.parse(raw)); } catch (e) { reject(new Error('bad json')); }
    });
    req.on('error', reject);
  });
}

// simple rate limit: 240 requests / minute / IP
const hits = new Map();
function limited(ip) {
  const now = Date.now(); const h = hits.get(ip) || { n: 0, t: now };
  if (now - h.t > 60000) { h.n = 0; h.t = now; }
  h.n++; hits.set(ip, h); return h.n > 240;
}

// Website logins are signed tokens, so a device stays logged in even when the server restarts or redeploys
// (Render's free plan forgets saved files on every deploy). Set TOKEN_SECRET on Render to any long random text.
const TOKEN_SECRET = process.env.TOKEN_SECRET || process.env.ADMIN_KEY || crypto.randomBytes(32).toString('hex');
const b64u = b => Buffer.from(b).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
function signToken(uuid, name) {
  const body = b64u(JSON.stringify({ u: uuid, n: name, t: Date.now() }));
  return 'k2.' + body + '.' + b64u(crypto.createHmac('sha256', TOKEN_SECRET).update(body).digest());
}
function readSigned(tok) {
  const m = /^k2\.([\w-]+)\.([\w-]+)$/.exec(String(tok || ''));
  if (!m) return null;
  const want = b64u(crypto.createHmac('sha256', TOKEN_SECRET).update(m[1]).digest());
  if (want.length !== m[2].length || !crypto.timingSafeEqual(Buffer.from(want), Buffer.from(m[2]))) return null;
  try {
    const p = JSON.parse(Buffer.from(m[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString());
    if (!/^[0-9a-f-]{32,36}$/.test(p.u) || Date.now() - p.t > 400 * 86400000) return null; // good for ~a year
    if (db.revoked && db.revoked[m[2]]) return null;
    if (!db.users[p.u]) db.users[p.u] = { name: String(p.n || '?').slice(0, 16), joined: Date.now() }; // server forgot you: remember again
    return p.u;
  } catch (e) { return null; }
}
function auth(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  // <img> tags can't send headers: the staff panel passes ?auth=<token> for Hub images only
  const qa = !m && req.method === 'GET' && (req.url.startsWith('/hub/image') || req.url.startsWith('/dm/img') || req.url.startsWith('/store/photo')) ? new URL(req.url, 'http://x').searchParams.get('auth') : null;
  if (!m && !qa) return null;
  const tok = m ? m[1] : qa;
  const t = db.tokens[tok];
  if (t) return t.uuid;
  return readSigned(tok);
}

// ---------------- routes ----------------
const routes = {
  'GET /': async (req, res) => send(res, 200, { name: 'Kyoto Client server', online: onlineCount() }),
  'POST /overlay/link': async (req, res, body) => {
    const qs = String(body.q || '').replace(/^\?/, '').slice(0, 600);
    if (!/^[\w\-.~%=&,+]*$/.test(qs)) return fail(res, 400, 'Bad settings');
    const code = require('crypto').createHash('sha1').update(qs).digest('hex').slice(0, 7);
    db.overlays = db.overlays || {};
    if (!(code in db.overlays)) { if (Object.keys(db.overlays).length > 50000) return fail(res, 503, 'Try again later'); db.overlays[code] = qs; save(); }
    send(res, 200, { code });
  },
  'GET /stats': async (req, res) => send(res, 200, { online: onlineCount(), users: Object.keys(db.users).length }),

  // Login: the client tells Mojang "I'm joining <serverId>", we ask Mojang if that's true.
  'POST /auth/challenge': async (req, res) => {
    const serverId = crypto.randomBytes(16).toString('hex');
    challenges.set(serverId, Date.now() + 120000);
    send(res, 200, { serverId });
  },
  'POST /auth/verify': async (req, res, body) => {
    const { name, serverId } = body;
    const exp = challenges.get(serverId);
    if (!exp || exp < Date.now()) return fail(res, 400, 'challenge expired');
    challenges.delete(serverId);
    const r = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`);
    if (r.status !== 200) return fail(res, 401, 'Mojang could not verify you (are you using a real Minecraft account?)');
    const prof = await r.json();
    const uuid = dashed(prof.id);
    db.users[uuid] = { name: prof.name, joined: (db.users[uuid] && db.users[uuid].joined) || Date.now() };
    for (const [t, v] of Object.entries(db.tokens)) if (v.uuid === uuid && !v.web) delete db.tokens[t];
    const token = crypto.randomBytes(32).toString('hex');
    db.tokens[token] = { uuid, created: Date.now() };
    save();
    send(res, 200, { token, uuid, name: prof.name });
  },

  'POST /presence': async (req, res, body, me) => {
    presence.set(me, { lastSeen: Date.now(), server: String(body.server || '').slice(0, 80), join: String(body.join || '').slice(0, 80), calls: !!body.calls, song: String(body.song || '').slice(0, 80), artist: String(body.artist || '').slice(0, 60) });
    send(res, 200, { online: onlineCount() });
  },

  'GET /friends': async (req, res, body, me) => {
    const friends = list(db.friends, me).map(u => ({
      uuid: u, name: (db.users[u] || {}).name || '?', online: isOnline(u),
      server: isOnline(u) ? (presence.get(u) || {}).server || '' : '',
      join: isOnline(u) ? (presence.get(u) || {}).join || '' : '',
      calls: isOnline(u) ? !!(presence.get(u) || {}).calls : false
    }));
    const incoming = Object.entries(db.requests).filter(([from, tos]) => tos.includes(me)).map(([from]) => (db.users[from] || {}).name).filter(Boolean);
    const outgoing = list(db.requests, me).map(u => (db.users[u] || {}).name).filter(Boolean);
    send(res, 200, { friends, incoming, outgoing });
  },
  'POST /friends/request': async (req, res, body, me) => {
    const them = byName(body.name);
    if (!them) return fail(res, 404, `${body.name} hasn't used Kyoto Client on this server yet`);
    if (them === me) return fail(res, 400, "That's you!");
    if (areFriends(me, them)) return fail(res, 400, 'Already friends');
    if (list(db.requests, them).includes(me)) { // they already asked you -> accept
      db.requests[them] = db.requests[them].filter(u => u !== me);
      list(db.friends, me).push(them); list(db.friends, them).push(me);
    } else if (!list(db.requests, me).includes(them)) {
      db.requests[me].push(them);
    }
    save(); send(res, 200, { ok: true });
  },
  'POST /friends/accept': async (req, res, body, me) => {
    const them = byName(body.name);
    if (!them || !list(db.requests, them).includes(me)) return fail(res, 404, 'No request from that player');
    db.requests[them] = db.requests[them].filter(u => u !== me);
    if (!areFriends(me, them)) { list(db.friends, me).push(them); list(db.friends, them).push(me); }
    save(); send(res, 200, { ok: true });
  },
  'POST /friends/remove': async (req, res, body, me) => {
    const them = byName(body.name);
    if (!them) return fail(res, 404, 'Unknown player');
    db.friends[me] = list(db.friends, me).filter(u => u !== them);
    db.friends[them] = list(db.friends, them).filter(u => u !== me);
    db.requests[me] = list(db.requests, me).filter(u => u !== them);
    db.requests[them] = list(db.requests, them).filter(u => u !== me);
    save(); send(res, 200, { ok: true });
  },

  'POST /waypoints/send': async (req, res, body, me) => {
    const them = byName(body.to);
    if (!them) return fail(res, 404, `${body.to} hasn't used Kyoto yet - add them as a friend first`);
    if (!areFriends(me, them)) return fail(res, 403, 'You can only send waypoints to friends');
    const box = list(db.inbox, them);
    if (box.length > 50) box.shift();
    box.push({ from: db.users[me].name, name: String(body.name).slice(0, 32), x: body.x | 0, y: body.y | 0, z: body.z | 0, dim: String(body.dim || '').slice(0, 64) });
    save(); send(res, 200, { ok: true });
  },
  'GET /waypoints/inbox': async (req, res, body, me) => {
    const box = list(db.inbox, me); db.inbox[me] = []; if (box.length) save();
    send(res, 200, { waypoints: box });
  },

  'PUT /skin': async (req, res, body, me) => {
    const c = db.cosmetics[me] || (db.cosmetics[me] = { hasCape: false, capeType: 'png', accessory: 0 });
    if (body.remove) { c.hasSkin = false; }
    else {
      const buf = Buffer.from(String(body.skin || ''), 'base64');
      const isPng = buf.slice(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
      if (!isPng || buf.length > 200000) return fail(res, 400, 'Skin must be a PNG under 200 KB');
      // PNG width/height live at bytes 16-23
      const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20);
      if (w !== 64 || (h !== 64 && h !== 32)) return fail(res, 400, 'Skin must be 64x64');
      fs.writeFileSync(path.join(SKINS, me.replace(/[^a-f0-9-]/g, '')), buf);
      c.hasSkin = true;
    }
    c.slim = !!body.slim; c.skinVersion = Date.now();
    save(); send(res, 200, { ok: true });
  },

  'PUT /cosmetics': async (req, res, body, me) => {
    const c = db.cosmetics[me] || { hasCape: false, capeType: 'png', accessory: 0 };
    if (body.removeCape) {
      c.hasCape = false;
    } else if (body.cape) {
      const buf = Buffer.from(String(body.cape), 'base64');
      const isPng = buf.slice(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
      const isGif = buf.slice(0, 3).toString() === 'GIF';
      if (!isPng && !isGif) return fail(res, 400, 'Cape must be a PNG or GIF');
      if (buf.length > 4000000) return fail(res, 400, 'Cape file is too big (max 4 MB)');
      fs.writeFileSync(path.join(CAPES, me.replace(/[^a-f0-9-]/g, '')), buf);
      c.hasCape = true; c.capeType = isGif ? 'gif' : 'png';
    }
    c.accessory = Math.max(0, Math.min(40, body.accessory | 0));
    c.model = Math.max(0, Math.min(40, body.model | 0));
    c.version = Date.now();
    db.cosmetics[me] = c; save();
    send(res, 200, { ok: true });
  },
  'POST /cosmetics/bulk': async (req, res, body) => {
    const players = {};
    const kyoto = [];
    for (const u of (body.uuids || []).slice(0, 100)) {
      if (db.users[u]) kyoto.push(u);
      const c = db.cosmetics[u];
      if (c && (c.hasCape || c.accessory || c.model || c.hasSkin)) players[u] = c;
    }
    const kycos = {};
    for (const u of kyoto) if (db.kycos && db.kycos[u] && Object.keys(db.kycos[u].slots || {}).length) kycos[u] = db.kycos[u].version;
    send(res, 200, { players, kyoto, plus: kyoto.filter(isPlus), kycos });
  },
};

// ---------------- profiles ----------------
const MEDIA_TYPES = { mp4: 'video/mp4', webm: 'video/webm', gif: 'image/gif', mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg' };
const MEDIA_KINDS = { // kind -> allowed extensions, max bytes
  video: [['mp4', 'gif', 'webm'], 15000000], audio: [['mp3', 'wav', 'ogg'], 10000000],
  avatar: [['png', 'jpg', 'jpeg', 'gif'], 3000000], image: [['png', 'jpg', 'jpeg'], 6000000] };
function cleanStr(v, max) { return String(v || '').replace(/[\u0000-\u001f]/g, '').slice(0, max); }
function mediaFile(uuid, kind) { return path.join(MEDIA, `${uuid.replace(/[^a-f0-9-]/g, '')}_${kind}`); }
const int = (v, lo, hi, d) => { const n = Number(v); return Number.isFinite(n) ? Math.max(lo, Math.min(hi, Math.round(n))) : d; };
const hex = (v, d) => /^#[0-9a-f]{6}$/i.test(String(v || '')) ? String(v).toLowerCase() : d;
function hubBlocked(t) { return blocked(t) || FILTER.isBad(t); } // profiles + names: bad words aren't allowed at all
function linkSafe(l) {
  const u = String(l).toLowerCase();
  if (u.includes('.onion') || /^(https?:\/\/)?\d{1,3}(\.\d{1,3}){3}/.test(u)) return false;
  if (['bit.ly', 'tinyurl', 't.co/', 'cutt.ly', 'is.gd', 'rb.gy', 'shorturl', 'grabify', 'iplogger', '2no.co'].some(x => u.includes(x))) return false;
  return !blocked(u);
}
const LINK_RX = /^(https?:\/\/)?[\w.-]+\.[a-z]{2,}(\/\S*)?$/i;

// Profile text + look. Fields that aren't sent keep their old value, so the mod and the website can both edit.
routes['PUT /profile'] = async (req, res, body, me) => {
  if (isBanned(me)) return fail(res, 403, 'Banned');
  const old = db.profiles[me] || {};
  const p = Object.assign({}, old);
  const has = k => Object.prototype.hasOwnProperty.call(body, k);
  const textOk = t => !hubBlocked(t);
  if (has('displayName')) { const v = cleanStr(body.displayName, 24); if (!textOk(v)) return fail(res, 400, 'That name isn\'t allowed'); p.displayName = v; }
  if (has('bio')) { const v = cleanStr(body.bio, 300); if (!textOk(v)) return fail(res, 400, 'Your bio has a word that isn\'t allowed'); p.bio = v; }
  if (has('location')) { const v = cleanStr(body.location, 40); if (!textOk(v)) return fail(res, 400, 'That location isn\'t allowed'); p.location = v; }
  if (has('tags')) p.tags = (Array.isArray(body.tags) ? body.tags : []).slice(0, 8).map(t => cleanStr(t, 18)).filter(t => t && textOk(t));
  if (has('links')) p.links = (Array.isArray(body.links) ? body.links : []).slice(0, 8).map(l => cleanStr(l, 160)).filter(l => LINK_RX.test(l) && linkSafe(l));
  if (has('hue')) p.hue = int(body.hue, 0, 360, 262);
  if (has('nameEffect')) p.nameEffect = int(body.nameEffect, 0, 4, 1);
  if (has('bgEffect')) p.bgEffect = int(body.bgEffect, 0, 6, 1);
  if (has('accent')) p.accent = hex(body.accent, null);
  if (has('textColor')) p.textColor = hex(body.textColor, '#ffffff');
  if (has('layout')) p.layout = int(body.layout, 0, 2, 0);
  if (has('cardStyle')) p.cardStyle = int(body.cardStyle, 0, 3, 0);
  if (has('cardOpacity')) p.cardOpacity = int(body.cardOpacity, 0, 100, 62);
  if (has('bgDim')) p.bgDim = int(body.bgDim, 0, 90, 45);
  if (has('font')) p.font = ['poppins', 'inter', 'mono', 'serif', 'pixel'].includes(body.font) ? body.font : 'poppins';
  if (has('showViews')) p.showViews = !!body.showViews;
  if (has('avatarGlow')) p.avatarGlow = !!body.avatarGlow;
  if (has('cursorTrail')) p.cursorTrail = !!body.cursorTrail;
  if (has('typewriterBio')) p.typewriterBio = !!body.typewriterBio;
  if (has('showLinks')) p.showLinks = !!body.showLinks;
  if (has('showDiscord')) p.showDiscord = !!body.showDiscord;
  if (has('unlinkDiscord') && body.unlinkDiscord) delete p.discord;
  p.views = old.views || 0;
  p.updated = Date.now();
  db.profiles[me] = p;
  save(); send(res, 200, { ok: true });
};
routes['PUT /profile/media'] = async (req, res, body, me) => {
  const kind = Object.prototype.hasOwnProperty.call(MEDIA_KINDS, body.kind) ? body.kind : null;
  if (!kind) return fail(res, 400, 'kind must be video, audio, avatar or image');
  if (isBanned(me)) return fail(res, 403, 'Banned');
  const p = db.profiles[me] || (db.profiles[me] = { views: 0 });
  const file = mediaFile(me, kind);
  if (body.remove) {
    try { fs.unlinkSync(file); } catch (e) { /* none */ }
    p[kind + 'Ext'] = null; save(); return send(res, 200, { ok: true });
  }
  const ext = String(body.ext || '').toLowerCase();
  const [allowed, max] = MEDIA_KINDS[kind];
  if (!allowed.includes(ext)) return fail(res, 400, `${kind} must be ${allowed.join(', ')}`);
  const buf = Buffer.from(String(body.data || ''), 'base64');
  if (!buf.length || buf.length > max) return fail(res, 400, `File too big (max ${max / 1000000} MB)`);
  fs.writeFileSync(file, buf);
  p[kind + 'Ext'] = ext === 'jpeg' ? 'jpg' : ext;
  if (kind === 'audio') p.audioName = cleanStr(body.fileName, 60);
  if (kind === 'video' && p.imageExt) { try { fs.unlinkSync(mediaFile(me, 'image')); } catch (e) { /* none */ } p.imageExt = null; }
  if (kind === 'image' && p.videoExt) { try { fs.unlinkSync(mediaFile(me, 'video')); } catch (e) { /* none */ } p.videoExt = null; }
  p.updated = Date.now();
  save(); send(res, 200, { ok: true });
};

// ---------------- website login: one-time code from the game (/kyoto link) ----------------
const linkCodes = new Map(); // code -> { uuid, exp }
routes['POST /link/code'] = async (req, res, body, me) => {
  for (const [c, v] of linkCodes) if (v.uuid === me || v.exp < Date.now()) linkCodes.delete(c);
  let code; do { code = String(crypto.randomInt(100000, 1000000)); } while (linkCodes.has(code));
  linkCodes.set(code, { uuid: me, exp: Date.now() + 10 * 60000, tries: 0 });
  send(res, 200, { code, expiresIn: 600 });
};
const webFails = new Map();
routes['POST /web/login'] = async (req, res, body) => {
  const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
  const f = webFails.get(ip) || { n: 0, at: 0 };
  if (f.n >= 10 && Date.now() - f.at < 10 * 60000) return fail(res, 429, 'Too many wrong codes, wait 10 minutes');
  const code = String(body.code || '').replace(/\D/g, '');
  const name = String(body.name || '').trim().toLowerCase();
  const v = linkCodes.get(code);
  const uuid = v && v.exp > Date.now() ? v.uuid : null;
  if (!uuid || (name && db.users[uuid].name.toLowerCase() !== name)) {
    webFails.set(ip, { n: f.n + 1, at: Date.now() });
    return fail(res, 401, 'That code is wrong or expired. Type /kyoto link in Minecraft for a new one.');
  }
  linkCodes.delete(code); webFails.delete(ip);
  for (const [t, tv] of Object.entries(db.tokens)) if (tv.uuid === uuid && tv.web && Date.now() - tv.created > 30 * 86400000) delete db.tokens[t];
  const token = signToken(uuid, db.users[uuid].name);
  save();
  send(res, 200, { token, uuid, name: db.users[uuid].name });
};
routes['POST /web/logout'] = async (req, res, body, me) => {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  if (m && db.tokens[m[1]] && db.tokens[m[1]].web) delete db.tokens[m[1]];
  const sig = m && /^k2\.[\w-]+\.([\w-]+)$/.exec(m[1]);
  if (sig) { db.revoked = db.revoked || {}; db.revoked[sig[1]] = Date.now(); }
  save(); send(res, 200, { ok: true });
};
routes['GET /me'] = async (req, res, body, me) => {
  const u = db.users[me] || {};
  send(res, 200, { uuid: me, name: u.name, owner: OWNERS.includes(String(u.name || '').toLowerCase()), staff: isStaff(me), plus: isPlus(me), plusUntil: (db.plus[me] || {}).until || 0, coins: (db.coins || {})[me] || 0, profile: db.profiles[me] || {} });
};

// ---------------- Kyoto jars for the launcher (put files in ./jars as kyoto-<mc version>.jar) ----------------
const JARS = path.join(__dirname, 'jars');
// Jars too big for GitHub's web upload (25 MB) go in a GitHub Release instead; list their links in jars/links.json:
//   { "26.3": { "url": "https://github.com/you/kyoto-server/releases/download/v6.3.0/kyoto-26.3.jar", "version": "6.3.0" } }
function jarLinks() {
  try { return JSON.parse(fs.readFileSync(path.join(JARS, 'links.json'), 'utf8')); } catch (e) { return {}; }
}
function jarList() {
  let local = [];
  try { local = fs.readdirSync(JARS).map(f => /^kyoto-(.+)\.jar$/.exec(f)).filter(Boolean).map(m => m[1]); } catch (e) { /* none */ }
  return [...new Set([...Object.keys(jarLinks()), ...local])];
}
const jarHash = new Map(); // file -> { mtime, sha1, size }
routes['GET /mods/list'] = async (req, res) => {
  const files = {}, links = jarLinks();
  for (const v of jarList()) {
    const l = links[v];
    if (l && /^https:\/\//.test(l.url || '') && !/YOUR_GITHUB_NAME/.test(l.url)) { files[v] = { url: l.url, version: String(l.version || l.url) }; continue; }
    const f = path.join(JARS, `kyoto-${v}.jar`); if (!fs.existsSync(f)) continue;
    const st = fs.statSync(f);
    let h = jarHash.get(f);
    if (!h || h.mtime !== st.mtimeMs) { h = { mtime: st.mtimeMs, sha1: crypto.createHash('sha1').update(fs.readFileSync(f)).digest('hex'), size: st.size }; jarHash.set(f, h); }
    files[v] = { sha1: h.sha1, size: h.size };
  }
  send(res, 200, { versions: Object.keys(files), files, release: db.release });
};

// ---------------- Kyoto Hub: posts, setting presets, capes, public chat, DMs ----------------
db.hub = Object.assign({ posts: [], chat: [], dms: {}, reports: [], bans: [], blocks: {}, seq: 1 }, db.hub || {});
const H = db.hub;
const REQUIRE_APPROVAL = process.env.REQUIRE_APPROVAL !== '0'; // image posts wait for an owner to approve them
const EXTRA_BLOCKED = (process.env.BLOCKED_WORDS || '').toLowerCase().split(',').map(s => s.trim()).filter(Boolean);
const b64 = s => Buffer.from(s, 'base64').toString('utf8');
// adult / hateful words (base64 so the file doesn't spell them out) - any of these = message refused
const BLOCK = ['cG9ybg==', 'bnNmdw==', 'aGVudGFp', 'eHh4', 'b25seWZhbnM=', 'bnVkZXM=', 'bnVkZQ==', 'c2V4', 'Y29jaw==', 'cHVzc3k=', 'ZGljaw==',
  'Y3Vt', 'Ym9vYnM=', 'bmlnZ2Vy', 'bmlnZ2E=', 'ZmFnZ290', 'ZmFn', 'cmV0YXJk', 'a3lz', 'a2lsbCB5b3Vyc2VsZg==', 'dHJhbm55', 'Y2hpbms=',
  'c3BpYw==', 'a2lrZQ==', 'd2V0YmFjaw==', 'Y29vbg==', 'cmFwZQ=='].map(b64).concat(EXTRA_BLOCKED);
const MASK = ['ZnVjaw==', 'c2hpdA==', 'Yml0Y2g=', 'YXNz', 'ZGFtbg==', 'Y3JhcA==', 'YmFzdGFyZA==', 'd2hvcmU=', 'c2x1dA=='].map(b64);
const LEET = { a: '4@', e: '3', i: '1!|', o: '0', s: '5$', t: '7', g: '9' };
const wordRx = w => new RegExp('(?<![a-z])' + [...w].map(c => c === ' ' ? '\\s+' : '[' + c.replace(/[\]\\^-]/g, '\\$&') + (LEET[c] || '').replace(/[\]\\^-]/g, '\\$&') + ']+').join('') + '(?![a-z])', 'i');
const BLOCK_RX = BLOCK.map(wordRx), MASK_RX = MASK.map(w => new RegExp(wordRx(w).source, 'gi'));
const SAFE_SITES = ['youtube.com', 'youtu.be', 'twitch.tv', 'modrinth.com', 'github.com', 'minecraftcapes.net', 'namemc.com', 'minecraftskins.com',
  'curseforge.com', 'minecraft.net', 'kick.com', 'x.com', 'twitter.com', 'tiktok.com', 'instagram.com', 'guns.lol', 'pastebin.com'];
const URL_RX = /\b((?:https?:\/\/|www\.)\S+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|gg|tv|io|me|co|xyz|ru|onion|link|ly|to|cc|lol|app|dev|site|online|live|club|info|biz|us|uk|de|fr)(?:\/\S*)?)/gi;
function safeLink(u) {
  u = u.toLowerCase();
  if (u.includes('.onion') || /^(https?:\/\/)?\d{1,3}(\.\d{1,3}){3}/.test(u)) return false;
  if (['bit.ly', 'tinyurl', 't.co/', 'cutt.ly', 'is.gd', 'rb.gy', 'shorturl', 'grabify', 'iplogger', '2no.co'].some(x => u.includes(x))) return false;
  const host = u.replace(/^https?:\/\//, '').replace(/^www\./, '').split('/')[0];
  return SAFE_SITES.some(s => host === s || host.endsWith('.' + s));
}
const FILTER = require('./filter');
// Bad words don't block a message any more: they're tagged out with #### (filter.js catches "F u c k", f.u.c.k, f*ck, sh1t...).
// Only dark-web links still get a message refused.
function blocked(t) {
  return String(t || '').toLowerCase().includes('.onion');
}
function clean(t, max) {
  let s = String(t || '').replace(/[\u0000-\u001f\u202e\u200b-\u200f\u2060\ufeff]/g, '').slice(0, max).trim();
  s = s.replace(URL_RX, m => safeLink(m) ? m : '[link removed]');
  return FILTER.tag(s);
}
const nameOf = u => (db.users[u] || {}).name || '?';
const isOwnerUuid = u => OWNERS.includes(nameOf(u).toLowerCase());
H.staff = H.staff || []; H.banInfo = H.banInfo || {}; H.mutes = H.mutes || {}; H.audit = H.audit || [];
const isStaff = u => isOwnerUuid(u) || H.staff.includes(u);
function isBanned(u) {
  if (!H.bans.includes(u)) return false;
  const b = H.banInfo[u];
  if (b && b.until && b.until < Date.now()) { H.bans = H.bans.filter(x => x !== u); delete H.banInfo[u]; save(); return false; }
  return true;
}
function isMuted(u) { const t = H.mutes[u]; if (!t) return false; if (t < Date.now()) { delete H.mutes[u]; return false; } return true; }
const lastAct = new Map();
function slowmode(u, ms) { const t = lastAct.get(u) || 0; if (Date.now() - t < ms) return true; lastAct.set(u, Date.now()); return false; }
function checkText(res, me, text) {
  if (isBanned(me)) { fail(res, 403, 'You are banned from the Kyoto Hub'); return false; }
  if (isMuted(me)) { fail(res, 403, 'You are muted until ' + new Date(H.mutes[me]).toUTCString()); return false; }
  if (blocked(text)) { fail(res, 400, 'Blocked by the filter (bad words or unsafe content)'); return false; }
  return true;
}
const pub = (p, me) => ({ id: p.id, type: p.type, slot: p.slot, uuid: p.uuid, name: nameOf(p.uuid), title: p.title, text: p.text, at: p.at,
  likes: p.likes.length, liked: p.likes.includes(me), hasImage: !!p.image, pending: !!p.pending, owner: isOwnerUuid(p.uuid),
  price: p.price || 0, desc: p.desc || '', photos: (p.photos || []).length });
const q = req => new URL(req.url, 'http://x').searchParams;

routes['GET /hub/posts'] = async (req, res, body, me) => {
  const type = q(req).get('type') || 'post';
  const mod = isStaff(me);
  const list = H.posts.filter(p => p.type === type && (!p.pending || mod || p.uuid === me));
  const sort = q(req).get('sort') === 'top' ? (a, b) => b.likes.length - a.likes.length : (a, b) => b.at - a.at;
  send(res, 200, { posts: list.sort(sort).slice(0, 60).map(p => pub(p, me)), mod });
};
routes['POST /hub/post'] = async (req, res, body, me) => {
  const type = ['post', 'preset', 'cape', 'kycos'].includes(body.type) ? body.type : 'post';
  const title = clean(body.title, 40), text = clean(body.text, 300);
  if (!checkText(res, me, title + ' ' + text)) return;
  if (slowmode(me + 'post', 30000)) return fail(res, 429, 'Wait 30 seconds between posts');
  if (!title && !text) return fail(res, 400, 'Write something first');
  const post = { id: H.seq++, type, uuid: me, title, text, at: Date.now(), likes: [] };
  if (type === 'preset') {
    try { const d = JSON.parse(String(body.data || '')); if (String(body.data).length > 40000) throw 0; post.data = JSON.stringify({ modules: d.modules || {}, choices: d.choices || {}, crosshair: d.crosshair || null, vfx: d.vfx || null }); }
    catch (e) { return fail(res, 400, 'Bad preset'); }
  }
  if (type === 'kycos') {
    const k = checkKycos(body.data);
    if (k.error) return fail(res, 400, k.error);
    post.data = k.json;
    post.slot = k.slot;
    post.pending = REQUIRE_APPROVAL && !isStaff(me);
  }
  if (type === 'cape') {
    const buf = Buffer.from(String(body.image || ''), 'base64');
    const isPng = buf.slice(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), isGif = buf.slice(0, 3).toString() === 'GIF';
    if ((!isPng && !isGif) || buf.length > 1500000) return fail(res, 400, 'Cape must be a PNG or GIF under 1.5 MB');
    post.image = isGif ? 'gif' : 'png';
    fs.writeFileSync(path.join(HUB_IMG, String(post.id)), buf);
    post.pending = REQUIRE_APPROVAL && !isStaff(me);
  }
  H.posts.push(post); if (H.posts.length > 2000) H.posts.shift();
  save(); send(res, 200, { ok: true, pending: !!post.pending });
};
routes['GET /hub/image'] = async (req, res, body, me) => {
  const id = Number(q(req).get('id')); const p = H.posts.find(x => x.id === id);
  if (!p || !p.image || (p.pending && !isStaff(me) && p.uuid !== me)) return fail(res, 404, 'no image');
  send(res, 200, fs.readFileSync(path.join(HUB_IMG, String(id))), p.image === 'gif' ? 'image/gif' : 'image/png');
};
routes['GET /hub/preset'] = async (req, res, body, me) => {
  const id = Number(q(req).get('id')); const p = H.posts.find(x => x.id === id && x.type === 'preset');
  if (!p) return fail(res, 404, 'no preset');
  send(res, 200, { data: p.data });
};
routes['POST /hub/like'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(body.id)); if (!p) return fail(res, 404, 'gone');
  p.likes = p.likes.includes(me) ? p.likes.filter(u => u !== me) : p.likes.concat(me); save(); send(res, 200, { likes: p.likes.length });
};
routes['POST /hub/delete'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(body.id)); if (!p) return fail(res, 404, 'gone');
  if (p.uuid !== me && !isStaff(me)) return fail(res, 403, 'Not yours');
  H.posts = H.posts.filter(x => x !== p); try { fs.unlinkSync(path.join(HUB_IMG, String(p.id))); } catch (e) { /* none */ }
  save(); send(res, 200, { ok: true });
};
routes['POST /hub/report'] = async (req, res, body, me) => {
  if (slowmode(me + 'report', 5000)) return fail(res, 429, 'Slow down');
  const rep = { kind: String(body.kind || 'post').slice(0, 8), id: String(body.id).slice(0, 20), by: me, reason: clean(body.reason, 100), at: Date.now() };
  if (body.where) rep.where = String(body.where).slice(0, 40);
  if (body.text) rep.text = clean(body.text, 300); // what the message said (DMs aren't readable by mods otherwise)
  if (body.who) rep.who = clean(body.who, 40);
  if (rep.kind === 'chat') { const m = H.chat.find(x => String(x.id) === rep.id); if (m) { rep.text = m.text || (m.voice ? '[voice message]' : m.img ? '[picture]' : ''); rep.uuid = m.uuid; } }
  H.reports.push(rep);
  if (DISCORD_REPORT_HOOK) fetch(DISCORD_REPORT_HOOK, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'Kyoto Reports', content: `Report (${rep.kind}${rep.where ? ' ' + rep.where : ''}) by ${nameOf(me)}: ${rep.reason}\n> ${(rep.who || (rep.uuid ? nameOf(rep.uuid) : '?'))}: ${(rep.text || '').slice(0, 300)}`.slice(0, 1900), allowed_mentions: { parse: [] } }) }).catch(() => {});
  if (H.reports.length > 500) H.reports.shift();
  save(); send(res, 200, { ok: true });
};
const chatOut = (m, me) => m.src === 'discord'
  ? { id: m.id, discord: true, name: m.dname, avatar: m.davatar || '', text: m.text, img: m.img || null, at: m.at }
  : { id: m.id, uuid: m.uuid, name: nameOf(m.uuid), owner: isOwnerUuid(m.uuid), staff: isStaff(m.uuid), plus: isPlus(m.uuid), text: m.text || '', img: m.img || null, at: m.at, me: m.uuid === me };
routes['GET /chat'] = async (req, res, body, me) => {
  const since = Number(q(req).get('since')) || 0;
  send(res, 200, { messages: H.chat.filter(m => m.at > since).slice(-100).map(m => chatOut(m, me)) });
};
function pushChat(m) {
  H.chat.push(m);
  while (H.chat.length > 400) { const old = H.chat.shift(); if (old.img) try { fs.unlinkSync(path.join(HUB_IMG, 'c' + old.img)); } catch (e) { /* gone */ } }
  save();
}
routes['POST /chat/send'] = async (req, res, body, me) => {
  const text = clean(body.text, 300);
  if (!text || !checkText(res, me, text)) return text ? undefined : fail(res, 400, 'Empty');
  if (slowmode(me + 'chat', 1500)) return fail(res, 429, 'Slow down');
  const m = { id: H.seq++, uuid: me, text, at: Date.now() };
  pushChat(m); toDiscord(m);
  send(res, 200, { ok: true });
};
// Pictures in Hub chat: PNG/JPG/GIF/WEBP up to 3 MB, one every 20 s. Mods can delete them; anyone can report them.
routes['POST /chat/image'] = async (req, res, body, me) => {
  if (!checkText(res, me, String(body.text || ''))) return;
  if (slowmode(me + 'chatimg', 20000)) return fail(res, 429, 'One picture every 20 seconds');
  const buf = Buffer.from(String(body.image || ''), 'base64'), kind = imgKind(buf);
  if (!kind) return fail(res, 400, 'PNG, JPG, GIF or WEBP only');
  if (buf.length > 3000000) return fail(res, 413, 'Max 3 MB');
  const img = crypto.randomBytes(9).toString('hex');
  fs.writeFileSync(path.join(HUB_IMG, 'c' + img), buf);
  const m = { id: H.seq++, uuid: me, text: clean(body.text, 300), img, at: Date.now() };
  const ch = String(body.ch || '').toLowerCase().replace(/^(play|mc|join|hub)\./, '').slice(0, 60);
  if (/^[a-z0-9.\-]+(:\d+)?$/.test(ch)) m.ch = ch;
  pushChat(m); if (!m.ch) toDiscord(m);
  send(res, 200, { ok: true });
};
routes['GET /chat/img'] = async (req, res) => {
  const id = String(q(req).get('id') || ''); if (!/^[a-f0-9]{18}$/.test(id)) return fail(res, 400, 'bad id');
  if (!H.chat.some(m => m.img === id)) return fail(res, 404, 'deleted');
  const f = path.join(HUB_IMG, 'c' + id); if (!fs.existsSync(f)) return fail(res, 404, 'gone');
  const buf = fs.readFileSync(f), k = imgKind(buf);
  res.setHeader('Cache-Control', 'public, max-age=86400');
  send(res, 200, buf, k === 'png' ? 'image/png' : k === 'gif' ? 'image/gif' : k === 'webp' ? 'image/webp' : 'image/jpeg');
};

// ---------------- Discord bridge ----------------
// Hub -> Discord: DISCORD_WEBHOOK (the webhook URL, kept only here on the server).
// Discord -> Hub: DISCORD_BOT_TOKEN + DISCORD_CHANNEL_ID (a bot in your server with "Message Content Intent" on).
const DISCORD_WEBHOOK = process.env.DISCORD_WEBHOOK || '';
const DISCORD_REPORT_HOOK = process.env.DISCORD_REPORT_WEBHOOK || ''; // optional: a private staff channel for reports
const DISCORD_BOT = process.env.DISCORD_BOT_TOKEN || '';
const DISCORD_CHANNEL = process.env.DISCORD_CHANNEL_ID || '';
const webhookId = (/webhooks\/(\d+)\//.exec(DISCORD_WEBHOOK) || [])[1] || '';
function toDiscord(m) {
  if (!DISCORD_WEBHOOK || m.src === 'discord') return;
  const content = (m.text || '') + (m.img ? `\n${PUBLIC_URL}/chat/img?id=${m.img}` : '');
  if (!content.trim()) return;
  fetch(DISCORD_WEBHOOK + '?wait=false', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: nameOf(m.uuid) + ' (Kyoto)', avatar_url: `https://mc-heads.net/avatar/${m.uuid}/64`, content: content.slice(0, 1900), allowed_mentions: { parse: [] } }) })
    .catch(e => console.warn('discord webhook', e.message));
}
let discordAfter = db.discordAfter || '';
const discordState = { lastPoll: 0, lastOk: 0, lastError: '', received: 0 };
async function pollDiscord() {
  if (!DISCORD_BOT || !DISCORD_CHANNEL) return;
  discordState.lastPoll = Date.now();
  try {
    const r = await fetch(`https://discord.com/api/v10/channels/${DISCORD_CHANNEL}/messages?limit=20${discordAfter ? '&after=' + discordAfter : ''}`, { headers: { Authorization: 'Bot ' + DISCORD_BOT } });
    if (!r.ok) {
      discordState.lastError = r.status === 401 ? 'Bot token is wrong (401)' : r.status === 403 ? 'The bot can\'t see that channel (403): give it View Channel + Read Message History' : r.status === 404 ? 'Channel ID is wrong (404)' : 'Discord said ' + r.status;
      if (r.status !== 429) console.warn('discord poll', r.status); return;
    }
    discordState.lastOk = Date.now(); discordState.lastError = '';
    const msgs = (await r.json()).sort((a, b) => (BigInt(a.id) < BigInt(b.id) ? -1 : 1));
    const first = !discordAfter;
    for (const d of msgs) {
      discordAfter = d.id;
      if (first) continue; // don't replay old history on the first run
      if (d.webhook_id && d.webhook_id === webhookId) continue; // our own Hub messages
      if (d.author && d.author.bot) continue;
      let text = String(d.content || '').replace(/<a?:(\w+):\d+>/g, ':$1:').replace(/<@!?\d+>/g, '@someone').replace(/[\u0000-\u001f]/g, ' ').slice(0, 300);
      text = clean(text, 300);
      const att = (d.attachments || []).find(a => /^image\//.test(a.content_type || '') && a.size < 3000000);
      let img = null;
      if (att) {
        try { const buf = Buffer.from(await (await fetch(att.url)).arrayBuffer()); if (imgKind(buf)) { img = crypto.randomBytes(9).toString('hex'); fs.writeFileSync(path.join(HUB_IMG, 'c' + img), buf); } } catch (e) { /* skip picture */ }
      }
      if (!d.content && !(d.attachments || []).length && !(d.embeds || []).length && !(d.sticker_items || []).length) discordState.lastError = 'Messages arrive empty: turn on Message Content Intent (Discord Developer Portal > Bot)';
      if (!text && !img) continue;
      const a = d.author || {};
      discordState.received++;
      pushChat({ id: H.seq++, src: 'discord', dname: (a.global_name || a.username || 'Discord').slice(0, 32), davatar: a.avatar ? `https://cdn.discordapp.com/avatars/${a.id}/${a.avatar}.png?size=64` : '', text, img, at: Date.now() });
    }
    if (db.discordAfter !== discordAfter) { db.discordAfter = discordAfter; save(); }
  } catch (e) { console.warn('discord poll', e.message); }
}
if (DISCORD_BOT && DISCORD_CHANNEL) { setInterval(pollDiscord, 4000); pollDiscord(); console.log('Discord -> Hub bridge on'); }
routes['GET /discord/status'] = async (req, res, body, me) => {
  if (!isStaff(me)) return fail(res, 403, 'Staff only');
  send(res, 200, { hubToDiscord: !!DISCORD_WEBHOOK, discordToHub: !!(DISCORD_BOT && DISCORD_CHANNEL), missing: [!DISCORD_WEBHOOK && 'DISCORD_WEBHOOK', !DISCORD_BOT && 'DISCORD_BOT_TOKEN', !DISCORD_CHANNEL && 'DISCORD_CHANNEL_ID'].filter(Boolean),
    lastOk: discordState.lastOk, lastError: discordState.lastError, received: discordState.received });
};
if (DISCORD_WEBHOOK) console.log('Hub -> Discord bridge on');
const dmKey = (a, b) => [a, b].sort().join(':');
routes['GET /dm/threads'] = async (req, res, body, me) => {
  const threads = [];
  for (const [k, msgs] of Object.entries(H.dms)) {
    if (!k.includes(me) || !msgs.length) continue;
    const other = k.split(':').find(u => u !== me) || me; const last = msgs[msgs.length - 1];
    threads.push({ name: nameOf(other), last: last.img ? '[picture]' : last.text, at: last.at, fromMe: last.uuid === me });
  }
  send(res, 200, { threads: threads.sort((a, b) => b.at - a.at).slice(0, 40) });
};
routes['GET /dm/with'] = async (req, res, body, me) => {
  const other = byName(q(req).get('name')); if (!other) return fail(res, 404, 'No Kyoto player with that name');
  send(res, 200, { messages: (H.dms[dmKey(me, other)] || []).slice(-80).map(m => ({ name: nameOf(m.uuid), text: m.text || '', img: m.img || null, at: m.at, me: m.uuid === me })) });
};
routes['POST /dm/send'] = async (req, res, body, me) => {
  const other = byName(body.to); if (!other) return fail(res, 404, `${body.to} hasn't used Kyoto yet`);
  if (other === me) return fail(res, 400, "That's you!");
  if (list(H.blocks, other).includes(me)) return fail(res, 403, "You can't message this player");
  const text = clean(body.text, 300);
  if (!text || !checkText(res, me, text)) return text ? undefined : fail(res, 400, 'Empty');
  if (slowmode(me + 'dm', 800)) return fail(res, 429, 'Slow down');
  const box = H.dms[dmKey(me, other)] || (H.dms[dmKey(me, other)] = []);
  box.push({ uuid: me, text, at: Date.now() }); if (box.length > 200) box.shift();
  save(); send(res, 200, { ok: true });
};
routes['POST /dm/block'] = async (req, res, body, me) => {
  const other = byName(body.name); if (!other) return fail(res, 404, 'Unknown player');
  const l = list(H.blocks, me); if (!l.includes(other)) l.push(other); save(); send(res, 200, { ok: true });
};

// ---------------- DM pictures (friends only, PNG/JPG, max 2 MB) ----------------
routes['POST /dm/image'] = async (req, res, body, me) => {
  const other = byName(body.to); if (!other) return fail(res, 404, `${body.to} hasn't used Kyoto yet`);
  if (!areFriends(me, other)) return fail(res, 403, 'You can only send pictures to friends');
  if (list(H.blocks, other).includes(me)) return fail(res, 403, "You can't message this player");
  if (isBanned(me)) return fail(res, 403, 'Banned');
  if (slowmode(me + 'dmimg', 4000)) return fail(res, 429, 'Slow down');
  let buf; try { buf = Buffer.from(String(body.image || ''), 'base64'); } catch (e) { return fail(res, 400, 'Bad picture'); }
  const png = buf.length > 8 && buf[0] === 0x89 && buf[1] === 0x50; const jpg = buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8;
  if (!png && !jpg) return fail(res, 400, 'PNG or JPG only');
  if (buf.length > 2 * 1024 * 1024) return fail(res, 413, 'Max 2 MB');
  const id = crypto.randomBytes(9).toString('hex');
  fs.writeFileSync(path.join(DM_IMG, id), buf);
  const box = H.dms[dmKey(me, other)] || (H.dms[dmKey(me, other)] = []);
  box.push({ uuid: me, img: id, imgOwners: [me, other], at: Date.now() });
  while (box.length > 200) { const old = box.shift(); if (old.img) try { fs.unlinkSync(path.join(DM_IMG, old.img)); } catch (e) { /* gone */ } }
  save(); send(res, 200, { ok: true, id });
};
routes['GET /dm/img'] = async (req, res, body, me) => {
  const id = String(q(req).get('id') || ''); if (!/^[a-f0-9]{18}$/.test(id)) return fail(res, 400, 'bad id');
  // only the two people in that conversation can load it
  let ok = false;
  for (const [k, msgs] of Object.entries(H.dms)) { if (!k.includes(me)) continue; if (msgs.some(m => m.img === id)) { ok = true; break; } }
  if (!ok) return fail(res, 404, 'not found');
  const f = path.join(DM_IMG, id); if (!fs.existsSync(f)) return fail(res, 404, 'not found');
  const buf = fs.readFileSync(f); send(res, 200, buf, buf[0] === 0x89 ? 'image/png' : 'image/jpeg');
};

// ---------------- shared video screens (per server address) ----------------
if (!db.screens) db.screens = {};
const YT = /^https:\/\/(www\.|m\.|music\.|clips\.|player\.)?(youtube\.com|youtu\.be|twitch\.tv|kick\.com|vimeo\.com|dailymotion\.com|dai\.ly|streamable\.com)\//i;
function screenOut(s) { return Object.assign({}, s, { owner: nameOf(s.ownerUuid) }); }
// ---------------- Live Rooms: watch parties (synced video + chat), in game and on the website ----------------
// Kept in memory: a room lives while people are in it (closes 10 minutes after everyone leaves).
const ROOMS = new Map(); // id -> room
const ytId = u => { const m = /(?:youtu\.be\/|[?&]v=|\/shorts\/|\/live\/|\/embed\/)([\w-]{11})/.exec(String(u || '')); return m ? m[1] : null; };
async function videoTitle(url) {
  try {
    const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 4000);
    const r = await fetch('https://www.youtube.com/oembed?format=json&url=' + encodeURIComponent(url), { signal: ctl.signal }); clearTimeout(t);
    if (r.ok) { const d = await r.json(); return cleanStr(d.title, 100) || url; }
  } catch (e) { /* offline / not YouTube */ }
  return url.replace(/^https?:\/\/(www\.)?/, '').slice(0, 60);
}
function roomPos(v) { return !v ? 0 : v.playing ? v.pos + (Date.now() - v.at) / 1000 : v.pos; }
function roomOut(r, me) {
  const members = [...r.members.entries()].filter(([, t]) => Date.now() - t < 20000).map(([u]) => u);
  return { id: r.id, name: r.name, host: nameOf(r.host), hostMe: r.host === me, privacy: r.privacy, control: r.control,
    canControl: r.control === 'everyone' || r.host === me || isStaff(me),
    video: r.video ? { url: r.video.url, title: r.video.title, yt: ytId(r.video.url), playing: r.video.playing, pos: roomPos(r.video), by: r.video.by } : null,
    queue: r.queue.map(q => ({ url: q.url, title: q.title, by: q.by })), members: members.map(u => ({ name: nameOf(u), host: u === r.host })), viewers: members.length };
}
function roomVisible(r, me) { return r.privacy === 'public' || r.host === me || r.members.has(me) || areFriends(r.host, me) || isStaff(me); }
setInterval(() => {
  for (const [id, r] of ROOMS) {
    for (const [u, t] of r.members) if (Date.now() - t > 30000) r.members.delete(u);
    if (r.members.size === 0 && Date.now() - r.lastActive > 10 * 60000) ROOMS.delete(id);
    else if (r.members.size && !r.members.has(r.host)) { r.host = [...r.members.keys()][0]; r.chat.push({ id: H.seq++, sys: true, text: nameOf(r.host) + ' is the host now', at: Date.now() }); }
    // a finished video moves on to the next one in the queue
    if (r.video && r.video.playing && r.video.dur && roomPos(r.video) > r.video.dur + 2 && r.queue.length) { const n = r.queue.shift(); r.video = { url: n.url, title: n.title, by: n.by, playing: true, pos: 0, at: Date.now() }; }
  }
}, 5000);
routes['GET /rooms'] = async (req, res, body, me) => {
  const list = [...ROOMS.values()].filter(r => roomVisible(r, me)).map(r => roomOut(r, me)).sort((a, b) => b.viewers - a.viewers);
  send(res, 200, { rooms: list.map(r => ({ id: r.id, name: r.name, host: r.host, privacy: r.privacy, viewers: r.viewers, now: r.video ? r.video.title : '', yt: r.video ? r.video.yt : null })) });
};
routes['POST /rooms/create'] = async (req, res, body, me) => {
  if (isBanned(me)) return fail(res, 403, 'Banned');
  const name = clean(body.name, 40) || nameOf(me) + "'s room";
  if (FILTER.isBad(name)) return fail(res, 400, 'Pick another name');
  for (const [id, r] of ROOMS) if (r.host === me) ROOMS.delete(id); // one room each
  const id = crypto.randomBytes(4).toString('hex');
  const r = { id, name, host: me, privacy: body.privacy === 'friends' ? 'friends' : 'public', control: body.control === 'everyone' ? 'everyone' : 'host',
    video: null, queue: [], members: new Map([[me, Date.now()]]), chat: [], lastActive: Date.now() };
  ROOMS.set(id, r);
  if (body.url && YT.test(String(body.url))) r.video = { url: String(body.url), title: await videoTitle(String(body.url)), by: nameOf(me), playing: true, pos: 0, at: Date.now() };
  send(res, 200, { id });
};
routes['GET /rooms/state'] = async (req, res, body, me) => {
  const r = ROOMS.get(String(q(req).get('id') || ''));
  if (!r || !roomVisible(r, me)) return fail(res, 404, 'That room is closed');
  r.members.set(me, Date.now()); r.lastActive = Date.now();
  const since = Number(q(req).get('since')) || 0;
  send(res, 200, { now: Date.now(), room: roomOut(r, me), messages: r.chat.filter(m => m.at > since).slice(-60).map(m => m.sys ? m : { id: m.id, name: nameOf(m.uuid), text: m.text, at: m.at, me: m.uuid === me }) });
};
routes['POST /rooms/leave'] = async (req, res, body, me) => { const r = ROOMS.get(String(body.id || '')); if (r) r.members.delete(me); send(res, 200, { ok: true }); };
routes['POST /rooms/chat'] = async (req, res, body, me) => {
  const r = ROOMS.get(String(body.id || '')); if (!r || !roomVisible(r, me)) return fail(res, 404, 'That room is closed');
  const text = clean(body.text, 200); if (!text || !checkText(res, me, text)) return text ? undefined : fail(res, 400, 'Empty');
  if (slowmode(me + 'room', 800)) return fail(res, 429, 'Slow down');
  r.chat.push({ id: H.seq++, uuid: me, text, at: Date.now() }); if (r.chat.length > 200) r.chat.shift();
  send(res, 200, { ok: true });
};
routes['POST /rooms/control'] = async (req, res, body, me) => {
  const r = ROOMS.get(String(body.id || '')); if (!r || !roomVisible(r, me)) return fail(res, 404, 'That room is closed');
  const can = r.control === 'everyone' || r.host === me || isStaff(me);
  const a = String(body.action || '');
  if (a === 'queue') {
    if (!YT.test(String(body.url || ''))) return fail(res, 400, 'Paste a YouTube link');
    if (r.queue.length >= 30) return fail(res, 400, 'The queue is full');
    const url = String(body.url).slice(0, 300);
    const item = { url, title: await videoTitle(url), by: nameOf(me) };
    if (!r.video) r.video = Object.assign({}, item, { playing: true, pos: 0, at: Date.now() }); else r.queue.push(item);
    r.chat.push({ id: H.seq++, sys: true, text: nameOf(me) + ' added ' + item.title, at: Date.now() });
    return send(res, 200, { ok: true });
  }
  if (a === 'duration') { if (r.video && !r.video.dur) r.video.dur = Math.max(0, Math.min(86400, Number(body.dur) || 0)); return send(res, 200, { ok: true }); }
  if (!can) return fail(res, 403, 'Only the host can control this room');
  const v = r.video;
  if (a === 'load') {
    if (!YT.test(String(body.url || ''))) return fail(res, 400, 'Paste a YouTube link');
    const url = String(body.url).slice(0, 300);
    r.video = { url, title: await videoTitle(url), by: nameOf(me), playing: true, pos: 0, at: Date.now() };
    r.chat.push({ id: H.seq++, sys: true, text: nameOf(me) + ' is playing ' + r.video.title, at: Date.now() });
  } else if (a === 'play' && v) { v.pos = roomPos(v); v.at = Date.now(); v.playing = true; }
  else if (a === 'pause' && v) { v.pos = roomPos(v); v.at = Date.now(); v.playing = false; }
  else if (a === 'seek' && v) { v.pos = Math.max(0, Number(body.pos) || 0); v.at = Date.now(); }
  else if (a === 'skip') { const n = r.queue.shift(); r.video = n ? { url: n.url, title: n.title, by: n.by, playing: true, pos: 0, at: Date.now() } : null; }
  else if (a === 'remove') { r.queue.splice(Number(body.index) || 0, 1); }
  else if (a === 'settings' && (r.host === me || isStaff(me))) {
    if (body.name) { const n = clean(body.name, 40); if (n && !FILTER.isBad(n)) r.name = n; }
    if (body.privacy) r.privacy = body.privacy === 'friends' ? 'friends' : 'public';
    if (body.control) r.control = body.control === 'everyone' ? 'everyone' : 'host';
  } else if (a === 'close' && (r.host === me || isStaff(me))) { ROOMS.delete(r.id); }
  else return fail(res, 400, 'Unknown action');
  r.lastActive = Date.now();
  send(res, 200, { ok: true });
};

routes['GET /screens'] = async (req, res, body, me) => {
  const key = String(q(req).get('server') || '').toLowerCase().slice(0, 80);
  send(res, 200, { now: Date.now(), screens: (db.screens[key] || []).map(screenOut) });
};
routes['POST /screens/save'] = async (req, res, body, me) => {
  const key = String(body.server || '').toLowerCase().slice(0, 80); if (!key) return fail(res, 400, 'No server');
  if (isBanned(me)) return fail(res, 403, 'Banned');
  const url = String(body.url || '').slice(0, 300);
  if (url && !YT.test(url)) return fail(res, 400, 'Only YouTube, Twitch, Kick, Vimeo, Dailymotion or Streamable links can go on screens');
  const num = (v, lo, hi, d) => { const n = Number(v); return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : d; };
  const arr = db.screens[key] || (db.screens[key] = []);
  let s = body.id ? arr.find(x => x.id === body.id) : null;
  if (s && s.ownerUuid !== me) return fail(res, 403, "That's someone else's screen");
  if (!s) {
    if (arr.filter(x => x.ownerUuid === me).length >= 3) return fail(res, 400, 'Max 3 screens per server');
    if (arr.length >= 40) return fail(res, 400, 'This server has too many screens');
    s = { id: crypto.randomBytes(4).toString('hex'), ownerUuid: me }; arr.push(s);
  }
  Object.assign(s, {
    dim: String(body.dim || '').slice(0, 60), x: num(body.x, -3e7, 3e7, 0), y: num(body.y, -2048, 4096, 0), z: num(body.z, -3e7, 3e7, 0),
    yaw: num(body.yaw, -720, 720, 0), w: num(body.w, 1, 16, 4), h: num(body.h, 0.5, 9, 2.25), url,
    paused: !!body.paused, pausedPos: num(body.pausedPos, 0, 86400, 0),
    startedAt: body.paused ? 0 : Date.now() - num(body.pos, 0, 86400, 0) * 1000, updated: Date.now()
  });
  save(); send(res, 200, { ok: true, id: s.id });
};
routes['POST /screens/delete'] = async (req, res, body, me) => {
  for (const k of Object.keys(db.screens)) {
    const s = db.screens[k].find(x => x.id === body.id);
    if (s && (s.ownerUuid === me || isOwnerUuid(me))) db.screens[k] = db.screens[k].filter(x => x !== s);
  }
  save(); send(res, 200, { ok: true });
};

// ---------------- launcher: news + latest version (post them at /admin) ----------------
// Set ADMIN_KEY on Render (Environment) to a long secret. Without it, posting is disabled.
const ADMIN_KEY = process.env.ADMIN_KEY || '';
if (!db.news) db.news = [];
if (!db.release) db.release = { version: process.env.MOD_VERSION || '6.2.0', url: process.env.KYOTO_JAR_URL || '', notes: '' };
const adminFails = new Map();
function isAdmin(req, res) {
  const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
  const f = adminFails.get(ip) || { n: 0, at: 0 };
  if (f.n >= 8 && Date.now() - f.at < 15 * 60000) { fail(res, 429, 'Too many wrong keys, wait 15 minutes'); return false; }
  const key = String(req.headers['x-admin-key'] || '');
  const a = Buffer.from(key), b = Buffer.from(ADMIN_KEY);
  if (!ADMIN_KEY || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    adminFails.set(ip, { n: f.n + 1, at: Date.now() }); fail(res, 403, 'Wrong admin key'); return false;
  }
  adminFails.delete(ip); return true;
}
// ---------------- server packs (patterns the mod reads, so wording fixes don't need a mod update) ----------------
const PACK_IDS = ['donut', 'hypixel', 'lifesteal', 'practice', 'mcc', 'smp'];
routes['POST /packs/set'] = async (req, res, body) => {
  if (!isAdmin(req, res)) return;
  const id = String(body.id || '');
  if (!PACK_IDS.includes(id)) return fail(res, 400, 'Unknown pack');
  db.packs = db.packs || {};
  const out = {};
  if (body.patterns && typeof body.patterns === 'object') { out.patterns = {}; for (const [k, v] of Object.entries(body.patterns).slice(0, 200)) { try { new RegExp(String(v)); out.patterns[String(k).slice(0, 40)] = String(v).slice(0, 400); } catch (e) { return fail(res, 400, 'Bad regex for ' + k); } } }
  if (Array.isArray(body.disallow)) out.disallow = body.disallow.slice(0, 50).map(x => String(x).slice(0, 40));
  if (Array.isArray(body.suggest)) out.suggest = body.suggest.slice(0, 20).map(a => (Array.isArray(a) ? a : []).slice(0, 3).map(x => String(x).slice(0, 200)));
  db.packs[id] = out; save();
  send(res, 200, { ok: true });
};
routes['POST /packs/suggest'] = async (req, res, body, me) => {
  const pack = String(body.pack || '').slice(0, 20), text = String(body.text || '').trim().slice(0, 500);
  if (!text) return fail(res, 400, 'Write something');
  db.packSuggest = db.packSuggest || [];
  if (db.packSuggest.filter(x => x.u === me && Date.now() - x.at < 3600000).length >= 10) return fail(res, 429, 'Slow down');
  db.packSuggest.push({ u: me, name: nameOf(me), pack, text, at: Date.now() });
  if (db.packSuggest.length > 2000) db.packSuggest.splice(0, db.packSuggest.length - 2000);
  save(); send(res, 200, { ok: true });
};
routes['GET /packs/suggestions'] = async (req, res) => { if (!isAdmin(req, res)) return; send(res, 200, (db.packSuggest || []).slice(-300).reverse()); };

// ---------------- Hypixel player stats (HYPIXEL_API_KEY lives only in Render env) ----------------
const HYPIXEL_KEY = process.env.HYPIXEL_API_KEY || '';
const HYP_CACHE = new Map(); // lower name -> { at, data }
const HYP_RATE = new Map();  // uuid -> [times]
routes['GET /hypixel/player'] = async (req, res, body, me) => {
  const name = String(q(req).get('name') || '');
  if (!/^[A-Za-z0-9_]{2,16}$/.test(name)) return fail(res, 400, 'Bad name');
  if (!HYPIXEL_KEY) return send(res, 200, { error: 'Stats are off on this server' });
  const k = name.toLowerCase(), c = HYP_CACHE.get(k);
  if (c && Date.now() - c.at < 10 * 60000) return send(res, 200, c.data);
  const t = (HYP_RATE.get(me) || []).filter(x => Date.now() - x < 60000);
  if (t.length >= 40) return fail(res, 429, 'Slow down'); t.push(Date.now()); HYP_RATE.set(me, t);
  try {
    const mr = await fetch('https://api.mojang.com/users/profiles/minecraft/' + encodeURIComponent(name));
    if (mr.status !== 200) { const d = { name, nick: true }; HYP_CACHE.set(k, { at: Date.now(), data: d }); return send(res, 200, d); }
    const mj = await mr.json();
    const hr = await fetch('https://api.hypixel.net/v2/player?uuid=' + mj.id, { headers: { 'API-Key': HYPIXEL_KEY } });
    const hj = await hr.json();
    const p = hj.player || {}, st = p.stats || {}, bw = st.Bedwars || {}, sw = st.SkyWars || {}, du = st.Duels || {};
    const r2 = (a, b) => Math.round((a || 0) / Math.max(1, b || 0) * 100) / 100;
    const data = {
      name: mj.name, rank: p.newPackageRank || p.packageRank || '', plus: p.monthlyPackageRank === 'SUPERSTAR',
      level: p.networkExp ? Math.round((Math.sqrt(2 * p.networkExp + 30625) / 50 - 2.5) * 10) / 10 : 0,
      bw_star: (p.achievements || {}).bedwars_level || 0, bw_fkdr: r2(bw.final_kills_bedwars, bw.final_deaths_bedwars), bw_wins: bw.wins_bedwars || 0, bw_wlr: r2(bw.wins_bedwars, bw.losses_bedwars), bw_ws: bw.winstreak ?? null,
      sw_star: Number(String(sw.levelFormatted || '').replace(/\D/g, '')) || 0, sw_kdr: r2(sw.kills, sw.deaths), sw_wins: sw.wins || 0,
      duels_wins: du.wins || 0, duels_wlr: r2(du.wins, du.losses), duels_ws: du.current_winstreak ?? null,
    };
    if (HYP_CACHE.size > 5000) HYP_CACHE.clear();
    HYP_CACHE.set(k, { at: Date.now(), data });
    send(res, 200, data);
  } catch (e) { send(res, 200, { error: 'Hypixel did not answer' }); }
};

// ---------------- live stats for stream overlays (the mod posts, the overlay reads with an overlay key) ----------------
// The overlay key only unlocks these game numbers. It is NOT your login or stream token, and you can make a new one any time.
const LIVE = new Map(); // uuid -> { at, data, events: [] }
function ovKeyOf(u) {
  const user = db.users[u]; if (!user) return '';
  if (!user.ovKey) { user.ovKey = crypto.randomBytes(9).toString('base64url'); save(); }
  return user.ovKey;
}
function userByOvKey(k) {
  if (!/^[\w-]{8,20}$/.test(k || '')) return null;
  for (const [u, user] of Object.entries(db.users)) if (user.ovKey === k) return u;
  return null;
}
routes['POST /overlay/live'] = async (req, res, body, me) => {
  const n = v => (typeof v === 'number' && isFinite(v) ? Math.round(v * 100) / 100 : -1);
  const s = v => String(v == null ? '' : v).replace(/[§\u0000-\u001f]/g, '').slice(0, 60);
  const prev = LIVE.get(me) || { events: [] };
  const data = { server: s(body.server), pack: s(body.pack), money: n(body.money), session: n(body.session), perHour: n(body.perHour), shards: n(body.shards), kills: n(body.kills), deaths: n(body.deaths), bounty: n(body.bounty), hearts: n(body.hearts), elo: n(body.elo) };
  const evs = Array.isArray(body.events) ? body.events.slice(0, 20).map(e => ({ type: s(e && e.type).slice(0, 20), text: s(e && e.text).slice(0, 120), at: Date.now() })) : [];
  const events = prev.events.concat(evs).filter(e => Date.now() - e.at < 120000).slice(-30);
  LIVE.set(me, { at: Date.now(), data, events });
  send(res, 200, { ok: true });
};
routes['GET /overlay/key'] = async (req, res, body, me) => send(res, 200, { key: ovKeyOf(me), name: nameOf(me) });
routes['POST /overlay/key/new'] = async (req, res, body, me) => { if (db.users[me]) { db.users[me].ovKey = ''; } send(res, 200, { key: ovKeyOf(me) }); };
routes['GET /overlay/live'] = async (req, res) => {
  const u = userByOvKey(q(req).get('k'));
  if (!u) return send(res, 200, { on: false, error: 'bad key' });
  const e = LIVE.get(u);
  const since = Number(q(req).get('since')) || 0;
  if (!e || Date.now() - e.at > 60000) return send(res, 200, { on: false, name: nameOf(u) });
  send(res, 200, { on: true, name: nameOf(u), ...e.data, events: e.events.filter(x => x.at > since), now: Date.now() });
};

const NEWS_TAGS = ['Update', 'News', 'Event', 'Cosmetics', 'Fix', 'Sneak peek'];
routes['GET /news'] = async (req, res) => {
  const list = db.news.slice().sort((a, b) => (b.pinned - a.pinned) || (b.at - a.at)).slice(0, 30);
  send(res, 200, { news: list, release: db.release });
};
routes['GET /version'] = async (req, res) => send(res, 200, db.release);
routes['POST /news'] = async (req, res, body) => {
  if (!isAdmin(req, res)) return;
  const title = cleanStr(body.title, 90).trim(); const text = String(body.body || '').replace(/\r/g, '').slice(0, 4000).trim();
  if (!title || !text) return fail(res, 400, 'Title and text are needed');
  const image = String(body.image || '').trim();
  if (image && !/^https:\/\/[^\s"'<>]+$/i.test(image)) return fail(res, 400, 'Image must be an https:// link');
  const item = { id: crypto.randomBytes(5).toString('hex'), title, body: text, tag: NEWS_TAGS.includes(body.tag) ? body.tag : 'News',
    image, pinned: !!body.pinned, at: Date.now() };
  if (body.id) { const old = db.news.find(n => n.id === body.id); if (old) { Object.assign(old, item, { id: old.id, at: old.at, edited: Date.now() }); save(); return send(res, 200, { ok: true, id: old.id }); } }
  db.news.push(item); if (db.news.length > 200) db.news.shift(); save(); send(res, 200, { ok: true, id: item.id });
};
routes['POST /news/delete'] = async (req, res, body) => {
  if (!isAdmin(req, res)) return;
  db.news = db.news.filter(n => n.id !== body.id); save(); send(res, 200, { ok: true });
};
routes['POST /version'] = async (req, res, body) => {
  if (!isAdmin(req, res)) return;
  const url = String(body.url || '').trim();
  if (url && !/^https:\/\/[^\s"'<>]+\.jar(\?[^\s"'<>]*)?$/i.test(url)) return fail(res, 400, 'Download link must be an https:// link to a .jar');
  db.release = { version: cleanStr(body.version, 20).trim() || db.release.version, url, notes: String(body.notes || '').slice(0, 2000), at: Date.now() };
  save(); send(res, 200, { ok: true });
};
routes['GET /admin'] = async (req, res) => send(res, 200, Buffer.from(ADMIN_PAGE), 'text/html; charset=utf-8');
const ADMIN_PAGE = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kyoto · News admin</title><style>
*{box-sizing:border-box}body{margin:0;font-family:Inter,Segoe UI,system-ui,sans-serif;background:radial-gradient(1200px 600px at 20% -10%,#1d2030,#08080b 60%);color:#e8eaee;min-height:100vh}
.wrap{max-width:980px;margin:0 auto;padding:40px 20px}h1{font-size:28px;margin:0 0 4px;letter-spacing:.5px}p.sub{color:#8d929c;margin:0 0 28px}
.card{background:rgba(20,21,27,.75);border:1px solid rgba(255,255,255,.08);border-radius:18px;padding:22px;margin-bottom:18px;backdrop-filter:blur(12px)}
label{display:block;font-size:12px;color:#9aa0aa;margin:12px 0 6px;text-transform:uppercase;letter-spacing:1.2px}
input,textarea,select{width:100%;background:#0e0f14;border:1px solid rgba(255,255,255,.1);color:#fff;border-radius:12px;padding:12px 14px;font:inherit;outline:none}
input:focus,textarea:focus,select:focus{border-color:#cfd3dc}textarea{min-height:160px;resize:vertical}
.row{display:grid;grid-template-columns:1fr 1fr;gap:14px}button{background:linear-gradient(180deg,#fff,#cfd3dc);color:#0b0b0e;border:0;border-radius:999px;padding:12px 22px;font-weight:700;cursor:pointer;margin-top:16px}
button.ghost{background:transparent;color:#e8eaee;border:1px solid rgba(255,255,255,.15)}.item{display:flex;gap:14px;align-items:center;padding:12px 0;border-top:1px solid rgba(255,255,255,.06)}
.tag{font-size:11px;padding:3px 9px;border-radius:999px;background:#e8eaee;color:#0b0b0e;font-weight:700}.muted{color:#8d929c;font-size:13px}.grow{flex:1}#msg{margin-top:12px;color:#a7f3d0}
</style></head><body><div class="wrap"><h1>Kyoto news</h1><p class="sub">Posts show up in the Kyoto launcher (and anywhere else that reads /news).</p>
<div class="card"><label>Admin key</label><input id="key" type="password" placeholder="The ADMIN_KEY you set on Render"></div>
<div class="card"><h3 style="margin:0">New post</h3><input type="hidden" id="id">
<div class="row"><div><label>Title</label><input id="title" maxlength="90"></div><div><label>Tag</label><select id="tag">${NEWS_TAGS.map(t => `<option>${t}</option>`).join('')}</select></div></div>
<label>Image link (optional, https://...)</label><input id="image" placeholder="https://i.imgur.com/....png">
<label>Text (blank line = new paragraph, - at the start = bullet)</label><textarea id="body"></textarea>
<label><input type="checkbox" id="pinned" style="width:auto"> Pin to the top</label>
<button onclick="post()">Publish</button> <button class="ghost" onclick="clearForm()">Clear</button><div id="msg"></div></div>
<div class="card"><h3 style="margin:0 0 6px">Latest version</h3><p class="muted">The launcher downloads this .jar when it changes.</p>
<div class="row"><div><label>Version</label><input id="ver"></div><div><label>Download link (.jar, https)</label><input id="url" placeholder="https://github.com/you/kyoto/releases/download/v6.2.0/kyoto-client-6.2.0.jar"></div></div>
<label>What's new</label><textarea id="notes" style="min-height:90px"></textarea><button onclick="saveVer()">Save version</button></div>
<div class="card"><h3 style="margin:0">Posts</h3><div id="list" class="muted">Loading...</div></div></div>
<script>
const $=id=>document.getElementById(id);$('key').value=localStorage.k||'';$('key').oninput=e=>localStorage.k=e.target.value;
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function api(path,body){const r=await fetch(path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json','X-Admin-Key':$('key').value},body:body?JSON.stringify(body):undefined});const j=await r.json();if(!r.ok)throw new Error(j.error||r.status);return j}
let NEWS=[];async function load(){const j=await api('/news');NEWS=j.news;$('ver').value=j.release.version||'';$('url').value=j.release.url||'';$('notes').value=j.release.notes||'';
$('list').innerHTML=NEWS.length?NEWS.map(n=>'<div class="item"><span class="tag">'+esc(n.tag)+'</span><div class="grow"><b>'+(n.pinned?'📌 ':'')+esc(n.title)+'</b><div class="muted">'+new Date(n.at).toLocaleString()+'</div></div><button class="ghost" onclick="edit(\\''+n.id+'\\')">Edit</button><button class="ghost" onclick="del(\\''+n.id+'\\')">Delete</button></div>').join(''):'No posts yet.'}
function clearForm(){['id','title','image','body'].forEach(k=>$(k).value='');$('pinned').checked=false}
function edit(id){const n=NEWS.find(x=>x.id===id);$('id').value=n.id;$('title').value=n.title;$('tag').value=n.tag;$('image').value=n.image||'';$('body').value=n.body;$('pinned').checked=!!n.pinned;scrollTo(0,0)}
async function post(){try{await api('/news',{id:$('id').value,title:$('title').value,tag:$('tag').value,image:$('image').value,body:$('body').value,pinned:$('pinned').checked});$('msg').textContent='Published ✓';clearForm();load()}catch(e){$('msg').textContent=e.message}}
async function del(id){if(!confirm('Delete this post?'))return;try{await api('/news/delete',{id});load()}catch(e){alert(e.message)}}
async function saveVer(){try{await api('/version',{version:$('ver').value,url:$('url').value,notes:$('notes').value});alert('Saved ✓')}catch(e){alert(e.message)}}
load();
</script></body></html>`;
// ---------------- Kyoto Blox now-playing relay (memory only) ----------------
const BLOX_NP = new Map();   // robloxUserId -> { key, title, artist, playing, pos, dur, song, lines, seen }
const BLOX_KEYS = new Map(); // robloxUserId -> the Kyoto Blox install that claimed it first (stops others overwriting it)
setInterval(() => { for (const [k, v] of BLOX_NP) if (Date.now() - v.seen > 120000) BLOX_NP.delete(k); }, 60000).unref();
routes['POST /blox/np'] = async (req, res, body) => {
  const id = String(body.userId || ''); const key = String(body.key || '');
  if (!/^\d{1,20}$/.test(id) || !/^[a-f0-9]{32}$/.test(key)) return fail(res, 400, 'bad request');
  const owner = BLOX_KEYS.get(id);
  if (owner && owner !== key) return send(res, 200, { ok: false, error: 'This Roblox account is linked to another Kyoto Blox' });
  BLOX_KEYS.set(id, key);
  const prev = BLOX_NP.get(id) || {};
  const e = { key, title: cleanStr(body.title, 120), artist: cleanStr(body.artist, 120), playing: !!body.playing, pos: Math.max(0, Number(body.pos) || 0),
    dur: Math.max(0, Number(body.dur) || 0), song: cleanStr(body.song, 16), seen: Date.now(), lines: prev.song === cleanStr(body.song, 16) ? prev.lines : [] };
  if (Array.isArray(body.lines)) e.lines = body.lines.slice(0, 400).filter(l => Array.isArray(l)).map(l => [Math.max(0, Number(l[0]) || 0), cleanStr(FILTER.tag(String(l[1] || '')), 140)]);
  BLOX_NP.set(id, e);
  send(res, 200, { ok: true, needLines: !!e.song && !e.lines.length });
};

// ---------------- Kyoto+ (optional supporter badge, paid through Ko-fi) ----------------
// Ko-fi > Settings > API > Webhook URL = https://<your server>/kofi, and copy the Verification Token into the
// KOFI_TOKEN environment variable on Render. Buyers type their Minecraft name in the Ko-fi message box.
db.plus = db.plus || {};
const KOFI_TOKEN = process.env.KOFI_TOKEN || '';
const KOFI_URL = process.env.KOFI_URL || '';
// accounts that always have Kyoto+ (the owner's alts)
const GIFTED_PLUS = ['hefffm'];
function isPlus(u) { const p = db.plus[u]; return !!p && (p.until === 0 || p.until > Date.now()) || isOwnerUuid(u) || GIFTED_PLUS.includes(String(nameOf(u) || '').toLowerCase()); }
function grantPlus(u, days, source) {
  const now = Date.now(); const cur = db.plus[u];
  const base = cur && cur.until > now ? cur.until : now;
  db.plus[u] = { since: cur ? cur.since : now, until: days ? base + days * 86400000 : 0, source };
}
routes['POST /kofi'] = async (req, res, body) => {
  let d = {}; try { d = JSON.parse(body.data || '{}'); } catch (e) { return fail(res, 400, 'bad data'); }
  if (!KOFI_TOKEN || d.verification_token !== KOFI_TOKEN) return fail(res, 403, 'bad token');
  const text = `${d.message || ''} ${d.from_name || ''}`;
  let who = null;
  for (const m of text.matchAll(/[A-Za-z0-9_]{3,16}/g)) { const u = byName(m[0]); if (u) { who = u; break; } }
  db.kofiLog = (db.kofiLog || []).slice(-200);
  db.kofiLog.push({ at: Date.now(), name: d.from_name || '', amount: d.amount || '', type: d.type || '', tier: d.tier_name || '', matched: who ? nameOf(who) : '' });
  if (who) { grantPlus(who, 32, 'kofi'); audit(who, 'kyoto+ (ko-fi)', nameOf(who), `${d.type || ''} ${d.amount || ''}`); }
  save();
  send(res, 200, { ok: true, matched: !!who });
};
routes['POST /mod/plus'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const u = byName(body.name); if (!u) return fail(res, 404, 'Unknown player');
  const days = Math.max(0, Math.min(3650, Number(body.days) || 0));
  if (body.remove) { delete db.plus[u]; audit(me, 'remove kyoto+', nameOf(u)); }
  else { grantPlus(u, days, 'staff'); audit(me, 'give kyoto+', nameOf(u), days ? days + ' days' : 'forever'); }
  save(); send(res, 200, { ok: true });
};
routes['GET /mod/kofi'] = async (req, res, body, me) => { if (!staffOnly(res, me)) return; send(res, 200, { log: (db.kofiLog || []).slice().reverse() }); };

// ---------------- shared client config (so nobody sets things up by hand) ----------------
// DISCORD_APP_ID on Render = one Discord application for everyone's "Playing Kyoto Client" status.
routes['GET /config'] = async (req, res) => send(res, 200, { discordAppId: process.env.DISCORD_APP_ID || '', kofiUrl: KOFI_URL, site: process.env.SITE_URL || '' });

// ---------------- TikTok LIVE gifts (unofficial: TikTok has no public LIVE API) ----------------
// The mod asks the server to watch a username; the server keeps one connection per Kyoto player and the mod
// polls for new events every second. Needs the "tiktok-live-connector" package (npm install on Render).
const TT = new Map(); // uuid -> { username, conn, events: [], seq, lastPoll, state }
let TTLib = null;
async function ttLib() { if (!TTLib) TTLib = await import('tiktok-live-connector'); return TTLib; }
function ttPush(t, ev) { ev.seq = ++t.seq; ev.at = Date.now(); t.events.push(ev); if (t.events.length > 200) t.events.shift(); }
function ttUser(u) { u = u || {}; return { id: u.uniqueId || '', name: cleanStr(u.nickname || u.uniqueId || 'Someone', 40), avatar: (u.profilePicture && (u.profilePicture.url || u.profilePicture.urls || [])[0]) || u.profilePictureUrl || '' }; }
function ttStop(uuid) { const t = TT.get(uuid); if (!t) return; try { t.conn && t.conn.disconnect(); } catch (e) { /* */ } TT.delete(uuid); }
setInterval(() => { for (const [u, t] of TT) if (Date.now() - t.lastPoll > 90000) ttStop(u); }, 30000).unref();
/* Euler Stream's cloud WebSocket: Euler watches the LIVE from their own servers (TikTok blocks Render) and sends us JSON.
   Used first whenever EULER_API_KEY is set. Message shapes vary a little between their versions, so read them loosely. */
function ttEuler(t, username, key) {
  return new Promise((resolve, reject) => {
    let WS; try { WS = require('ws'); } catch (e) { WS = globalThis.WebSocket; }
    if (!WS) return reject(new Error('No WebSocket support on the server'));
    const url = `wss://ws.eulerstream.com?uniqueId=${encodeURIComponent(username)}&apiKey=${encodeURIComponent(key)}`;
    const ws = new WS(url); let opened = false, logged = 0;
    const on = (ev, fn) => ws.on ? ws.on(ev, fn) : ws.addEventListener(ev, e => fn(ev === 'message' ? e.data : ev === 'close' ? e.code : e, e && e.reason));
    t.conn = { disconnect: () => { try { ws.close(); } catch (e) { /* */ } } };
    const timer = setTimeout(() => { if (!opened) { t.conn.disconnect(); reject(new Error('Euler took too long to answer')); } }, 11000);
    on('open', () => { opened = true; clearTimeout(timer); resolve(); });
    on('error', e => { if (!opened) { clearTimeout(timer); reject(new Error('Euler: ' + String(e && e.message || e))); } });
    on('close', (code, reason) => {
      clearTimeout(timer); const why = String(reason || '');
      if (!opened) return reject(new Error(`Euler closed (${code}) ${why}`));
      console.log('[tiktok]', username, 'Euler closed', code, why);
      if (t.state !== 'ended') { t.state = /offline|ended|not live/i.test(why) ? 'ended' : 'disconnected'; if (t.state === 'ended') ttPush(t, { type: 'state', state: 'ended' }); }
    });
    on('message', raw => {
      let j; try { j = JSON.parse(String(raw)); } catch (e) { return; }
      if (logged < 3) { logged++; console.log('[tiktok]', username, 'Euler sample:', String(raw).slice(0, 600)); }
      const list = Array.isArray(j) ? j : Array.isArray(j.messages) ? j.messages : [j];
      for (const m of list) ttEulerMsg(t, m);
    });
  });
}
function ttEulerMsg(t, m) {
  if (!m || typeof m !== 'object') return;
  const type = String(m.type || m.event || m.method || '').toLowerCase(), d = m.data || m.message || m;
  const user = ttUser(d.user || m.user);
  if (/gift/.test(type)) {
    const g = d.gift || d.giftDetails || {}, ex = d.extendedGiftInfo || {};
    const count = d.repeatCount || g.repeatCount || d.comboCount || 1;
    const streaky = (g.giftType === 1 || g.type === 1) || d.repeatEnd !== undefined;
    ttPush(t, { type: 'gift', user, gift: cleanStr(g.giftName || g.name || ex.name || d.giftName || 'Gift', 40), giftId: d.giftId || g.id || 0,
      count, done: !streaky || !!(d.repeatEnd || d.repeat_end), coins: g.diamondCount || g.diamond_count || ex.diamond_count || d.diamondCount || 0,
      image: ((g.image || ex.image || {}).url_list || (g.image || {}).urlList || [])[0] || '' });
  } else if (/follow/.test(type)) ttPush(t, { type: 'follow', user });
  else if (/share/.test(type)) ttPush(t, { type: 'share', user });
  else if (/social/.test(type)) { const txt = JSON.stringify(d).toLowerCase(); if (/follow/.test(txt)) ttPush(t, { type: 'follow', user }); else if (/share/.test(txt)) ttPush(t, { type: 'share', user }); }
  else if (/like/.test(type)) { t.likes = d.totalLikeCount || d.totalLikes || d.total || ((t.likes || 0) + (d.likeCount || d.count || 0)); const step = 1000 * Math.floor(t.likes / 1000); if (step > (t.likeStep || 0)) { t.likeStep = step; ttPush(t, { type: 'likes', user, count: step }); } }
  else if (/superfan|subscribe/.test(type)) ttPush(t, { type: 'superfan', user });
  else if (/control|streamend|live_end|liveend/.test(type) && /end|3/.test(JSON.stringify(d).toLowerCase())) { t.state = 'ended'; ttPush(t, { type: 'state', state: 'ended' }); }
}
routes['POST /tiktok/connect'] = async (req, res, body, me) => {
  const username = String(body.username || '').replace(/^@/, '').trim();
  if (!/^[A-Za-z0-9_.]{2,24}$/.test(username)) return fail(res, 400, 'That isn\'t a TikTok username');
  if (TT.size >= 60 && !TT.has(me)) return fail(res, 503, 'Too many people connected right now, try again soon');
  ttStop(me);
  const t = { username, conn: null, events: [], seq: 0, lastPoll: Date.now(), state: 'connecting' };
  TT.set(me, t);
  // TikTok often gives cloud servers (like Render) an empty page, so try a few ways in a row:
  //  1. normal connect   2. look the LIVE up first, then connect straight to that room   3. same again after a short wait
  const attempts = [
    { label: 'normal', opts: {} },
    { label: 'room id', opts: { fetchRoomInfoOnConnect: false }, room: true },
    { label: 'room id, retry', opts: { fetchRoomInfoOnConnect: false }, room: true, wait: 1500 }
  ];
  let lastErr = null; const started = Date.now();
  if (process.env.EULER_API_KEY) {
    try {
      await ttEuler(t, username, process.env.EULER_API_KEY);
      if (TT.get(me) !== t) return send(res, 200, { ok: false, error: 'Cancelled' });
      t.state = 'live'; console.log('[tiktok]', username, 'connected (Euler cloud)');
      return send(res, 200, { ok: true, state: 'live' });
    } catch (e) {
      lastErr = e; console.warn('[tiktok]', username, 'Euler cloud failed:', String(e && e.message || e));
      const m = String(e && e.message || e);
      if (/offline|not live|4404|404/i.test(m)) { ttStop(me); return send(res, 200, { ok: false, error: `@${username} isn't live right now (or the name is wrong)` }); }
      if (/40[13]|invalid|api ?key|unauthor/i.test(m)) { ttStop(me); return send(res, 200, { ok: false, error: 'The EULER_API_KEY on Render is wrong' }); }
    }
  }
  // the mod waits 15 s for an answer, so all tries together must finish in about 13 s
  const left = () => 13000 - (Date.now() - started);
  const within = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('TikTok took too long to answer')), Math.max(500, ms)))]);
  for (const a of attempts) {
    if (left() < 2500) break;
    if (TT.get(me) !== t) return send(res, 200, { ok: false, error: 'Cancelled' }); // they pressed Connect again
    if (a.wait) await new Promise(r => setTimeout(r, a.wait));
    try {
      const { TikTokLiveConnection, WebcastEvent, ControlEvent } = await ttLib();
      const conn = new TikTokLiveConnection(username, Object.assign({ enableExtendedGiftInfo: true, processInitialData: false, signApiKey: process.env.EULER_API_KEY || undefined }, a.opts));
      t.conn = conn;
      conn.on(WebcastEvent.GIFT, d => {
        const g = d.giftDetails || {}; const ex = d.extendedGiftInfo || {};
        const streaky = (g.giftType === 1);
        ttPush(t, { type: 'gift', user: ttUser(d.user), gift: cleanStr(g.giftName || ex.name || d.giftName || 'Gift', 40), giftId: d.giftId || 0,
          count: d.repeatCount || 1, done: !streaky || !!d.repeatEnd, coins: g.diamondCount || ex.diamond_count || d.diamondCount || 0,
          image: ((ex.image || {}).url_list || [])[0] || '' });
      });
      if (WebcastEvent.FOLLOW) conn.on(WebcastEvent.FOLLOW, d => ttPush(t, { type: 'follow', user: ttUser(d.user) }));
      if (WebcastEvent.SHARE) conn.on(WebcastEvent.SHARE, d => ttPush(t, { type: 'share', user: ttUser(d.user) }));
      if (WebcastEvent.SUPER_FAN) conn.on(WebcastEvent.SUPER_FAN, d => ttPush(t, { type: 'superfan', user: ttUser(d.user) }));
      if (WebcastEvent.LIKE) conn.on(WebcastEvent.LIKE, d => { t.likes = (d.totalLikeCount || ((t.likes || 0) + (d.likeCount || 0))); const step = 1000 * Math.floor(t.likes / 1000); if (step > (t.likeStep || 0)) { t.likeStep = step; ttPush(t, { type: 'likes', user: ttUser(d.user), count: step }); } });
      if (WebcastEvent.STREAM_END) conn.on(WebcastEvent.STREAM_END, () => { t.state = 'ended'; ttPush(t, { type: 'state', state: 'ended' }); });
      if (ControlEvent && ControlEvent.DISCONNECTED) conn.on(ControlEvent.DISCONNECTED, () => { if (t.state !== 'ended') t.state = 'disconnected'; });
      if (ControlEvent && ControlEvent.ERROR) conn.on(ControlEvent.ERROR, e => console.warn('[tiktok]', username, 'error event:', e && (e.info || e.message || e)));
      if (a.room) {
        const roomId = await within(conn.fetchRoomId(username), left() - 1000);
        if (!roomId) throw new Error('Could not find the LIVE room');
        await within(conn.connect(String(roomId)), left());
      } else await within(conn.connect(), Math.min(left(), 7000));
      t.state = 'live';
      console.log('[tiktok]', username, 'connected (' + a.label + ')');
      return send(res, 200, { ok: true, state: 'live' });
    } catch (e) {
      lastErr = e;
      try { t.conn && t.conn.disconnect(); } catch (x) { /* */ }
      const m = String(e && e.message || e);
      console.warn('[tiktok]', username, 'attempt "' + a.label + '" failed:', m, e && e.stack ? '\n' + String(e.stack).split('\n').slice(0, 4).join('\n') : '');
      if (/Cannot find (module|package)|ERR_MODULE_NOT_FOUND|UserOfflineError|not (currently )?live|LIVE has ended|isn't online/i.test(m)) break; // retrying won't help
    }
  }
  ttStop(me);
  const msg = String(lastErr && lastErr.message || lastErr);
  const keyHint = process.env.EULER_API_KEY ? '' : ' (server owner: add a free EULER_API_KEY on Render)';
  send(res, 200, { ok: false, error: /Cannot find (module|package)|ERR_MODULE_NOT_FOUND/.test(msg) ? 'TikTok support isn\'t installed on the server yet'
    : /UserOfflineError|offline|not (currently )?live|LIVE has ended|isn't online/i.test(msg) ? `@${username} isn't live right now`
    : /too long/i.test(msg) ? 'TikTok took too long to answer, press Connect again' + keyHint
    : /rate.?limit|429|too many/i.test(msg) ? 'TikTok is busy (rate limited), try again in a minute' + keyHint
    : /empty|captcha|blocked|403|sign/i.test(msg) ? 'TikTok blocked the Kyoto server' + keyHint
    : 'Couldn\'t connect to TikTok: ' + msg.slice(0, 120) });
};
routes['POST /tiktok/disconnect'] = async (req, res, body, me) => { ttStop(me); send(res, 200, { ok: true }); };
routes['GET /tiktok/events'] = async (req, res, body, me) => {
  const t = TT.get(me);
  if (!t) return send(res, 200, { state: 'off', events: [] });
  t.lastPoll = Date.now();
  const since = Number(q(req).get('since')) || 0;
  send(res, 200, { state: t.state, username: t.username, seq: t.seq, events: t.events.filter(e => e.seq > since) });
};

// ---------------- CurseForge (the launcher's mod browser) ----------------
// CurseForge needs an API key (free: console.curseforge.com). It stays here on the server, never in the launcher.
const CF_KEY = process.env.CF_API_KEY || '';
const cfCache = new Map(); // url -> { at, body }
async function cf(pathAndQuery) {
  if (!CF_KEY) { const e = new Error('CurseForge isn\'t set up on the Kyoto server yet (CF_API_KEY)'); e.code = 503; throw e; }
  const hit = cfCache.get(pathAndQuery);
  if (hit && Date.now() - hit.at < 5 * 60000) return hit.body;
  const r = await fetch('https://api.curseforge.com' + pathAndQuery, { headers: { 'x-api-key': CF_KEY, Accept: 'application/json', 'User-Agent': 'KyotoServer/1.0' } });
  if (!r.ok) { const e = new Error('CurseForge said ' + r.status); e.code = 502; throw e; }
  const body = await r.json();
  cfCache.set(pathAndQuery, { at: Date.now(), body });
  if (cfCache.size > 400) cfCache.delete(cfCache.keys().next().value);
  return body;
}
const CF_LOADER = { forge: 1, fabric: 4, quilt: 5, neoforge: 6 };
const cfRoute = fn => async (req, res) => { try { send(res, 200, await fn(q(req))); } catch (e) { fail(res, e.code || 500, e.message); } };
routes['GET /cf/search'] = cfRoute(p => {
  const s = new URLSearchParams({ gameId: '432', classId: String(int(p.get('classId'), 1, 99999, 6)), searchFilter: String(p.get('q') || '').slice(0, 80),
    sortField: String(int(p.get('sort'), 1, 12, 2)), sortOrder: 'desc', index: String(int(p.get('index'), 0, 9980, 0)), pageSize: String(int(p.get('pageSize'), 1, 50, 20)) });
  if (/^[\w.-]{1,20}$/.test(p.get('gameVersion') || '')) s.set('gameVersion', p.get('gameVersion'));
  if (CF_LOADER[p.get('loader')]) s.set('modLoaderType', String(CF_LOADER[p.get('loader')]));
  if (/^\d{1,8}$/.test(p.get('categoryId') || '')) s.set('categoryId', p.get('categoryId'));
  return cf('/v1/mods/search?' + s);
});
routes['GET /cf/categories'] = cfRoute(p => cf(`/v1/categories?gameId=432&classId=${int(p.get('classId'), 1, 99999, 6)}`));
routes['GET /cf/mod'] = cfRoute(async p => {
  const id = int(p.get('id'), 1, 1e9, 0);
  const [m, d] = await Promise.all([cf(`/v1/mods/${id}`), cf(`/v1/mods/${id}/description`).catch(() => ({ data: '' }))]);
  return { data: m.data, description: d.data || '' };
});
// one exact file (modpack import): download link + name
routes['GET /cf/file'] = cfRoute(p => cf(`/v1/mods/${int(p.get('mod'), 1, 1e9, 0)}/files/${int(p.get('file'), 1, 1e10, 0)}`));
routes['GET /cf/files'] = cfRoute(p => {
  const id = int(p.get('id'), 1, 1e9, 0);
  const s = new URLSearchParams({ pageSize: '20' });
  if (/^[\w.-]{1,20}$/.test(p.get('gameVersion') || '')) s.set('gameVersion', p.get('gameVersion'));
  if (CF_LOADER[p.get('loader')]) s.set('modLoaderType', String(CF_LOADER[p.get('loader')]));
  return cf(`/v1/mods/${id}/files?` + s);
});

// ---------------- connect Discord to your Kyoto profile (OAuth, from the launcher or website) ----------------
// Render env: DISCORD_APP_ID (same app as the status), DISCORD_CLIENT_SECRET, and in the Discord app > OAuth2 > Redirects add
//   https://kyoto-server.onrender.com/link/discord/callback   (or PUBLIC_URL + /link/discord/callback)
const DISCORD_SECRET = process.env.DISCORD_CLIENT_SECRET || '';
const PUBLIC_URL = (process.env.PUBLIC_URL || 'https://kyoto-server.onrender.com').replace(/\/$/, '');
const discordStates = new Map(); // state -> { uuid, exp }
routes['POST /link/discord'] = async (req, res, body, me) => {
  if (!process.env.DISCORD_APP_ID || !DISCORD_SECRET) return fail(res, 503, 'Discord linking isn\'t set up on the server yet');
  for (const [k, v] of discordStates) if (v.exp < Date.now()) discordStates.delete(k);
  const state = crypto.randomBytes(16).toString('hex');
  discordStates.set(state, { uuid: me, exp: Date.now() + 10 * 60000 });
  const u = new URL('https://discord.com/oauth2/authorize');
  u.search = new URLSearchParams({ client_id: process.env.DISCORD_APP_ID, response_type: 'code', scope: 'identify', state, prompt: 'none',
    redirect_uri: PUBLIC_URL + '/link/discord/callback' }).toString();
  send(res, 200, { url: u.toString() });
};
const donePage = (ok, msg) => Buffer.from(`<!doctype html><meta charset=utf-8><title>Kyoto</title><body style="margin:0;height:100vh;display:grid;place-items:center;background:#07070a;color:#fff;font-family:system-ui">
<div style="text-align:center"><div style="font-size:42px">${ok ? '&#10003;' : '&#10007;'}</div><h2>${msg}</h2><p style="color:#9aa0aa">You can close this tab and go back to Kyoto.</p></div>`);
routes['GET /link/discord/callback'] = async (req, res) => {
  const p = q(req); const st = discordStates.get(p.get('state') || '');
  discordStates.delete(p.get('state') || '');
  if (!st || st.exp < Date.now() || !p.get('code')) return send(res, 400, donePage(false, 'That link expired, try again'), 'text/html');
  try {
    const tr = await fetch('https://discord.com/api/oauth2/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: process.env.DISCORD_APP_ID, client_secret: DISCORD_SECRET, grant_type: 'authorization_code', code: p.get('code'), redirect_uri: PUBLIC_URL + '/link/discord/callback' }) });
    const tok = await tr.json();
    if (!tok.access_token) throw new Error('no token');
    const ur = await fetch('https://discord.com/api/users/@me', { headers: { Authorization: 'Bearer ' + tok.access_token } });
    const du = await ur.json();
    if (!du.id) throw new Error('no user');
    const prof = db.profiles[st.uuid] || (db.profiles[st.uuid] = {});
    prof.discord = { id: String(du.id), username: cleanStr(du.global_name || du.username, 40), at: Date.now() };
    save();
    send(res, 200, donePage(true, 'Discord connected as ' + prof.discord.username.replace(/[<>&"]/g, '')), 'text/html');
  } catch (e) { send(res, 500, donePage(false, 'Couldn\'t connect Discord'), 'text/html'); }
};

// ---------------- .kycos custom cosmetics (made in the Cosmetic Builder) ----------------
// One cosmetic per slot (head, back, arm, leg). Everyone with Kyoto downloads the ones you wear.
db.kycos = db.kycos || {};
const KYCOS_SLOTS = ['head', 'back', 'arm', 'leg'];
function checkKycos(raw) {
  const str = typeof raw === 'string' ? raw : JSON.stringify(raw || {});
  if (str.length > 450000) return { error: 'That cosmetic is too big (max 450 KB)' };
  let d; try { d = JSON.parse(str); } catch (e) { return { error: 'Not a .kycos file' }; }
  if (d.format !== 'kycos') return { error: 'Not a .kycos file' };
  if (!KYCOS_SLOTS.includes(d.slot)) return { error: 'Capes are uploaded as capes. Pick head, back, arm or leg.' };
  if (FILTER.isBad(String(d.name || ''))) return { error: 'That name isn\'t allowed' };
  const out = { format: 'kycos', version: 1, name: cleanStr(d.name || 'Cosmetic', 32), author: cleanStr(d.author || '', 16), slot: d.slot, kind: d.kind === 'texture' ? 'texture' : 'voxel', mirror: !!d.mirror };
  const nums = (a, n, lo, hi) => Array.isArray(a) && a.length === n && a.every(v => Number.isFinite(v) && v >= lo && v <= hi);
  if (out.kind === 'voxel') {
    const g = d.grid || {};
    if (!nums(g.n, 3, 1, 48) || !Number.isFinite(g.vs) || g.vs < 0.25 || g.vs > 4 || !nums(g.center, 3, -64, 64)) return { error: 'Bad voxel grid' };
    const pal = (Array.isArray(d.palette) ? d.palette : []).slice(0, 256).map(c => hex(c, '#ffffff'));
    const vox = Array.isArray(d.voxels) ? d.voxels : [];
    if (!pal.length || !vox.length || vox.length > 12000) return { error: 'A cosmetic needs 1 to 12000 voxels' };
    for (const v of vox) if (!Array.isArray(v) || (v.length !== 4 && v.length !== 5) || v[0] < 0 || v[1] < 0 || v[2] < 0 || v[0] >= g.n[0] || v[1] >= g.n[1] || v[2] >= g.n[2] || !(v[3] >= 0 && v[3] < pal.length) || (v.length === 5 && !(v[4] >= 0 && v[4] < 8))) return { error: 'Bad voxel' };
    out.grid = { n: g.n.map(Math.round), vs: g.vs, center: g.center }; out.palette = pal; out.voxels = vox.map(v => v.map(Math.round));
  } else {
    const t = d.texture || {};
    const png = Buffer.from(String(t.png || ''), 'base64');
    if (!png.slice(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) || png.length > 300000) return { error: 'The picture must be a PNG under 300 KB' };
    if (!nums(t.size, 2, 0.5, 64) || !nums(t.offset, 3, -64, 64) || !nums(t.rot, 3, -360, 360)) return { error: 'Bad picture placement' };
    out.texture = { png: png.toString('base64'), size: t.size, offset: t.offset, rot: t.rot };
  }
  // animation: up to 8 moving parts, each with keyframes (rotation, offset, scale) over a looping timeline
  if (d.anim && typeof d.anim === 'object') {
    const a = d.anim, len = Number(a.len);
    if (!(len >= 0.1 && len <= 30) || !Array.isArray(a.groups) || a.groups.length > 8) return { error: 'Bad animation' };
    const EASES = ['linear', 'smooth', 'bounce', 'step', 'elastic'];
    const groups = [];
    for (const gr of a.groups) {
      if (!gr || !nums(gr.pivot, 3, -64, 64) || !Array.isArray(gr.keys) || gr.keys.length > 48) return { error: 'Bad animation part' };
      const keys = [];
      for (const k of gr.keys) {
        if (!k || !(k.t >= 0 && k.t <= len) || !nums(k.r, 3, -1440, 1440) || !nums(k.p, 3, -48, 48) || !(k.s >= 0.05 && k.s <= 4)) return { error: 'Bad keyframe' };
        keys.push({ t: +k.t, r: k.r.map(Number), p: k.p.map(Number), s: +k.s });
      }
      keys.sort((x, y) => x.t - y.t);
      groups.push({ name: cleanStr(gr.name || 'Part', 20), pivot: gr.pivot.map(Number), mirror: !!gr.mirror, ease: EASES.includes(gr.ease) ? gr.ease : 'smooth', keys });
    }
    out.anim = { len, loop: a.loop !== false, groups };
  }
  return { json: JSON.stringify(out), slot: out.slot };
}
routes['GET /kycos'] = async (req, res) => {
  const u = String(q(req).get('uuid') || ''); const k = db.kycos[u];
  if (!k || isBanned(u)) return send(res, 200, { slots: {}, version: 0 });
  send(res, 200, { slots: k.slots, version: k.version });
};
routes['PUT /kycos'] = async (req, res, body, me) => {
  if (isBanned(me)) return fail(res, 403, 'Banned');
  const k = db.kycos[me] || (db.kycos[me] = { slots: {}, version: 0 });
  if (body.remove) { if (KYCOS_SLOTS.includes(body.slot)) delete k.slots[body.slot]; else k.slots = {}; }
  else {
    const c = checkKycos(body.data);
    if (c.error) return fail(res, 400, c.error);
    k.slots[c.slot] = JSON.parse(c.json);
  }
  k.version = Date.now(); save();
  send(res, 200, { ok: true, slots: Object.keys(k.slots) });
};
routes['POST /kycos/equip'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(body.id) && x.type === 'kycos' && !x.pending);
  if (!p) return fail(res, 404, 'That cosmetic is gone');
  const k = db.kycos[me] || (db.kycos[me] = { slots: {}, version: 0 });
  const d = JSON.parse(p.data); k.slots[d.slot] = d; k.version = Date.now(); save();
  send(res, 200, { ok: true, slot: d.slot });
};
routes['GET /hub/kycos'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(q(req).get('id')) && x.type === 'kycos');
  if (!p || (p.pending && !isStaff(me) && p.uuid !== me)) return fail(res, 404, 'gone');
  send(res, 200, { data: p.data });
};
// Skins for the builder (Mojang doesn't allow browsers to load them directly).
const skinCache = new Map();
routes['GET /mcskin'] = async (req, res) => {
  const name = String(q(req).get('name') || '');
  if (!/^[A-Za-z0-9_]{2,16}$/.test(name)) return fail(res, 400, 'Bad name');
  const hit = skinCache.get(name.toLowerCase());
  if (hit && Date.now() - hit.at < 10 * 60000) { res.setHeader('X-Skin-Slim', hit.slim ? '1' : '0'); return send(res, 200, hit.buf, 'image/png'); }
  try {
    const pr = await fetch('https://api.mojang.com/users/profiles/minecraft/' + name); if (!pr.ok) return fail(res, 404, 'No player with that name');
    const { id } = await pr.json();
    const sr = await fetch('https://sessionserver.mojang.com/session/minecraft/profile/' + id); const sp = await sr.json();
    const tex = JSON.parse(Buffer.from(sp.properties.find(x => x.name === 'textures').value, 'base64').toString()).textures.SKIN;
    const ir = await fetch(tex.url.replace('http://', 'https://')); const buf = Buffer.from(await ir.arrayBuffer());
    const slim = !!(tex.metadata && tex.metadata.model === 'slim');
    skinCache.set(name.toLowerCase(), { at: Date.now(), buf, slim }); if (skinCache.size > 300) skinCache.delete(skinCache.keys().next().value);
    res.setHeader('X-Skin-Slim', slim ? '1' : '0');
    send(res, 200, buf, 'image/png');
  } catch (e) { fail(res, 502, 'Couldn\'t get that skin'); }
};

// ---------------- Kyoto Studio collab links: live co-editing (in memory, rooms close after 30 min idle) ----------------
// Each editor sends small "ops" (voxel/pixel/settings changes) and polls for everyone else's. A snapshot lets late joiners catch up.
const COLLAB = new Map(); // code -> session
const COLLAB_COLOURS = ['#ff8fc8', '#6ee7b7', '#7cc4ff', '#ffd166', '#c39bff', '#ff9f6e', '#5eead4', '#f472b6'];
function collabSweep() { const now = Date.now(); for (const [c, s] of COLLAB) { for (const [id, m] of s.members) if (now - m.at > 15000) s.members.delete(id); if (now - s.seen > 30 * 60000) COLLAB.delete(c); } }
setInterval(collabSweep, 10000).unref();
const collabMembers = s => [...s.members].map(([id, m]) => ({ id, name: m.name, color: m.color, cursor: m.cursor, mode: m.mode, host: id === s.host }));
function collabMember(s, cid, me, body) {
  let m = s.members.get(cid);
  if (!m) {
    if (s.members.size >= 8) return null;
    const used = new Set([...s.members.values()].map(x => x.color));
    m = { name: me ? nameOf(me) : 'Guest ' + cid.slice(-4).toUpperCase(), color: COLLAB_COLOURS.find(c => !used.has(c)) || COLLAB_COLOURS[0], at: 0, cursor: null, mode: '' };
    s.members.set(cid, m);
  }
  m.at = Date.now(); if (body.cursor !== undefined) m.cursor = Array.isArray(body.cursor) ? body.cursor.slice(0, 4).map(Number) : null; if (body.mode) m.mode = String(body.mode).slice(0, 10);
  return m;
}
const collabCid = b => /^[a-z0-9]{6,24}$/.test(String(b.cid || '')) ? String(b.cid) : null;
routes['POST /collab/create'] = async (req, res, body, me) => {
  const cid = collabCid(body); if (!cid) return fail(res, 400, 'Bad client');
  if (slowmode('collab' + (me || cid), 5000)) return fail(res, 429, 'Wait a few seconds');
  if (COLLAB.size > 300) collabSweep();
  if (COLLAB.size > 300) return fail(res, 503, 'Too many collab rooms right now, try again soon');
  let code; do code = Array.from({ length: 6 }, () => 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'[Math.random() * 32 | 0]).join(''); while (COLLAB.has(code));
  const snap = JSON.stringify(body.state || {}); if (snap.length > 3500000) return fail(res, 400, 'Project too big to share live');
  const s = { code, host: cid, created: Date.now(), seen: Date.now(), members: new Map(), ops: [], seq: 0, snapshot: snap, snapSeq: 0, locked: false };
  COLLAB.set(code, s); collabMember(s, cid, me, body);
  send(res, 200, { code, seq: 0, members: collabMembers(s) });
};
routes['POST /collab/join'] = async (req, res, body, me) => {
  const cid = collabCid(body); const s = COLLAB.get(String(body.code || '').toUpperCase());
  if (!cid) return fail(res, 400, 'Bad client'); if (!s) return fail(res, 404, 'That collab link expired or doesn\'t exist');
  if (s.locked && !s.members.has(cid)) return fail(res, 403, 'The host locked this room');
  if (!collabMember(s, cid, me, body)) return fail(res, 403, 'The room is full (8 max)');
  s.seen = Date.now();
  send(res, 200, { code: s.code, snapshot: s.snapshot, seq: s.seq, ops: s.ops.filter(o => o.seq > s.snapSeq), members: collabMembers(s), host: s.host === cid });
};
routes['POST /collab/sync'] = async (req, res, body, me) => {
  const cid = collabCid(body); const s = COLLAB.get(String(body.code || '').toUpperCase());
  if (!cid || !s) return send(res, 200, { gone: true });
  if (!s.members.has(cid) && s.locked) return send(res, 200, { gone: true });
  if (!collabMember(s, cid, me, body)) return send(res, 200, { gone: true });
  s.seen = Date.now();
  const ops = Array.isArray(body.ops) ? body.ops.slice(0, 400) : [];
  for (const op of ops) {
    const j = JSON.stringify(op); if (j.length > 600000) continue;
    s.ops.push({ seq: ++s.seq, by: cid, op });
  }
  if (s.ops.length > 6000) s.ops.splice(0, s.ops.length - 6000);
  const since = Number(body.since) || 0;
  if (s.ops.length && since < s.ops[0].seq - 1 && since < s.snapSeq) return send(res, 200, { resync: true, snapshot: s.snapshot, seq: s.seq, ops: s.ops.filter(o => o.seq > s.snapSeq), members: collabMembers(s) });
  send(res, 200, { seq: s.seq, ops: s.ops.filter(o => o.seq > since && o.by !== cid), members: collabMembers(s), locked: s.locked });
};
// any member can refresh the snapshot (the host does it every 20 s) so late joiners load fast
routes['POST /collab/snapshot'] = async (req, res, body, me) => {
  const cid = collabCid(body); const s = COLLAB.get(String(body.code || '').toUpperCase());
  if (!cid || !s || !s.members.has(cid)) return fail(res, 404, 'gone');
  const snap = JSON.stringify(body.state || {}); if (snap.length > 3500000) return fail(res, 400, 'Too big');
  s.snapshot = snap; s.snapSeq = Math.min(Number(body.seq) || 0, s.seq);
  send(res, 200, { ok: true });
};
routes['POST /collab/leave'] = async (req, res, body) => {
  const cid = collabCid(body); const s = COLLAB.get(String(body.code || '').toUpperCase());
  if (s && cid) { s.members.delete(cid); if (cid === s.host && body.close) COLLAB.delete(s.code); }
  send(res, 200, { ok: true });
};
routes['POST /collab/lock'] = async (req, res, body) => {
  const cid = collabCid(body); const s = COLLAB.get(String(body.code || '').toUpperCase());
  if (!s || s.host !== cid) return fail(res, 403, 'Only the host can do that');
  s.locked = !!body.locked; send(res, 200, { ok: true, locked: s.locked });
};

// ---------------- public Store (website /store): approved community capes + builds, no login needed to look ----------------
const isListing = p => (p.type === 'cape' && p.image) || p.type === 'kycos' || p.type === 'pack';
db.lib = db.lib || {};       // uuid -> [item ids you own]
db.coins = db.coins || {};   // uuid -> coins
const owns = (u, id) => !!u && (db.lib[u] || []).includes(id);
const storeOut = (p, me) => ({ id: p.id, type: p.type, title: p.title, name: nameOf(p.uuid), creator: nameOf(p.uuid), desc: p.desc || p.text || '',
  price: p.price || 0, photos: (p.photos || []).length, likes: p.likes.length, liked: !!me && p.likes.includes(me), slot: p.slot || (p.type === 'cape' ? 'cape' : ''),
  at: p.at, gif: p.image === 'gif', owned: owns(me, p.id) || p.uuid === me, owners: p.owners || 0, pending: !!p.pending,
  items: p.type === 'pack' ? packItems(p).map(x => ({ id: x.id, type: x.type, title: x.title, slot: x.slot || (x.type === 'cape' ? 'cape' : ''), photos: (x.photos || []).length, gif: x.image === 'gif' })) : undefined,
  worth: p.type === 'pack' ? packItems(p).reduce((a, x) => a + (x.price || 0), 0) : undefined,
  wearing: !!me && (p.type === 'cape' ? (db.cosmetics[me] || {}).capeItem === p.id && !!(db.cosmetics[me] || {}).hasCape : !!(db.kycos[me] && db.kycos[me].items && db.kycos[me].items[p.slot] === p.id)) });
// a pack is a bundle of a creator's own items, sold together (usually cheaper than buying them one by one)
function packItems(p) { return (p.items || []).map(id => H.posts.find(x => x.id === id && x.type !== 'pack' && isListing(x) && !x.pending)).filter(Boolean); }
routes['GET /store/items'] = async (req, res, body, me) => {
  const sort = q(req).get('sort') || 'top';
  const mine = q(req).get('mine') === '1';
  const list = H.posts.filter(p => isListing(p) && (!p.pending || (me && p.uuid === me)) && (!mine || p.uuid === me || owns(me, p.id)))
    .sort(sort === 'new' ? (a, b) => b.at - a.at : sort === 'owned' ? (a, b) => (b.owners || 0) - (a.owners || 0) : (a, b) => b.likes.length - a.likes.length || b.at - a.at).slice(0, 200)
    .map(p => storeOut(p, me));
  send(res, 200, { items: list, coins: me ? (db.coins[me] || 0) : null });
};
routes['GET /store/item'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(q(req).get('id')) && isListing(x) && (!x.pending || x.uuid === me || isStaff(me)));
  if (!p) return fail(res, 404, 'That item is gone');
  send(res, 200, storeOut(p, me));
};
routes['GET /store/photo'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(q(req).get('id')) && isListing(x));
  const n = Number(q(req).get('n')) || 0;
  if (!p || !p.photos || !p.photos[n] || (p.pending && p.uuid !== me && !isStaff(me))) return fail(res, 404, 'no photo');
  const f = path.join(HUB_IMG, p.photos[n]); if (!fs.existsSync(f)) return fail(res, 404, 'no photo');
  const buf = fs.readFileSync(f);
  res.setHeader('Cache-Control', 'public, max-age=3600');
  send(res, 200, buf, buf[0] === 0x89 ? 'image/png' : buf[0] === 0x47 ? 'image/gif' : buf[0] === 0x52 ? 'image/webp' : 'image/jpeg');
};
function imgKind(buf) {
  if (buf.length > 8 && buf[0] === 0x89 && buf[1] === 0x50) return 'png';
  if (buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8) return 'jpg';
  if (buf.length > 3 && buf.slice(0, 3).toString() === 'GIF') return 'gif';
  if (buf.length > 12 && buf.slice(0, 4).toString() === 'RIFF' && buf.slice(8, 12).toString() === 'WEBP') return 'webp';
  return null;
}
// Create a Store listing (website): name, description, price (coins, 0 = free), up to 4 photos, and either a .kycos build or a cape image.
routes['POST /store/listing'] = async (req, res, body, me) => {
  if (isBanned(me)) return fail(res, 403, 'Banned');
  const type = body.type === 'cape' ? 'cape' : body.type === 'pack' ? 'pack' : 'kycos';
  const title = clean(body.name, 40), desc = clean(body.desc, 600);
  if (!title) return fail(res, 400, 'Give it a name');
  if (!checkText(res, me, title + ' ' + desc)) return;
  if (!isStaff(me) && slowmode(me + 'listing', 20000)) return fail(res, 429, 'Wait a few seconds between listings');
  const price = Math.max(0, Math.min(100000, Math.round(Number(body.price) || 0)));
  const post = { id: H.seq++, type, uuid: me, title, text: desc, desc, price, at: Date.now(), likes: [], owners: 0, photos: [] };
  if (type === 'pack') {
    const ids = [...new Set((Array.isArray(body.items) ? body.items : []).map(Number))].slice(0, 12);
    const mine = ids.map(id => H.posts.find(x => x.id === id && x.type !== 'pack' && isListing(x) && x.uuid === me));
    if (ids.length < 2 || mine.some(x => !x)) return fail(res, 400, 'A pack needs 2 to 12 of your own Store items');
    if (mine.some(x => x.pending)) return fail(res, 400, 'Wait until every item in the pack is approved');
    post.items = ids; post.slot = 'pack';
  } else if (type === 'kycos') {
    const k = checkKycos(body.data);
    if (k.error) return fail(res, 400, k.error);
    post.data = k.json; post.slot = k.slot;
  } else {
    const buf = Buffer.from(String(body.image || ''), 'base64'), kind = imgKind(buf);
    if (kind !== 'png' && kind !== 'gif') return fail(res, 400, 'Capes must be a PNG or GIF (make one from any picture on the Kyoto Studio page)');
    if (buf.length > 4000000) return fail(res, 400, 'Cape is too big (max 4 MB)');
    post.image = kind;
    fs.writeFileSync(path.join(HUB_IMG, String(post.id)), buf);
  }
  for (const [i, b64] of (Array.isArray(body.photos) ? body.photos : []).slice(0, 4).entries()) {
    const buf = Buffer.from(String(b64 || ''), 'base64'), kind = imgKind(buf);
    if (!kind || buf.length > 2500000) return fail(res, 400, `Photo ${i + 1} must be a PNG, JPG, GIF or WEBP under 2.5 MB`);
    const id = `p${post.id}_${i}`; fs.writeFileSync(path.join(HUB_IMG, id), buf); post.photos.push(id);
  }
  post.pending = REQUIRE_APPROVAL && !isStaff(me);
  H.posts.push(post);
  (db.lib[me] = db.lib[me] || []).push(post.id); // you always own what you made
  save(); send(res, 200, { ok: true, id: post.id, pending: post.pending });
};
// "Get" an item: it goes into your Kyoto account (your Locker), no downloads.
routes['POST /store/get'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(body.id) && isListing(x) && !x.pending);
  if (!p) return fail(res, 404, 'That item is gone');
  const lib = db.lib[me] = db.lib[me] || [];
  if (lib.includes(p.id) || p.uuid === me) return send(res, 200, { ok: true, owned: true, coins: db.coins[me] || 0 });
  // a pack only charges for what you don't own yet (never more than its price)
  const price = p.type === 'pack' ? Math.min(p.price || 0, packItems(p).filter(x => !lib.includes(x.id) && x.uuid !== me).reduce((a, x) => a + (x.price || 0), 0)) : (p.price || 0);
  if (price > 0) {
    if ((db.coins[me] || 0) < price) return fail(res, 402, `You need ${price} coins (you have ${db.coins[me] || 0})`);
    db.coins[me] -= price;
    db.coins[p.uuid] = (db.coins[p.uuid] || 0) + Math.floor(price * 0.8); // the creator gets 80%
  }
  lib.push(p.id); p.owners = (p.owners || 0) + 1;
  if (p.type === 'pack') for (const x of packItems(p)) if (!lib.includes(x.id) && x.uuid !== me) { lib.push(x.id); x.owners = (x.owners || 0) + 1; }
  save(); send(res, 200, { ok: true, owned: true, coins: db.coins[me] || 0, items: p.type === 'pack' ? packItems(p).length : undefined });
};
// Your Locker: everything you own, and what you're wearing.
routes['GET /library'] = async (req, res, body, me) => {
  const ids = new Set(db.lib[me] || []);
  const items = H.posts.filter(p => isListing(p) && (ids.has(p.id) || p.uuid === me)).map(p => storeOut(p, me));
  send(res, 200, { items: items.sort((a, b) => b.at - a.at), coins: db.coins[me] || 0 });
};
routes['POST /library/wear'] = async (req, res, body, me) => {
  const p = H.posts.find(x => x.id === Number(body.id) && isListing(x));
  if (!p) return fail(res, 404, 'That item is gone');
  if (!owns(me, p.id) && p.uuid !== me) return fail(res, 403, 'Get it from the Store first');
  if (p.pending && p.uuid !== me) return fail(res, 403, 'Still waiting for approval');
  const wearOne = x => {
    if (x.type === 'kycos') { const k = db.kycos[me] || (db.kycos[me] = { slots: {}, version: 0 }); k.slots[x.slot] = JSON.parse(x.data); k.items = k.items || {}; k.items[x.slot] = x.id; k.version = Date.now(); }
    else { const c = db.cosmetics[me] || (db.cosmetics[me] = { hasCape: false, capeType: 'png', accessory: 0 }); fs.copyFileSync(path.join(HUB_IMG, String(x.id)), path.join(CAPES, me.replace(/[^a-f0-9-]/g, ''))); c.hasCape = true; c.capeType = x.image === 'gif' ? 'gif' : 'png'; c.capeItem = x.id; c.version = Date.now(); }
  };
  if (p.type === 'pack') { const all = packItems(p); for (const x of all) wearOne(x); save(); return send(res, 200, { ok: true, slot: 'pack', worn: all.length }); }
  if (p.type === 'kycos') {
    const k = db.kycos[me] || (db.kycos[me] = { slots: {}, version: 0 });
    k.slots[p.slot] = JSON.parse(p.data); k.items = k.items || {}; k.items[p.slot] = p.id; k.version = Date.now();
  } else {
    const c = db.cosmetics[me] || (db.cosmetics[me] = { hasCape: false, capeType: 'png', accessory: 0 });
    fs.copyFileSync(path.join(HUB_IMG, String(p.id)), path.join(CAPES, me.replace(/[^a-f0-9-]/g, '')));
    c.hasCape = true; c.capeType = p.image === 'gif' ? 'gif' : 'png'; c.capeItem = p.id; c.version = Date.now();
  }
  save(); send(res, 200, { ok: true, slot: p.type === 'cape' ? 'cape' : p.slot });
};
routes['POST /library/unwear'] = async (req, res, body, me) => {
  const slot = String(body.slot || '');
  if (slot === 'cape') { const c = db.cosmetics[me]; if (c) { c.hasCape = false; c.capeItem = 0; c.version = Date.now(); } }
  else { const k = db.kycos[me]; if (k) { delete k.slots[slot]; if (k.items) delete k.items[slot]; k.version = Date.now(); } }
  save(); send(res, 200, { ok: true });
};
routes['POST /mod/coins'] = async (req, res, body, me) => {
  if (!ownerOnly(res, me)) return;
  const u = byName(body.name); if (!u) return fail(res, 404, 'No Kyoto player with that name');
  db.coins[u] = Math.max(0, (db.coins[u] || 0) + Math.round(Number(body.amount) || 0));
  audit(me, 'coins', nameOf(u), String(body.amount)); save(); send(res, 200, { ok: true, coins: db.coins[u] });
};
routes['GET /store/image'] = async (req, res) => {
  const p = H.posts.find(x => x.id === Number(q(req).get('id')) && x.type === 'cape' && !x.pending && x.image);
  if (!p) return fail(res, 404, 'gone');
  res.setHeader('Cache-Control', 'public, max-age=3600');
  send(res, 200, fs.readFileSync(path.join(HUB_IMG, String(p.id))), p.image === 'gif' ? 'image/gif' : 'image/png');
};
routes['GET /store/kycos'] = async (req, res) => {
  const p = H.posts.find(x => x.id === Number(q(req).get('id')) && x.type === 'kycos' && !x.pending);
  if (!p) return fail(res, 404, 'gone');
  send(res, 200, { data: p.data });
};

// ---------------- staff panel (website: /mod) ----------------
// Owners (OWNERS env var) are always staff. Owners can add moderators from the panel.
// Moderators can: approve/reject capes, delete posts + chat, handle reports, mute, ban (not other staff), reset profiles.
const ownerOnly = (res, me) => { if (!isOwnerUuid(me)) { fail(res, 403, 'Owners only'); return false; } return true; };
const staffOnly = (res, me) => { if (!isStaff(me)) { fail(res, 403, 'Staff only'); return false; } return true; };
function audit(me, action, target, detail) {
  H.audit.push({ at: Date.now(), by: me, action, target: target || '', detail: String(detail || '').slice(0, 160) });
  if (H.audit.length > 1000) H.audit.shift();
}
const banOf = u => Object.assign({ name: nameOf(u), uuid: u }, H.banInfo[u] || {}, { byName: H.banInfo[u] ? nameOf(H.banInfo[u].by) : '' });
function canPunish(res, me, u) {
  if (!u) { fail(res, 404, 'No Kyoto player with that name'); return false; }
  if (u === me) { fail(res, 400, "You can't do that to yourself"); return false; }
  if (isOwnerUuid(u)) { fail(res, 403, "Owners can't be punished"); return false; }
  if (isStaff(u) && !isOwnerUuid(me)) { fail(res, 403, 'Only an owner can punish staff'); return false; }
  return true;
}
function reportTarget(r) {
  if (r.kind === 'chat') { const m = H.chat.find(x => String(x.id) === String(r.id)); return m ? { name: m.uuid ? nameOf(m.uuid) : m.dname + ' (Discord)', text: m.text, chatImg: m.img || null, gone: false } : { gone: true }; }
  if (r.kind === 'review') { const [ip, n] = String(r.id).split('|'); const sv = (H.servers || {})[ip]; const u = byName(n); const rv = sv && u && sv.reviews[u]; return rv ? { name: n, text: `${rv.stars}/5 on ${ip}: ${rv.text}`, gone: false } : { gone: true }; }
  if (r.kind === 'user' || r.kind === 'profile') { const u = byName(r.id); return { name: u ? nameOf(u) : r.id, text: u && db.profiles[u] ? (db.profiles[u].bio || '') : '', gone: !u }; }
  const p = H.posts.find(x => String(x.id) === String(r.id));
  return p ? { name: nameOf(p.uuid), text: [p.title, p.text].filter(Boolean).join(' - '), image: p.image ? p.id : null, type: p.type, gone: false } : { gone: true };
}
routes['GET /mod/me'] = async (req, res, body, me) => send(res, 200, { name: nameOf(me), staff: isStaff(me), owner: isOwnerUuid(me) });
routes['GET /mod/overview'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  const day = Date.now() - 86400000;
  send(res, 200, {
    users: Object.keys(db.users).length, online: onlineCount(), profiles: Object.keys(db.profiles).length,
    newToday: Object.values(db.users).filter(u => (u.joined || 0) > day).length,
    chatToday: H.chat.filter(m => m.at > day).length, posts: H.posts.length,
    pending: H.posts.filter(p => p.pending).length, reports: H.reports.length, bans: H.bans.filter(u => isBanned(u)).length,
    mutes: Object.keys(H.mutes).filter(u => isMuted(u)).length,
    staff: OWNERS.map(n => ({ name: n, role: 'owner' })).concat(H.staff.map(u => ({ name: nameOf(u), role: 'mod' }))),
    recent: H.audit.slice(-8).reverse().map(x => Object.assign({ byName: nameOf(x.by) }, x))
  });
};
routes['GET /mod/queue'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  H.bans.forEach(isBanned); // drops expired bans
  send(res, 200, {
    pending: H.posts.filter(p => p.pending).map(p => pub(p, me)),
    reports: H.reports.slice(-100).reverse().map(r => Object.assign({ byName: nameOf(r.by), target: reportTarget(r) }, r)),
    bans: H.bans.map(nameOf), banList: H.bans.map(banOf)
  });
};
routes['POST /mod/approve'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const p = H.posts.find(x => x.id === Number(body.id)); if (!p) return fail(res, 404, 'Already gone');
  p.pending = false; audit(me, 'approve', nameOf(p.uuid), p.title); save(); send(res, 200, { ok: true });
};
routes['POST /mod/delete'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  if (body.kind === 'review') {
    const [ip, n] = String(body.id).split('|'); const sv = (H.servers || {})[ip], u = byName(n);
    if (!sv || !u || !sv.reviews[u]) return fail(res, 404, 'Already gone');
    delete sv.reviews[u]; audit(me, 'delete review', n, ip);
  } else if (body.kind === 'chat') {
    const m = H.chat.find(x => String(x.id) === String(body.id)); if (!m) return fail(res, 404, 'Already gone');
    H.chat = H.chat.filter(x => x !== m); audit(me, 'delete chat', m.uuid ? nameOf(m.uuid) : m.dname + ' (Discord)', m.text || '[picture]');
    if (m.img) try { fs.unlinkSync(path.join(HUB_IMG, 'c' + m.img)); } catch (e) { /* gone */ }
  } else {
    const p = H.posts.find(x => String(x.id) === String(body.id)); if (!p) return fail(res, 404, 'Already gone');
    H.posts = H.posts.filter(x => x !== p); try { fs.unlinkSync(path.join(HUB_IMG, String(p.id))); } catch (e) { /* none */ }
    for (const ph of p.photos || []) try { fs.unlinkSync(path.join(HUB_IMG, ph)); } catch (e) { /* none */ }
    audit(me, p.pending ? 'reject' : 'delete post', nameOf(p.uuid), p.title || p.text);
  }
  H.reports = H.reports.filter(r => !(String(r.id) === String(body.id) && (r.kind === 'chat') === (body.kind === 'chat')));
  save(); send(res, 200, { ok: true });
};
routes['POST /mod/report/dismiss'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  H.reports = H.reports.filter(r => !(r.at === Number(body.at) && r.by === body.by)); audit(me, 'dismiss report', '', ''); save(); send(res, 200, { ok: true });
};
routes['POST /mod/clearreports'] = async (req, res, body, me) => { if (!staffOnly(res, me)) return; H.reports = []; audit(me, 'clear reports'); save(); send(res, 200, { ok: true }); };
routes['POST /mod/ban'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const u = byName(body.name); if (!canPunish(res, me, u)) return;
  const hours = Math.max(0, Math.min(24 * 365, Number(body.hours) || 0));
  if (!H.bans.includes(u)) H.bans.push(u);
  H.banInfo[u] = { reason: cleanStr(body.reason, 160) || 'No reason given', by: me, at: Date.now(), until: hours ? Date.now() + hours * 3600000 : 0 };
  if (body.wipe !== false) { H.posts = H.posts.filter(p => p.uuid !== u); H.chat = H.chat.filter(m => m.uuid !== u); }
  audit(me, 'ban', nameOf(u), (hours ? hours + 'h: ' : 'permanent: ') + H.banInfo[u].reason);
  save(); send(res, 200, { ok: true });
};
routes['POST /mod/unban'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const u = byName(body.name); if (!u) return fail(res, 404, 'Unknown player');
  H.bans = H.bans.filter(x => x !== u); delete H.banInfo[u]; audit(me, 'unban', nameOf(u)); save(); send(res, 200, { ok: true });
};
routes['POST /mod/mute'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const u = byName(body.name); if (!canPunish(res, me, u)) return;
  const mins = Math.max(1, Math.min(60 * 24 * 30, Number(body.minutes) || 60));
  H.mutes[u] = Date.now() + mins * 60000; audit(me, 'mute', nameOf(u), mins + ' min'); save(); send(res, 200, { ok: true });
};
routes['POST /mod/unmute'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const u = byName(body.name); if (!u) return fail(res, 404, 'Unknown player');
  delete H.mutes[u]; audit(me, 'unmute', nameOf(u)); save(); send(res, 200, { ok: true });
};
routes['POST /mod/profile/reset'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; const u = byName(body.name); if (!canPunish(res, me, u)) return;
  for (const k of Object.keys(MEDIA_KINDS)) { try { fs.unlinkSync(mediaFile(u, k)); } catch (e) { /* none */ } }
  const views = (db.profiles[u] || {}).views || 0; db.profiles[u] = { views };
  audit(me, 'reset profile', nameOf(u)); save(); send(res, 200, { ok: true });
};
routes['GET /mod/player'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  const u = byName(q(req).get('name')); if (!u) return fail(res, 404, 'No Kyoto player with that name');
  const pr = presence.get(u) || {}; const prof = db.profiles[u] || {};
  send(res, 200, {
    name: nameOf(u), uuid: u, joined: (db.users[u] || {}).joined || 0, online: isOnline(u), lastSeen: pr.lastSeen || 0, server: pr.server || '',
    owner: isOwnerUuid(u), staff: isStaff(u), plus: isPlus(u), plusUntil: (db.plus[u] || {}).until || 0, banned: isBanned(u), ban: isBanned(u) ? banOf(u) : null, muted: isMuted(u), mutedUntil: H.mutes[u] || 0,
    friends: list(db.friends, u).length, posts: H.posts.filter(p => p.uuid === u).length,
    profile: { displayName: prof.displayName || '', bio: prof.bio || '', location: prof.location || '', links: prof.links || [], views: prof.views || 0,
      media: Object.keys(MEDIA_KINDS).filter(k => prof[k + 'Ext']) },
    chat: H.chat.filter(m => m.uuid === u).slice(-20).reverse(),
    reports: H.reports.filter(r => { const t = reportTarget(r); return t.name && t.name.toLowerCase() === nameOf(u).toLowerCase(); }).length,
    history: H.audit.filter(x => x.target.toLowerCase() === nameOf(u).toLowerCase()).slice(-15).reverse().map(x => Object.assign({ byName: nameOf(x.by) }, x))
  });
};
routes['GET /mod/players'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  const s = String(q(req).get('q') || '').toLowerCase();
  const out = Object.entries(db.users).filter(([u, v]) => !s || v.name.toLowerCase().includes(s))
    .sort((a, b) => (isOnline(b[0]) - isOnline(a[0])) || ((b[1].joined || 0) - (a[1].joined || 0))).slice(0, 40)
    .map(([u, v]) => ({ name: v.name, online: isOnline(u), banned: isBanned(u), muted: isMuted(u), staff: isStaff(u), owner: isOwnerUuid(u), plus: isPlus(u), joined: v.joined || 0 }));
  send(res, 200, { players: out });
};
routes['GET /mod/chat'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return;
  send(res, 200, { messages: H.chat.slice(-120).reverse().map(m => Object.assign({ name: nameOf(m.uuid), staff: isStaff(m.uuid) }, m)) });
};
routes['GET /mod/audit'] = async (req, res, body, me) => {
  if (!staffOnly(res, me)) return; send(res, 200, { log: H.audit.slice(-200).reverse().map(x => Object.assign({ byName: nameOf(x.by) }, x)) });
};
routes['POST /mod/staff/add'] = async (req, res, body, me) => {
  if (!ownerOnly(res, me)) return; const u = byName(body.name); if (!u) return fail(res, 404, 'That player has to open Kyoto once first');
  if (!H.staff.includes(u) && !isOwnerUuid(u)) H.staff.push(u); audit(me, 'add staff', nameOf(u)); save(); send(res, 200, { ok: true });
};
routes['POST /mod/staff/remove'] = async (req, res, body, me) => {
  if (!ownerOnly(res, me)) return; const u = byName(body.name); H.staff = H.staff.filter(x => x !== u); audit(me, 'remove staff', nameOf(u)); save(); send(res, 200, { ok: true });
};

// ---------------- website (static files in ./site) ----------------
const SITE = path.join(__dirname, 'site');
const SITE_TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.webm': 'video/webm', '.woff2': 'font/woff2', '.ico': 'image/x-icon', '.json': 'application/json' };
function serveSite(req, res, pathname) {
  // pretty URLs: /edit, /u/<name>, /download, /features -> the single page app
  let rel = decodeURIComponent(pathname);
  // kyoto-server.onrender.com/<name or custom URL> -> that profile
  const vanity = /^\/([A-Za-z0-9_]{3,16})\/?$/.exec(rel);
  if (vanity && !WEB.RESERVED.has(vanity[1].toLowerCase()) && (WEB.bySlug(vanity[1]) || (byName(vanity[1]) && db.profiles[byName(vanity[1])]))) rel = '/index.html';
  if (/^\/(wrapped|wrapped\/[A-Za-z0-9_]{1,16}|badges|events|blog|blog\/\d{1,8}|feedback|bug|docs|docs\/[a-z0-9-]{1,30}|creator-program|me)\/?$/.test(rel)) rel = '/index.html';
  if (rel === '/' || /^\/(edit|mod|u\/[A-Za-z0-9_]{1,16}|download|features|profile|news|news\/[\w-]{1,40}|changelog|store|cosmetics|hub|hub\/(?:chat|dms|voice|lfg|servers|trades|mods)|rooms|studio|creators|leaderboards|skins|wishlist|invite|status|card|achievements|(?:creator|card|achievements|wishlist)\/[A-Za-z0-9_]{1,16})\/?$/.test(rel)) rel = '/index.html';
  if (/^\/overlay\/?$/.test(rel)) rel = '/overlay.html';
  else if (/^\/overlay\/setup\/?$/.test(rel)) rel = '/overlay-setup.html';
  if (/^\/builder\/?$/.test(rel)) rel = '/builder.html';
  if (/^\/(build|timelapse)\/?$/.test(rel)) rel = '/build.html';
  const file = path.normalize(path.join(SITE, rel));
  if (!file.startsWith(SITE) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) return false;
  const ext = path.extname(file).toLowerCase();
  if (rel === '/index.html' && pathname !== '/') {
    const html = WEB.htmlWithEmbed(fs.readFileSync(file, 'utf8'), pathname.replace(/\/$/, '') || '/');
    if (html) { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' }); res.end(html); return true; }
  }
  const stat = fs.statSync(file);
  const headers = { 'Content-Type': SITE_TYPES[ext] || 'application/octet-stream', 'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=600' };
  // videos need range requests so browsers can seek/stream them
  const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
  if (range && (ext === '.mp4' || ext === '.webm')) {
    const start = range[1] ? Number(range[1]) : 0, end = range[2] ? Math.min(Number(range[2]), stat.size - 1) : stat.size - 1;
    res.writeHead(206, Object.assign(headers, { 'Content-Range': `bytes ${start}-${end}/${stat.size}`, 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 }));
    fs.createReadStream(file, { start, end }).pipe(res); return true;
  }
  res.writeHead(200, Object.assign(headers, { 'Content-Length': stat.size, 'Accept-Ranges': 'bytes' }));
  fs.createReadStream(file).pipe(res); return true;
}

// ---------------- social: trades, voice rooms, server reviews, LFG, polls, stickers, status, streaks, group DMs, reactions, pins, gifts ----------------
const SOCIAL = require('./social')({ routes, db, H, send, fail, clean, nameOf, byName, list, areFriends, isStaff, isBanned, checkText, slowmode, save, q, imgKind,
  HUB_IMG, DM_IMG, presence, isOnline, isOwnerUuid, isPlus, OWNERS, toDiscord, pushChat, isListing, PUBLIC_URL, readSigned });
// ---------------- community: creators, themes, rooms, cards, leaderboards, achievements, skins, wishlist, referrals, status page ----------------
const COMMUNITY = require('./community')({ routes, db, H, send, fail, clean, nameOf, byName, list, areFriends, isStaff, isBanned, checkText, slowmode, save, q,
  presence, isOnline, isOwnerUuid, isPlus, isListing, storeOut, SOCIAL, DATA, SKINS, PUBLIC_URL, onlineCount, OWNERS,
  discordInfo: () => ({ configured: !!(DISCORD_WEBHOOK || (DISCORD_BOT && DISCORD_CHANNEL)), lastError: DISCORD_BOT && DISCORD_CHANNEL ? discordState.lastError : '' }) });
const EXTRAS = require('./extras')({ routes, db, send, fail, nameOf, byName, areFriends, isBanned, save, q, SOCIAL });
const notifyStaff = text => { const hook = DISCORD_REPORT_HOOK || ''; if (hook) fetch(hook, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'Kyoto', content: String(text).slice(0, 1900), allowed_mentions: { parse: [] } }) }).catch(() => {}); };
const WEB = require('./web')({ routes, db, H, send, fail, clean, nameOf, byName, isStaff, isBanned, isOwnerUuid, isPlus, checkText, slowmode, save, q, presence, isOnline, COMMUNITY, PUBLIC_URL, list, notifyStaff, DATA });

const server = http.createServer(async (req, res) => {
  try {
    const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
    // Roblox game servers poll the lyrics relay for every player in them - they get their own, bigger budget
    if (limited(req.url.startsWith('/blox/np') ? 'blox:' + ip : ip) && !(req.url.startsWith('/blox/np') && (hits.get('blox:' + ip) || {}).n <= 1500)) return fail(res, 429, 'slow down');
    // the website may be hosted somewhere else (Netlify, GitHub Pages...) - let it call the API
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Expose-Headers', 'X-Skin-Slim');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Admin-Key');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    const url = new URL(req.url, 'http://x');
    // short overlay links (/o/abc1234) for apps that don't accept long links, like TikTok LIVE Studio
    const ov = req.method === 'GET' && /^\/o\/([a-z0-9]{4,12})\/?$/.exec(url.pathname);
    if (ov) {
      const qs = (db.overlays || {})[ov[1]];
      let html = fs.readFileSync(path.join(SITE, 'overlay.html'), 'utf8');
      if (qs !== undefined) html = html.replace('<div id="root"></div>', `<div id="root"></div><script>window.KY_Q = ${JSON.stringify(qs).replace(/</g, '\\u003c')};</script>`);
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' }); return res.end(html);
    }
    // one place to change the launcher download: every button links here
    if (req.method === 'GET' && (url.pathname === '/get/launcher' || url.pathname === '/get/launcher/')) {
      res.writeHead(302, { Location: process.env.LAUNCHER_URL || 'https://github.com/kyotoclient/kyoto-server/releases/download/v7.0.0/Kyoto.Launcher.exe', 'Cache-Control': 'no-cache' });
      return res.end();
    }
    // browsers get the website, the mod/launcher (no text/html Accept) keep getting JSON
    if (req.method === 'GET' && (String(req.headers.accept || '').includes('text/html') || /^\/(assets|img|media|fonts)\//.test(url.pathname) || /\.(css|js|png|jpg|webp|svg|mp4|webm|woff2|ico)$/.test(url.pathname))) {
      if (url.pathname !== '/admin' && serveSite(req, res, url.pathname)) return;
    }
    const jar = /^\/mods\/kyoto-([0-9][0-9a-z.\-]{0,20})\.jar$/i.exec(url.pathname);
    if (req.method === 'GET' && jar) {
      const link = jarLinks()[jar[1]];
      if (link && /^https:\/\//.test(link.url || '') && !/YOUR_GITHUB_NAME/.test(link.url)) { res.writeHead(302, { Location: link.url }); return res.end(); }
      const f = path.join(JARS, `kyoto-${jar[1]}.jar`);
      if (!fs.existsSync(f)) return fail(res, 404, 'No Kyoto build for that Minecraft version yet');
      const st = fs.statSync(f);
      res.writeHead(200, { 'Content-Type': 'application/java-archive', 'Content-Length': st.size, 'Content-Disposition': `attachment; filename="kyoto-${jar[1]}.jar"` });
      return fs.createReadStream(f).pipe(res);
    }
    const capeMatch = /^\/cape\/([a-f0-9-]{36})$/.exec(url.pathname);
    if (req.method === 'GET' && capeMatch) {
      const file = path.join(CAPES, capeMatch[1]);
      if (!fs.existsSync(file)) return fail(res, 404, 'no cape');
      const c = db.cosmetics[capeMatch[1]] || {};
      return send(res, 200, fs.readFileSync(file), c.capeType === 'gif' ? 'image/gif' : 'image/png');
    }
    const skinMatch = /^\/skin\/([a-f0-9-]{36})$/.exec(url.pathname);
    if (req.method === 'GET' && skinMatch) {
      const file = path.join(SKINS, skinMatch[1]);
      if (!fs.existsSync(file)) return fail(res, 404, 'no skin');
      return send(res, 200, fs.readFileSync(file), 'image/png');
    }
    const prof = /^\/profile\/([A-Za-z0-9_]{1,16})(?:\/(video|audio|avatar|image))?$/.exec(url.pathname);
    if (req.method === 'GET' && prof) {
      const uuid = byName(prof[1]) || WEB.bySlug(prof[1]);
      const isOwner = uuid ? OWNERS.includes(nameOf(uuid).toLowerCase()) : false;
      const p = uuid ? db.profiles[uuid] : null;
      if (prof[2]) {
        if (!uuid || !p || !p[prof[2] + 'Ext']) return fail(res, 404, 'no media');
        const file = mediaFile(uuid, prof[2]);
        if (!fs.existsSync(file)) return fail(res, 404, 'no media');
        return send(res, 200, fs.readFileSync(file), MEDIA_TYPES[p[prof[2] + 'Ext']] || 'application/octet-stream');
      }
      if (!uuid || !p || isBanned(uuid)) return fail(res, 404, 'no profile');
      if (!q(req).get('noview')) { p.views = (p.views || 0) + 1; try { WEB.onView(uuid, req); } catch (e) { /* analytics are optional */ } save(); }
      const badges = ['kyoto'];
      if (isPlus(uuid)) badges.unshift('plus');
      if (isOwner) badges.unshift('owner');
      const out = Object.assign({ name: db.users[uuid].name, badges }, p);
      if (p.showLinks === false) out.links = [];
      if (p.discord && p.showDiscord !== false) out.discordName = p.discord.username; delete out.discord;
      const cz = db.cosmetics[uuid] || {};
      out.skinUrl = cz.hasSkin ? `/skin/${uuid}?v=${cz.skinVersion || 0}` : `/mcskin?name=${encodeURIComponent(db.users[uuid].name)}`; out.slim = !!cz.slim;
      try { const L = COMMUNITY.levelOf(uuid); out.level = L.lvl; out.achievements = Object.keys(L.got).length; out.followers = L.s.followers; } catch (e) { /* stats are optional */ }
      return send(res, 200, out);
    }
    // Kyoto Blox -> your Roblox games: what someone is listening to + its synced lyrics (Kyoto Lyrics kit reads this)
    const np = /^\/blox\/np\/(\d{1,20})$/.exec(url.pathname);
    if (np && req.method === 'GET') {
      const e = BLOX_NP.get(np[1]);
      if (!e || Date.now() - e.seen > 20000) return send(res, 200, { playing: false });
      const out = { playing: e.playing, title: e.title, artist: e.artist, song: e.song, pos: e.pos + (e.playing ? Date.now() - e.seen : 0), dur: e.dur };
      if (q(req).get('have') !== e.song) out.lines = e.lines;
      return send(res, 200, out);
    }
    if (req.method === 'GET' && url.pathname.startsWith('/api/v1/') && WEB.apiPath(url.pathname, req, res)) return;
    const pk = /^\/packs\/([a-z]{2,12})$/.exec(url.pathname);
    if (pk && req.method === 'GET' && PACK_IDS.includes(pk[1])) return send(res, 200, (db.packs || {})[pk[1]] || {});
    const key = `${req.method} ${url.pathname}`;
    const handler = routes[key];
    if (!handler) return fail(res, 404, 'not found');
    const limit = key === 'PUT /profile/media' ? 22000000 : key === 'POST /store/listing' ? 16000000 : key === 'PUT /cosmetics' ? 6000000 : key.startsWith('POST /collab/') ? 4000000 : key === 'POST /groups/image' || key === 'POST /chat/image' ? 4500000 : key === 'POST /skins/post' ? 400000 : key === 'POST /bugs' ? 600000 : 2500000;
    const body = (req.method === 'POST' || req.method === 'PUT') ? await readBody(req, limit) : {};
    const open = key === 'GET /' || key === 'GET /stats' || key === 'POST /overlay/link' || key.startsWith('POST /auth/') || key === 'POST /cosmetics/bulk'
      || key === 'POST /blox/np' || key === 'POST /kofi' || key === 'GET /config' || key === 'GET /kycos' || key === 'GET /mcskin' || key === 'GET /store/items' || key === 'GET /store/image' || key === 'GET /store/kycos' || key === 'GET /store/item' || key === 'GET /store/photo' || key === 'GET /chat/img' || key === 'GET /chat' || key.startsWith('GET /cf/') || key === 'GET /link/discord/callback' || key === 'GET /news' || key === 'GET /version' || key === 'POST /web/login' || key === 'GET /mods/list' || key === 'GET /admin' || key === 'POST /news' || key === 'POST /news/delete' || key === 'POST /version' || key.startsWith('POST /collab/') || key === 'GET /servers' || key === 'GET /servers/one' || key === 'GET /lfg' || key === 'GET /voice' || key === 'GET /stickers'
      || ['GET /creators', 'GET /creator', 'GET /card', 'GET /leaderboards', 'GET /achievements', 'GET /skins', 'GET /skins/png', 'GET /status/info', 'GET /overlay/live', 'GET /chat/voice', 'GET /chat/channels', 'GET /api/v1', 'GET /api/v1/stats', 'GET /api/v1/events', 'GET /api/v1/version', 'GET /events', 'GET /events/ics', 'GET /blog', 'GET /blog/one', 'GET /feedback', 'GET /badges', 'GET /wrapped', 'GET /creator/program', 'GET /profile-extras', 'GET /slug', 'POST /bugs', 'POST /packs/set', 'GET /packs/suggestions'].includes(key);
    let me = null;
    if (!open) { me = auth(req); if (!me) return fail(res, 401, 'not logged in'); }
    else { try { me = auth(req); } catch (e) { me = null; } } // open routes: logged in is optional
    await handler(req, res, body, me);
  } catch (e) {
    console.error(e);
    if (!res.headersSent) fail(res, 500, e.message);
  }
});

// ---------------- friend calls: tiny WebSocket relay (no packages needed) ----------------
// Text frames: {t:"call"|"accept"|"hangup", name}. Binary frames: Opus audio, forwarded to your call partner.
const sockets = new Map(); // uuid -> socket
const callOf = new Map();  // uuid -> partner uuid (active)
const ringing = new Map(); // callee uuid -> caller uuid
function wsSend(sock, data, binary) {
  if (!sock || sock.destroyed) return;
  const payload = binary ? data : Buffer.from(data);
  let header;
  if (payload.length < 126) header = Buffer.from([binary ? 0x82 : 0x81, payload.length]);
  else if (payload.length < 65536) { header = Buffer.alloc(4); header[0] = binary ? 0x82 : 0x81; header[1] = 126; header.writeUInt16BE(payload.length, 2); }
  else { header = Buffer.alloc(10); header[0] = binary ? 0x82 : 0x81; header[1] = 127; header.writeBigUInt64BE(BigInt(payload.length), 2); }
  sock.write(Buffer.concat([header, payload]));
}
const wsJson = (uuid, o) => wsSend(sockets.get(uuid), JSON.stringify(o), false);
function endCall(uuid) {
  const p = callOf.get(uuid); callOf.delete(uuid);
  if (p) { callOf.delete(p); wsJson(p, { t: 'end' }); }
  for (const [callee, caller] of ringing) if (callee === uuid || caller === uuid) { ringing.delete(callee); wsJson(callee === uuid ? caller : callee, { t: 'end' }); }
}
function onWsMessage(me, op, data) {
  if (op === 0x2) { const p = callOf.get(me); if (p && data.length <= 4000) wsSend(sockets.get(p), data, true); return; }
  if (op !== 0x1) return;
  let m; try { m = JSON.parse(data.toString('utf8')); } catch (e) { return; }
  const them = m.name ? byName(m.name) : null;
  if (m.t === 'call') {
    if (!them || !areFriends(me, them)) return wsJson(me, { t: 'error', msg: 'You can only call friends' });
    if (!sockets.has(them)) return wsJson(me, { t: 'error', msg: `${nameOf(them)} isn't online with calls on` });
    if (list(H.blocks, them).includes(me)) return wsJson(me, { t: 'error', msg: "Can't call this player" });
    if (callOf.has(them) || ringing.has(them)) return wsJson(me, { t: 'error', msg: `${nameOf(them)} is busy` });
    endCall(me); ringing.set(them, me); wsJson(them, { t: 'ring', name: nameOf(me) });
    setTimeout(() => { if (ringing.get(them) === me) { ringing.delete(them); wsJson(me, { t: 'error', msg: 'No answer' }); wsJson(them, { t: 'end' }); } }, 30000);
  } else if (m.t === 'accept') {
    const caller = ringing.get(me); if (!caller || caller !== them) return;
    ringing.delete(me); callOf.set(me, caller); callOf.set(caller, me);
    wsJson(me, { t: 'start', name: nameOf(caller) }); wsJson(caller, { t: 'start', name: nameOf(me) });
  } else if (m.t === 'hangup') endCall(me);
}
server.on('upgrade', (req, sock) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/voice') return SOCIAL.voiceUpgrade(req, sock, url);
    const t = db.tokens[url.searchParams.get('token') || ''];
    const key = req.headers['sec-websocket-key'];
    if (url.pathname !== '/ws' || !t || !key) { sock.end('HTTP/1.1 401 Unauthorized\r\n\r\n'); return; }
    const me = t.uuid;
    const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    sock.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
    const old = sockets.get(me); if (old && old !== sock) old.destroy();
    sockets.set(me, sock); sock.setNoDelay(true);
    let buf = Buffer.alloc(0); let frag = null, fragOp = 0;
    sock.on('data', chunk => {
      buf = Buffer.concat([buf, chunk]);
      if (buf.length > 1e6) { sock.destroy(); return; }
      while (buf.length >= 2) {
        const fin = (buf[0] & 0x80) !== 0, op = buf[0] & 0x0f, masked = (buf[1] & 0x80) !== 0;
        let len = buf[1] & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        const need = off + (masked ? 4 : 0) + len; if (buf.length < need) return;
        let payload = buf.slice(off + (masked ? 4 : 0), need);
        if (masked) { const mk = buf.slice(off, off + 4); payload = Buffer.from(payload.map((b, i) => b ^ mk[i & 3])); }
        buf = buf.slice(need);
        if (op === 0x8) { sock.end(); return; }
        if (op === 0x9) { const h = Buffer.from([0x8a, payload.length]); sock.write(Buffer.concat([h, payload])); continue; }
        if (op === 0xA) continue;
        if (op === 0x0) { if (!frag) continue; frag = Buffer.concat([frag, payload]); if (fin) { onWsMessage(me, fragOp, frag); frag = null; } continue; }
        if (!fin) { frag = payload; fragOp = op; continue; }
        onWsMessage(me, op, payload);
      }
    });
    const bye = () => { if (sockets.get(me) === sock) { sockets.delete(me); endCall(me); } };
    sock.on('close', bye); sock.on('error', bye);
  } catch (e) { try { sock.destroy(); } catch (e2) { /* ignore */ } }
});
server.listen(PORT, () => console.log(`Kyoto server running on port ${PORT}`));
