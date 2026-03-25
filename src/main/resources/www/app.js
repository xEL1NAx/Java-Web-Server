const log = (msg) => {
  const el = document.getElementById('log');
  el.textContent += msg + '\n';
};

let ws;
document.getElementById('connect').addEventListener('click', () => {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.onopen = () => log('socket opened');
  ws.onmessage = (e) => log('recv: ' + e.data);
  ws.onclose = () => log('socket closed');
  ws.onerror = () => log('socket error');
});

document.getElementById('send').addEventListener('click', () => {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    log('socket is not open');
    return;
  }
  ws.send('hello at ' + new Date().toISOString());
  log('sent a message');
});
