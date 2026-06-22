package scalascript.codegen

/** WebAuthn (FIDO2) server verifier — a JS port of `scalascript.server.WebAuthn`, so
 *  `ssc emit-js`'d auth code runs standalone under Node. Emitted only when the module
 *  references `webauthn*` (Capability.WebAuthn). Reuses the always-present `_nodeCrypto`
 *  (Part2b) and the Core Option/Map values (`None`/`_Some`/`_Map`); returns ssc values so
 *  `verify… match { case None … }` works. Self-contained CBOR/COSE/base64url (`_wa*`). */
val JsRuntimeWebAuthn: String = """

// ── WebAuthn server verifier (challenge + registration + assertion) ──────────
function _waB64uToBytes(s) { return new Uint8Array(Buffer.from(String(s).replace(/-/g, '+').replace(/_/g, '/'), 'base64')); }
function _waBytesToB64u(b) { return Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }

// minimal CBOR decoder (maps, arrays, uint, negint, byte/text strings) — enough for
// attestationObject + COSE keys.
function _waCborDecode(buf) {
  buf = new Uint8Array(buf);
  var p = 0;
  function read() {
    var ib = buf[p++], mt = ib >> 5, ai = ib & 0x1f, len = ai;
    if (ai === 24) len = buf[p++];
    else if (ai === 25) { len = (buf[p] << 8) | buf[p + 1]; p += 2; }
    else if (ai === 26) { len = ((buf[p] << 24) | (buf[p + 1] << 16) | (buf[p + 2] << 8) | buf[p + 3]) >>> 0; p += 4; }
    else if (ai === 27) { var hi = ((buf[p]<<24)|(buf[p+1]<<16)|(buf[p+2]<<8)|buf[p+3])>>>0, lo = ((buf[p+4]<<24)|(buf[p+5]<<16)|(buf[p+6]<<8)|buf[p+7])>>>0; len = hi * 4294967296 + lo; p += 8; }
    else if (ai > 27) throw new Error('cbor: bad ai');
    if (mt === 0) return len;
    if (mt === 1) return -1 - len;
    if (mt === 2) { var b = buf.slice(p, p + len); p += len; return b; }
    if (mt === 3) { var b3 = buf.slice(p, p + len); p += len; return Buffer.from(b3).toString('utf8'); }
    if (mt === 4) { var a = []; for (var i = 0; i < len; i++) a.push(read()); return a; }
    if (mt === 5) { var m = new Map(); for (var j = 0; j < len; j++) { var k = read(); m.set(typeof k === 'object' ? _waBytesToB64u(k) : k, read()); } return m; }
    throw new Error('cbor: unsupported major type ' + mt);
  }
  return read();
}

var _waChallengeTtlMs = 5 * 60 * 1000;
var _waChallenges = new Map();   // challenge -> { userId, issuedMs }
var _waCreds = new Map();        // userId -> [ { credentialId, publicKey, signCount } ]

function _webauthnChallenge(userId) {
  var c = _waBytesToB64u(_nodeCrypto.randomBytes(32));
  _waChallenges.set(c, { userId: userId, issuedMs: Date.now() });
  return c;
}
function _webauthnConsumeChallenge(s) {
  var pe = _waChallenges.get(s);
  _waChallenges.delete(s);
  if (!pe || (Date.now() - pe.issuedMs) > _waChallengeTtlMs) return None;
  return _Some(pe.userId);
}
function _waConsume(s) { var pe = _waChallenges.get(s); _waChallenges.delete(s); if (!pe || (Date.now() - pe.issuedMs) > _waChallengeTtlMs) return null; return pe.userId; }

function _webauthnStorePut(userId, credentialId, publicKey, signCount) {
  var list = _waCreds.get(userId) || [];
  var c = { credentialId: credentialId, publicKey: publicKey, signCount: signCount | 0 };
  var i = -1; for (var k = 0; k < list.length; k++) if (list[k].credentialId === credentialId) i = k;
  if (i >= 0) list[i] = c; else list.push(c);
  _waCreds.set(userId, list);
  return undefined;
}
function _waCredMap(c) { return _Map(['credentialId', c.credentialId], ['publicKey', c.publicKey], ['signCount', c.signCount]); }
function _webauthnStoreGet(userId) { return (_waCreds.get(userId) || []).map(_waCredMap); }
function _waFindRaw(userId, credentialId) { var list = _waCreds.get(userId) || []; for (var i = 0; i < list.length; i++) if (list[i].credentialId === credentialId) return list[i]; return null; }
function _webauthnStoreFind(userId, credentialId) { var c = _waFindRaw(userId, credentialId); return c ? _Some(_waCredMap(c)) : None; }
function _webauthnUpdateSignCount(userId, credentialId, newCount) { var c = _waFindRaw(userId, credentialId); if (!c || newCount <= c.signCount) return false; c.signCount = newCount; return true; }

function _waJsonField(json, key) { try { var o = JSON.parse(json); var v = o[key]; return typeof v === 'string' ? v : null; } catch (e) { return null; } }
function _waUint32BE(b, o) { return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0; }

function _waParseAuthData(b) {
  b = new Uint8Array(b);
  if (b.length < 37) return null;
  var flags = b[32] & 0xff, signCount = _waUint32BE(b, 33);
  if ((flags & 0x40) === 0 || b.length < 55) return null;       // AT (attested cred data) required
  var credIdLen = (b[53] << 8) | b[54];
  if (b.length < 55 + credIdLen) return null;
  return { credentialIdB64: _waBytesToB64u(b.slice(55, 55 + credIdLen)), publicKeyB64: _waBytesToB64u(b.slice(55 + credIdLen)), signCount: signCount };
}
function _waCoseToKey(coseBytes) {
  var m = _waCborDecode(coseBytes);
  if (!(m instanceof Map)) return null;
  var kty = m.get(1), alg = m.get(3), crv = m.get(-1), x = m.get(-2), y = m.get(-3);
  if (kty !== 2 || alg !== -7 || crv !== 1 || !(x instanceof Uint8Array) || !(y instanceof Uint8Array)) return null;
  return _nodeCrypto.createPublicKey({ key: { kty: 'EC', crv: 'P-256', x: _waBytesToB64u(x), y: _waBytesToB64u(y) }, format: 'jwk' });
}

function _webauthnVerifyRegistration(clientDataJSONB64, attestationObjectB64, expectedOrigin) {
  try {
    var cd = Buffer.from(_waB64uToBytes(clientDataJSONB64)).toString('utf8');
    var challenge = _waJsonField(cd, 'challenge'); if (!challenge) return None;
    if (_waJsonField(cd, 'origin') !== expectedOrigin) return None;
    if (_waJsonField(cd, 'type') !== 'webauthn.create') return None;
    var userId = _waConsume(challenge); if (!userId) return None;
    var att = _waCborDecode(_waB64uToBytes(attestationObjectB64));
    if (!(att instanceof Map) || att.get('fmt') !== 'none') return None;   // "none" attestation
    var authData = att.get('authData'); if (!(authData instanceof Uint8Array)) return None;
    var parsed = _waParseAuthData(authData); if (!parsed) return None;
    return _Some(_Map(['userId', userId], ['credentialId', parsed.credentialIdB64], ['publicKey', parsed.publicKeyB64], ['signCount', parsed.signCount]));
  } catch (e) { return None; }
}

function _webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64, signatureB64, credentialIdB64, expectedOrigin) {
  try {
    var cd = Buffer.from(_waB64uToBytes(clientDataJSONb64)).toString('utf8');
    var challenge = _waJsonField(cd, 'challenge'); if (!challenge) return None;
    if (_waJsonField(cd, 'origin') !== expectedOrigin) return None;
    if (_waJsonField(cd, 'type') !== 'webauthn.get') return None;
    var userId = _waConsume(challenge); if (!userId) return None;
    var cred = _waFindRaw(userId, credentialIdB64); if (!cred) return None;
    var authData = Buffer.from(_waB64uToBytes(authenticatorDataB64));
    if (authData.length < 37) return None;
    var newCount = _waUint32BE(authData, 33);
    var cdHash = _nodeCrypto.createHash('sha256').update(Buffer.from(_waB64uToBytes(clientDataJSONb64))).digest();
    var signed = Buffer.concat([authData, cdHash]);
    var key = _waCoseToKey(_waB64uToBytes(cred.publicKey)); if (!key) return None;
    if (!_nodeCrypto.verify('sha256', signed, key, Buffer.from(_waB64uToBytes(signatureB64)))) return None;   // DER ECDSA
    if (newCount > 0 && !_webauthnUpdateSignCount(userId, credentialIdB64, newCount)) return None;
    return _Some(_Map(['userId', userId], ['signCount', newCount]));
  } catch (e) { return None; }
}
"""
