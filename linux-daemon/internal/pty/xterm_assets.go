package pty

const TerminalHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>LowDeck Web Terminal</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.min.css" />
  <style>
    * { box-sizing: border-box; }
    html, body {
      margin: 0;
      padding: 0;
      width: 100%;
      height: 100%;
      background-color: #0D1117;
      color: #C9D1D9;
      font-family: "JetBrains Mono", Menlo, Monaco, "Courier New", monospace;
      overflow: hidden;
      user-select: none;
      -webkit-user-select: none;
    }
    #terminal-container {
      width: 100%;
      height: 100%;
      position: absolute;
      top: 0;
      left: 0;
    }
    #fallback-canvas {
      width: 100%;
      height: 100%;
      display: none;
    }
    #status-pill {
      position: fixed;
      bottom: 6px;
      right: 10px;
      background: rgba(13, 17, 23, 0.85);
      border: 1px solid #30363D;
      border-radius: 12px;
      padding: 2px 8px;
      font-size: 10px;
      color: #58A6FF;
      pointer-events: none;
      z-index: 999;
      font-family: monospace;
    }
  </style>
  <script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.min.js"></script>
</head>
<body>
  <div id="terminal-container"></div>
  <canvas id="fallback-canvas"></canvas>
  <div id="status-pill">CONECTANDO...</div>

  <script>
    (function() {
      const statusPill = document.getElementById('status-pill');
      const container = document.getElementById('terminal-container');
      const canvas = document.getElementById('fallback-canvas');

      let ws = null;
      let term = null;
      let fitAddon = null;
      let isFallback = false;

      function updateStatus(text, color) {
        if (statusPill) {
          statusPill.textContent = text;
          if (color) statusPill.style.color = color;
        }
      }

      function sendRawBytes(bytes) {
        if (ws && ws.readyState === WebSocket.OPEN) {
          const packet = new Uint8Array(bytes.length + 1);
          packet[0] = 0x00; // OP_DATA
          packet.set(bytes, 1);
          ws.send(packet.buffer);
        }
      }

      function sendResize(cols, rows) {
        if (!cols || !rows) {
          if (term && term.cols && term.rows) {
            cols = term.cols;
            rows = term.rows;
          } else {
            cols = 80;
            rows = 24;
          }
        }
        if (ws && ws.readyState === WebSocket.OPEN) {
          const jsonStr = JSON.stringify({ cols: Math.max(10, cols), rows: Math.max(4, rows) });
          const jsonBytes = new TextEncoder().encode(jsonStr);
          const packet = new Uint8Array(jsonBytes.length + 1);
          packet[0] = 0x01; // OP_RESIZE
          packet.set(jsonBytes, 1);
          ws.send(packet.buffer);
        }
      }

      // Android Bridge functions exposed on window
      window.sendRawBase64 = function(b64String) {
        try {
          const binary = atob(b64String);
          const bytes = new Uint8Array(binary.length);
          for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
          }
          sendRawBytes(bytes);
        } catch (e) {
          console.error("sendRawBase64 error", e);
        }
      };

      window.sendSpecialSeq = function(seqString) {
        try {
          const bytes = new TextEncoder().encode(seqString);
          sendRawBytes(bytes);
        } catch (e) {
          console.error("sendSpecialSeq error", e);
        }
      };

      // Fallback Canvas Terminal for offline USB LAN without internet access
      class CanvasTerminal {
        constructor(canvasEl) {
          this.canvas = canvasEl;
          this.ctx = canvasEl.getContext('2d');
          this.cols = 80;
          this.rows = 24;
          this.charW = 8;
          this.charH = 16;
          this.lines = [''];
          this.cursorX = 0;
          this.cursorY = 0;
          this.cursorVisible = true;
          this.init();
        }

        init() {
          this.canvas.style.display = 'block';
          container.style.display = 'none';
          this.resize();
          window.addEventListener('resize', () => this.resize());
          setInterval(() => {
            this.cursorVisible = !this.cursorVisible;
            this.render();
          }, 500);

          window.addEventListener('keydown', (e) => {
            if (e.key === 'Backspace') {
              window.sendSpecialSeq('\b');
              e.preventDefault();
            } else if (e.key === 'Enter') {
              window.sendSpecialSeq('\r');
              e.preventDefault();
            } else if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
              window.sendSpecialSeq(e.key);
              e.preventDefault();
            }
          });
        }

        resize() {
          const dpr = window.devicePixelRatio || 1;
          const rect = this.canvas.getBoundingClientRect();
          this.canvas.width = rect.width * dpr;
          this.canvas.height = rect.height * dpr;
          this.ctx.scale(dpr, dpr);
          this.charW = 8.5;
          this.charH = 17;
          this.cols = Math.floor(rect.width / this.charW);
          this.rows = Math.floor(rect.height / this.charH);
          sendResize(this.cols, this.rows);
          this.render();
        }

        write(data) {
          let str = '';
          if (typeof data === 'string') {
            str = data;
          } else {
            str = new TextDecoder('utf-8', { fatal: false }).decode(data);
          }
          // Strip basic ANSI escapes for fallback canvas
          const clean = str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
          for (let i = 0; i < clean.length; i++) {
            const ch = clean[i];
            if (ch === '\r') {
              this.cursorX = 0;
            } else if (ch === '\n') {
              this.cursorY++;
              if (this.cursorY >= this.lines.length) {
                this.lines.push('');
              }
              if (this.lines.length > 500) this.lines.shift();
            } else if (ch === '\b') {
              if (this.cursorX > 0) this.cursorX--;
            } else {
              let curLine = this.lines[this.lines.length - 1] || '';
              curLine += ch;
              this.lines[this.lines.length - 1] = curLine;
              this.cursorX++;
            }
          }
          this.render();
        }

        render() {
          const rect = this.canvas.getBoundingClientRect();
          this.ctx.fillStyle = '#0D1117';
          this.ctx.fillRect(0, 0, rect.width, rect.height);
          this.ctx.fillStyle = '#C9D1D9';
          this.ctx.font = '13px monospace';
          this.ctx.textBaseline = 'top';

          const visibleLines = Math.min(this.lines.length, this.rows);
          const startIdx = Math.max(0, this.lines.length - visibleLines);

          for (let i = startIdx; i < this.lines.length; i++) {
            const y = (i - startIdx) * this.charH;
            this.ctx.fillText(this.lines[i], 4, y);
          }

          if (this.cursorVisible) {
            const curY = (this.lines.length - 1 - startIdx) * this.charH;
            const lastLine = this.lines[this.lines.length - 1] || '';
            const curX = 4 + (lastLine.length * this.charW);
            this.ctx.fillStyle = '#58A6FF';
            this.ctx.fillRect(curX, curY, 8, this.charH);
          }
        }
      }

      function initTerminal() {
        if (typeof window.Terminal !== 'undefined') {
          try {
            term = new window.Terminal({
              cursorBlink: true,
              fontSize: 13,
              fontFamily: '"JetBrains Mono", Menlo, Monaco, "Courier New", monospace',
              scrollback: 1500,
              theme: {
                background: '#0D1117',
                foreground: '#C9D1D9',
                cursor: '#58A6FF',
                selectionBackground: '#264F78',
                black: '#484F58',
                red: '#FF7B72',
                green: '#3FB950',
                yellow: '#D29922',
                blue: '#58A6FF',
                magenta: '#BC8CFF',
                cyan: '#39C5CF',
                white: '#B1BAC4',
                brightBlack: '#6E7681',
                brightRed: '#FFA198',
                brightGreen: '#56D364',
                brightYellow: '#E3B341',
                brightBlue: '#79C0FF',
                brightMagenta: '#D2A8FF',
                brightCyan: '#56D4DD',
                brightWhite: '#F0F6FC'
              }
            });

            if (typeof window.FitAddon !== 'undefined') {
              fitAddon = new window.FitAddon.FitAddon();
              term.loadAddon(fitAddon);
            }

            term.open(container);
            if (fitAddon) {
              fitAddon.fit();
            }

            term.onData(function(data) {
              window.sendSpecialSeq(data);
            });

            term.onResize(function(size) {
              sendResize(size.cols, size.rows);
            });

            window.addEventListener('resize', function() {
              if (fitAddon) {
                try { fitAddon.fit(); } catch (_) {}
              }
            });
          } catch (e) {
            console.warn("xterm init failed; falling back to canvas", e);
            isFallback = true;
            term = new CanvasTerminal(canvas);
          }
        } else {
          isFallback = true;
          term = new CanvasTerminal(canvas);
        }
      }

      function connectWs() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = protocol + '//' + window.location.host + '/ws/pty';
        updateStatus('CONECTANDO...', '#D29922');

        try {
          ws = new WebSocket(wsUrl);
          ws.binaryType = 'arraybuffer';

          ws.onopen = function() {
            updateStatus('CONECTADO', '#3FB950');
            if (fitAddon) {
              try { fitAddon.fit(); } catch (_) {}
            }
            sendResize();
          };

          ws.onmessage = function(ev) {
            if (term) {
              if (typeof ev.data === 'string') {
                term.write(ev.data);
              } else {
                term.write(new Uint8Array(ev.data));
              }
            }
          };

          ws.onclose = function() {
            updateStatus('DESCONECTADO', '#FF7B72');
            setTimeout(connectWs, 2000);
          };

          ws.onerror = function() {
            updateStatus('ERRO DE CONEXÃO', '#FF7B72');
          };
        } catch (e) {
          updateStatus('FALHA DE REDE', '#FF7B72');
          setTimeout(connectWs, 2000);
        }
      }

      // Start terminal then connect websocket
      initTerminal();
      connectWs();
    })();
  </script>
</body>
</html>
`

