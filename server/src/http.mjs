import { createServer } from 'node:http';
import { ServiceError, fail } from './provider.mjs';

const returnPage = '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="refresh" content="0;url=followertracker://facebook/complete"><title>팔로워 트래커</title><h1>앱에서 연결을 이어가세요</h1><p>앱이 자동으로 열리지 않으면 아래 버튼을 눌러주세요.</p><a href="followertracker://facebook/complete">팔로워 트래커로 돌아가기</a>';
const baseHeaders = { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'", 'X-Frame-Options': 'DENY' };
function send(res, status, body, headers = {}) {
  res.writeHead(status, { ...baseHeaders, 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(typeof body === 'string' ? body : JSON.stringify(body));
}
async function body(req, form = false) {
  if (!req.headers['content-type']?.startsWith(form ? 'application/x-www-form-urlencoded' : 'application/json')) fail('BAD_REQUEST');
  let size = 0, chunks = [];
  for await (const chunk of req) { size += chunk.length; if (size > 16384) fail('BAD_REQUEST', 413); chunks.push(chunk); }
  const text = Buffer.concat(chunks).toString();
  try {
    const result = form ? new URLSearchParams(text) : JSON.parse(text);
    if (!form && (typeof result !== 'object' || result === null || Array.isArray(result))) fail('BAD_REQUEST');
    return result;
  } catch { fail('BAD_REQUEST'); }
}
export function httpServer(service) {
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, service.config.origin);
      service.limit('ip:' + req.socket.remoteAddress, 600, 60000);
      if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });
      if (req.method === 'GET' && url.pathname === '/deletion/complete')
        return send(res, 200, '<!doctype html><meta charset="utf-8"><p>Facebook 연결 정보 삭제를 완료했어요.</p>', { 'Content-Type': 'text/html; charset=utf-8' });
      if (req.method === 'GET' && url.pathname === '/oauth/facebook/start') {
        const { url: location, cookie } = service.browserStart(url.searchParams.get('flow'));
        return send(res, 302, '', { Location: location, 'Set-Cookie': '__Host-ft_oauth=' + cookie + '; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=600' });
      }
      if (req.method === 'GET' && url.pathname === '/oauth/facebook/callback') {
        const cookie = req.headers.cookie?.split(';').map(s => s.trim()).find(s => s.startsWith('__Host-ft_oauth='))?.slice(16);
        await service.callback(url.searchParams, cookie);
        return send(res, 200, returnPage, { 'Content-Type': 'text/html; charset=utf-8',
          'Set-Cookie': '__Host-ft_oauth=; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=0' });
      }
      if (req.method === 'POST' && ['/meta/deauthorize', '/meta/delete'].includes(url.pathname)) {
        service.configured('facebook');
        return send(res, 200, service.metaDeletion((await body(req, true)).get('signed_request')));
      }
      if (req.method !== 'POST') fail('NOT_FOUND', 404);
      const input = await body(req);
      if (url.pathname === '/v1/installations') return send(res, 201, service.register(req.socket.remoteAddress));
      const device = service.authorize(req.headers.authorization);
      let result;
      switch (url.pathname) {
        case '/v1/youtube': result = await service.youtube(input.address); break;
        case '/v1/facebook/login': result = service.startFacebook(device, input.expectedId ?? null); break;
        case '/v1/facebook/status': result = service.status(device, input.flowId); break;
        case '/v1/facebook/select': result = await service.select(device, input.flowId, input.pageId); break;
        case '/v1/facebook/count': result = await service.facebook(device, input.pageId); break;
        case '/v1/facebook/cancel': service.cancelFacebook(device, input.flowId); result = {}; break;
        case '/v1/facebook/disconnect': service.disconnect(device, input.pageId ?? null); result = {}; break;
        default: fail('NOT_FOUND', 404);
      }
      send(res, 200, result);
    } catch (error) {
      const safe = error instanceof ServiceError ? error : new ServiceError('SERVICE_UNAVAILABLE', 503);
      // No request URLs, provider error messages, authorization headers, codes or tokens in logs.
      send(res, safe.status, { error: { code: safe.code, retryAfterSeconds: safe.retryAfterSeconds } },
        safe.retryAfterSeconds ? { 'Retry-After': String(safe.retryAfterSeconds) } : {});
    }
  });
  server.requestTimeout = 30000; server.headersTimeout = 10000; server.maxHeadersCount = 32;
  return server;
}
