import { DatabaseSync } from 'node:sqlite';
import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { chmodSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

/** The master key comes from the operator's secret manager, never the mobile app. */
export class Store {
  constructor(path, key) {
    if (!Buffer.isBuffer(key) || key.length !== 32) throw new Error('Invalid storage key');
    this.key = key;
    if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    this.db = new DatabaseSync(path);
    if (path !== ':memory:') chmodSync(path, 0o600);
    this.db.exec('PRAGMA secure_delete=ON; CREATE TABLE IF NOT EXISTS records (kind TEXT, id TEXT, value TEXT NOT NULL, PRIMARY KEY(kind,id))');
  }
  get(kind, id) {
    const row = this.db.prepare('SELECT value FROM records WHERE kind=? AND id=?').get(kind, id);
    if (!row) return null;
    const bytes = Buffer.from(row.value, 'base64');
    const cipher = createDecipheriv('aes-256-gcm', this.key, bytes.subarray(0, 12));
    cipher.setAAD(Buffer.from(kind + ':' + id));
    cipher.setAuthTag(bytes.subarray(12, 28));
    return JSON.parse(Buffer.concat([cipher.update(bytes.subarray(28)), cipher.final()]).toString());
  }
  set(kind, id, value) {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    cipher.setAAD(Buffer.from(kind + ':' + id));
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final()]);
    const bytes = Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString('base64');
    this.db.prepare('INSERT INTO records VALUES (?,?,?) ON CONFLICT(kind,id) DO UPDATE SET value=excluded.value').run(kind, id, bytes);
  }
  remove(kind, id) { this.db.prepare('DELETE FROM records WHERE kind=? AND id=?').run(kind, id); }
  ids(kind) { return this.db.prepare('SELECT id FROM records WHERE kind=?').all(kind).map(row => row.id); }
  transaction(block) {
    this.db.exec('BEGIN IMMEDIATE');
    try { const result = block(); this.db.exec('COMMIT'); return result; }
    catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  close() { this.db.close(); }
}
