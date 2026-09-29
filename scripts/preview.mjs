// Desktop-only browser QA fixture. The production HTTP/WebSocket server runs in the APK.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { WebSocketServer } from 'ws';
import ffmpeg from 'ffmpeg-static';
const root = new URL('../common/src/main/assets/web/', import.meta.url);
const demo = process.argv.includes('--demo');
const port = Number(process.env.PORT || 8081);
let jpeg;
if (demo) {
  const result = spawnSync(ffmpeg, ['-v','error','-f','lavfi','-i','testsrc=size=1280x720:rate=1','-frames:v','1','-f','image2pipe','-vcodec','mjpeg','pipe:1'], { maxBuffer: 4e6 });
  if (result.status !== 0) throw new Error(result.stderr.toString());
  jpeg = result.stdout;
}
let touchReports = [];
const server = http.createServer(async (req,res) => {
  if (demo && req.url === '/__test/touches') {
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify(touchReports));return;
  }
  const file = { '/':'index.html','/app.js':'app.js','/browser-audio.js':'browser-audio.js',
    '/audio-worklet.js':'audio-worklet.js','/style.css':'style.css' }[req.url];
  if (!file) { res.writeHead(404);res.end();return; }
  try {
    const body = await readFile(new URL(file,root));
    res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html; charset=utf-8');
    res.setHeader('Cache-Control','no-store');res.end(body);
  } catch { res.writeHead(500);res.end(); }
});
const wss = new WebSocketServer({noServer:true,maxPayload:2048});
let viewer;
server.on('upgrade',(req,socket,head) => {
  const url = new URL(req.url,'http://localhost');
  if (!demo || url.pathname !== '/stream' || url.searchParams.get('code') !== '123456') {
    socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');return;
  }
  wss.handleUpgrade(req,socket,head,ws => wss.emit('connection',ws));
});
wss.on('connection',ws => {
  if (viewer) { ws.send(JSON.stringify({type:'busy',message:'已有车机连接，请先在另一台车机断开'}));ws.close();return; }
  viewer=ws;let ready=true;
  const timer=setInterval(()=>{if (ready && ws.readyState===1) {ready=false;ws.send(jpeg);}},100);
  ws.on('message',(data,isBinary)=>{
    if (isBinary) return;
    const msg=JSON.parse(data);
    if(msg.type==='ack')ready=true;
    if(msg.type==='ping')ws.send(JSON.stringify({type:'status',width:1280,height:720,streaming:true,stage:'测试画面（非真实 CarPlay）'}));
    if(msg.type==='touch'){touchReports.push(msg.contacts);touchReports=touchReports.slice(-100);}
  });
  ws.on('close',()=>{clearInterval(timer);if(viewer===ws)viewer=null;});
});
server.listen(port,'127.0.0.1',()=>console.log(`Browser ${demo?'test fixture (code 123456)':'preview'}: http://127.0.0.1:${port}`));
