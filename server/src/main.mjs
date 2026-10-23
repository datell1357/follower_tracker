import { Store } from './store.mjs';
import { Service } from './service.mjs';
import { httpServer } from './http.mjs';

const origin = new URL(process.env.TRACKER_PUBLIC_ORIGIN ?? 'https://invalid.invalid');
if (origin.protocol !== 'https:' || origin.pathname !== '/' || origin.search || origin.hash ||
    origin.username || origin.password || origin.hostname === 'invalid.invalid') throw new Error('Configure TRACKER_PUBLIC_ORIGIN as an HTTPS origin');
const key = Buffer.from(process.env.TRACKER_STORAGE_KEY ?? '', 'base64');
const store = new Store(process.env.TRACKER_DB_PATH ?? './data/tracker.sqlite', key);
const budget = Number(process.env.YOUTUBE_DAILY_BUDGET ?? 9000);
if (!Number.isSafeInteger(budget) || budget <= 0) throw new Error('Invalid YouTube daily budget');
const service = new Service(store, { origin: origin.origin, youtubeKey: process.env.YOUTUBE_API_KEY,
  metaAppId: process.env.META_APP_ID, metaSecret: process.env.META_APP_SECRET,
  metaLoginConfigId: process.env.META_LOGIN_CONFIG_ID, youtubeDailyBudget: budget });
const server = httpServer(service);
service.sweep();
const sweep = setInterval(() => service.sweep(), 60000);
server.listen(Number(process.env.PORT ?? 8787), '127.0.0.1', () => console.log('Follower Tracker service listening on loopback'));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  clearInterval(sweep); server.close(() => { store.close(); process.exit(0); });
});
