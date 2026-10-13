// Stand-in for the kitchen's ESC/POS network printer (port 9100): prints each
// ticket it receives as text (GBK) and keeps the raw bytes in ticket-N.bin.
// The emulator reaches this PC at 10.0.2.2, so Ourea Settings > kitchen printer
// = 10.0.2.2:9100.
//   node tools/scan-e2e/fake-printer.cjs [out dir]
const net = require('net'), fs = require('fs'), path = require('path')
const dir = process.argv[2] || __dirname
let n = 0

function text(bytes) {
  const out = []
  for (let i = 0; i < bytes.length; i++) {
    const c = bytes[i]
    if (c === 0x1b) { i += bytes[i + 1] === 0x40 ? 1 : 2; continue }                                      // ESC @ / ESC x n
    if (c === 0x1d) { i += bytes[i + 1] === 0x56 && (bytes[i + 2] === 65 || bytes[i + 2] === 66) ? 3 : 2; continue } // GS V m n / GS x n
    if (c === 0x1c) { i += 1; continue }                                                                   // FS & (GBK mode)
    out.push(c)
  }
  return new TextDecoder('gbk').decode(Buffer.from(out))
}

net.createServer((s) => {
  const chunks = [], id = ++n
  s.on('data', (d) => chunks.push(d))
  s.on('close', () => {
    const bytes = Buffer.concat(chunks)
    fs.writeFileSync(path.join(dir, `ticket-${id}.bin`), bytes)
    console.log(`\n===== ticket ${id} (${bytes.length} bytes) =====\n${text(bytes)}`)
  })
}).listen(9100, () => console.log('fake kitchen printer on :9100'))
