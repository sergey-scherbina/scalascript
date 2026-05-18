// Runtime helpers for org.example.crypto plugin — JS backend.
// Injected into generated JS preamble when a program uses std.crypto
// and is compiled with the js backend.

const _crypto = require('crypto');

function _cryptoSha256(input) {
  return _crypto.createHash('sha256').update(String(input), 'utf8').digest('hex');
}

function _cryptoBase64Encode(s) {
  return Buffer.from(String(s), 'utf8').toString('base64');
}

function _cryptoBase64Decode(s) {
  return Buffer.from(String(s), 'base64').toString('utf8');
}

function _cryptoHmacSha256(key, data) {
  return _crypto.createHmac('sha256', String(key)).update(String(data), 'utf8').digest('hex');
}
