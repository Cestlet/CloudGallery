// Local fixtures only. Never contacts Baidu or accepts real credentials.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const uploads = new Map(), backedUp = new Map();
const uploadedContent = new Map();
let nextId = 10000;
const base = 'http://127.0.0.1:5246';
const image = fs.readFileSync(path.join(__dirname, '../docs/screenshots/photos.png'));
let approved = false, mode = 'normal', uploadFailAfter = -1;
const counts = { lists: [], originals: 0, images: 0, refreshes: 0, uploads: 0, creates: 0 };
function file(id, name, parent, directory = false, thumb = true) {
  return { fs_id: id, server_filename: name, path: `${parent === '/' ? '' : parent}/${name}`,
    isdir: directory ? 1 : 0, size: directory ? 0 : image.length, server_mtime: 1720000000,
    ...(thumb && !directory ? { thumbs: { url3: `${base}/image.png?id=${id}` } } : {}) };
}
function files(dir) {
  if (dir === '/') return [file(1, 'Photos', '/', true), file(2, 'Other', '/', true)];
  if (dir === '/Photos') return [...Array.from({length: mode === 'removed' ? 199 : 201}, (_, i) => file(100+i, `photo-${String(i).padStart(3, '0')}.png`, dir)), file(3, 'Nested', dir, true)];
  if (dir === '/Photos/Nested') return [file(400, 'no-thumb.png', dir, false, false),
    ...(mode === 'heif' ? ['sample.heif', 'sample.HEIC'].map((name, i) => ({
      ...file(401+i, name, dir, false, false),
      size: fs.statSync(path.join(__dirname, '../app/build/tmp/heif-test/sample.heic')).size
    })) : [])];
  if (dir === '/Other') return [file(500, 'other.png', dir)];
  return [];
}
function json(res, data, status = 200) { res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify(data)); }
http.createServer((req, res) => {
  const url = new URL(req.url, base), q = url.searchParams;
  if (url.pathname === '/discovery/item/000000000000000000000001') {
    const noteData = {noteId:'000000000000000000000001',type:'normal',imageList:[1,2].map(i => ({fileId:`fixture-image-${i}`,url:`${base}/fixture-full-${i}`,width:image.readUInt32BE(16),height:image.readUInt32BE(20)}))};
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    return res.end(`<script>window.__SETUP_SERVER_STATE__=${JSON.stringify({LAUNCHER_SSR_STORE_PAGE_DATA:{noteData}})}</script>`);
  }
  if (/^\/fixture-(?:image|full)-[12]$/.test(url.pathname)) {
    const bytes = mode === 'source-fallback' && url.pathname.includes('/fixture-image-')
      ? fs.readFileSync(path.join(__dirname, '../app/build/tmp/xhs-source/tiny.png')) : image;
    res.writeHead(200, {'Content-Type':'image/png','Content-Length':bytes.length}); return res.end(bytes);
  }
  if (url.pathname === '/fixture') {
    if (q.has('approve')) approved = true;
    if (q.has('mode')) mode = q.get('mode');
    if (q.has('uploadFailAfter')) uploadFailAfter = Number(q.get('uploadFailAfter'));
    return json(res, {approved, mode, counts});
  }
  if (url.pathname === '/authorize') {
    if (q.has('confirm')) approved = true;
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    return res.end('<h1>本机模拟授权</h1><p>不连接真实百度账号。</p>' + (approved ? '<p>已授权，请返回图库。</p>' : '<a href="?confirm=1">批准模拟授权</a>'));
  }
  if (url.pathname === '/oauth/2.0/device/code') {
    approved = false;
    return json(res, {device_code:'fixture-device',user_code:'TEST-1234',verification_url:`${base}/authorize`,expires_in:600,interval:1});
  }
  if (url.pathname === '/oauth/2.0/token') {
    if (q.get('grant_type') === 'refresh_token') counts.refreshes++;
    else if (!approved) return json(res, {error:'authorization_pending'},400);
    return json(res, {access_token:'fixture-access',refresh_token:'fixture-refresh',expires_in:3600});
  }
  if (url.pathname === '/rest/2.0/xpan/nas') return json(res, {errno:0,uk:123456,baidu_name:'模拟用户'});
  if (req.method === 'POST' && (url.pathname === '/rest/2.0/xpan/file' || url.pathname === '/rest/2.0/pcs/superfile2')) {
    if (q.get('access_token') !== 'fixture-access') return json(res, {errno: -6});
    const chunks = [];
    let received = 0;
    req.on('data', chunk => { received += chunk.length; if (received > 5*1024*1024) req.destroy(); else chunks.push(chunk); });
    req.on('end', () => {
      const body = Buffer.concat(chunks), fields = new URLSearchParams(body.toString());
      if (q.get('method') === 'precreate') {
        if (uploadFailAfter >= 0 && counts.creates >= uploadFailAfter) return json(res, {errno:31034});
        const id = crypto.randomUUID(), hashes = JSON.parse(fields.get('block_list'));
        uploads.set(id, {path: fields.get('path'), size: Number(fields.get('size')), hashes, parts: new Map()});
        return json(res, {errno:0, uploadid:id, return_type:1, block_list:hashes.map((_, i) => i)});
      }
      if (q.get('method') === 'upload') {
        const upload = uploads.get(q.get('uploadid'));
        if (!upload) return json(res, {errno: -8});
        const boundary = '--' + /boundary=([^;]+)/.exec(req.headers['content-type'])[1];
        const start = body.indexOf(Buffer.from('\r\n\r\n')) + 4;
        const end = body.lastIndexOf(Buffer.from('\r\n' + boundary));
        const bytes = body.subarray(start,end), hash = crypto.createHash('md5').update(bytes).digest('hex');
        const index = Number(q.get('partseq'));
        if (hash !== upload.hashes[index]) return json(res,{errno:2});
        upload.parts.set(index,bytes); counts.uploads++;
        return json(res,{errno:0,md5:hash});
      }
      if (q.get('method') === 'create' && fields.get('isdir') === '1') {
        const target = fields.get('path'), parent = target.slice(0, target.lastIndexOf('/')) || '/', name = target.split('/').pop();
        if (!name || target.includes('/../')) return json(res,{errno:2});
        if (backedUp.has(target) || files(parent).some(item => item.path === target)) return json(res,{errno:-8});
        if (!['/','/Photos','/Photos/Nested','/Other'].includes(parent) && !backedUp.get(parent)?.isdir) return json(res,{errno:-9});
        const item = file(nextId++,name,parent,true);
        backedUp.set(target,item);
        return json(res,{errno:0,...item});
      }
      if (q.get('method') === 'create') {
        const upload = uploads.get(fields.get('uploadid'));
        if (!upload || upload.parts.size !== upload.hashes.length) return json(res,{errno:2});
        if (backedUp.has(upload.path)) return json(res,{errno:-8});
        const name = upload.path.split('/').pop(), parent = upload.path.slice(0,upload.path.lastIndexOf('/')) || '/';
        const item = {...file(nextId++,name,parent,false,false), size:upload.size, server_mtime:Math.floor(Date.now()/1000)};
        backedUp.set(upload.path,item); counts.creates++;
        uploadedContent.set(item.fs_id, Buffer.concat(upload.hashes.map((_, i) => upload.parts.get(i))));
        uploads.delete(fields.get('uploadid'));
        return json(res,{errno:0,path:upload.path,size:upload.size,fs_id:item.fs_id});
      }
      return json(res,{errno:2});
    });
    return;
  }
  if (url.pathname === '/rest/2.0/xpan/file') {
    const dir = q.get('dir'), start = Number(q.get('start'));
    counts.lists.push({dir,start});
    if (mode === 'failure' && dir.startsWith('/Photos')) return json(res, {errno:31034});
    return json(res, {errno:0,list:[...files(dir),...Array.from(backedUp.values()).filter(item => item.path.slice(0,item.path.lastIndexOf('/')) === dir)].slice(start, start + Number(q.get('limit') || 200))});
  }
  if (url.pathname === '/rest/2.0/xpan/multimedia') {
    counts.originals++;
    const id = JSON.parse(q.get('fsids'))[0];
    const item = [...['/Photos','/Photos/Nested','/Other'].flatMap(files), ...backedUp.values()].find(f => f.fs_id === id);
    return json(res, {errno:0,list:item ? [{...item,dlink:`${base}/image.png?original=${id}`}] : []});
  }
  if (url.pathname === '/image.png') {
    if (q.has('original') && (q.get('access_token') !== 'fixture-access' || req.headers['user-agent'] !== 'pan.baidu.com')) {
      return json(res, {error:'missing download authorization'},403);
    }
    const originalId = Number(q.get('original'));
    const isHeif = mode === 'heif' && [401,402].includes(originalId);
    const bytes = isHeif ? fs.readFileSync(path.join(__dirname, '../app/build/tmp/heif-test/sample.heic'))
      : uploadedContent.get(originalId) || image;
    const type = isHeif ? 'image/heif' : uploadedContent.has(originalId) ? 'application/octet-stream' : 'image/png';
    counts.images++; res.writeHead(200, {'Content-Type':type, 'Content-Length':bytes.length}); return res.end(bytes);
  }
  json(res, {error:'unknown fixture endpoint'},404);
}).listen(5246, '127.0.0.1', () => console.log('Baidu fixture ready on 127.0.0.1:5246'));
