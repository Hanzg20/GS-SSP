import React, { useEffect, useRef, useState } from 'react'
import { X, Loader2, Send, Mic, Square, Sparkles } from 'lucide-react'

const money = (c) => '$' + (c / 100).toFixed(2)

/**
 * The AI guide: the customer asks in any language (typed or spoken) and gets
 * the answer in that language, from the store's real dish data. Suggested
 * dishes come as cards with an Add button -- nothing goes in the cart unless
 * the customer taps it. The conversation stays for this browser tab only.
 */
export default function Guide({ token, products, T, askGuide, onClose, onAdd }) {
  const key = 'scan_guide_' + token
  const [turns, setTurns] = useState(() => { try { return JSON.parse(sessionStorage.getItem(key) || '[]') } catch { return [] } })
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [recording, setRecording] = useState(null)   // MediaRecorder while listening
  const [added, setAdded] = useState({})
  const endRef = useRef(null)

  useEffect(() => {
    try { sessionStorage.setItem(key, JSON.stringify(turns.slice(-30))) } catch { /* private mode: fine */ }
    endRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [turns, key])

  const say = (content) => setTurns((ts) => [...ts, { role: 'assistant', content, error: true }])

  const ask = async (q) => {
    const question = q.trim()
    if (!question || busy) return
    setText('')
    const history = turns.filter((t) => !t.error).map((t) => ({ role: t.role, content: t.content })).slice(-6)
    setTurns((ts) => [...ts, { role: 'user', content: question }])
    setBusy(true)
    const r = await askGuide({ action: 'ask', token, question, history, browserLang: navigator.language || 'en' })
    setBusy(false)
    if (r.error) say(T.guideErrors[r.error] || T.guideErrors.failed)
    else setTurns((ts) => [...ts, { role: 'assistant', content: r.reply, lang: r.language, suggestions: r.suggestions || [] }])
  }

  // Tap to talk, tap to stop (30 s max); the words land in the box to check before sending.
  const toggleVoice = async () => {
    if (recording) { recording.stop(); return }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      const rec = new MediaRecorder(stream)
      const chunks = []
      rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop())
        setRecording(null)
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' })
        const b64 = await new Promise((ok) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1]); fr.readAsDataURL(blob) })
        setBusy(true)
        const r = await askGuide({ action: 'transcribe', token, audio: b64, mime: blob.type })
        setBusy(false)
        if (r.text) setText(r.text)
        else if (r.error) say(T.guideErrors[r.error] || T.guideErrors.failed)
      }
      rec.start()
      setRecording(rec)
      setTimeout(() => { if (rec.state === 'recording') rec.stop() }, 30000)
    } catch {
      say(T.guideErrors.no_speech_service)
    }
  }

  const byId = Object.fromEntries(products.map((p) => [p.id, p]))
  return (
    <div className="fixed inset-0 z-50 bg-slate-900/60 flex items-end sm:items-center sm:justify-center">
      <div className="bg-white w-full max-w-lg rounded-t-3xl sm:rounded-3xl h-[85vh] flex flex-col overflow-hidden">
        <div className="px-5 py-4 bg-indigo-600 text-white flex items-center justify-between">
          <div className="flex items-center gap-2 font-black"><Sparkles className="w-5 h-5" />{T.guideTitle}</div>
          <button onClick={onClose} className="p-1 text-indigo-100" aria-label="close"><X className="w-6 h-6" /></button>
        </div>
        <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-slate-50">
          <div className="bg-white border border-slate-200 rounded-2xl rounded-bl-sm px-4 py-3 text-sm text-slate-800 max-w-[85%]">{T.guideHello}</div>
          {turns.map((t, i) => (
            <div key={i} className={`flex flex-col ${t.role === 'user' ? 'items-end' : 'items-start'}`}>
              <div dir="auto" lang={t.lang || undefined}
                className={`px-4 py-3 rounded-2xl text-sm max-w-[85%] whitespace-pre-wrap ${t.role === 'user' ? 'bg-indigo-600 text-white rounded-br-sm' : t.error ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-white text-slate-800 border border-slate-200 rounded-bl-sm'}`}>
                {t.content}
              </div>
              {(t.suggestions || []).map((s) => {
                const p = byId[s.product_id]
                if (!p) return null
                const k = i + ':' + s.product_id
                return (
                  <div key={k} className="mt-2 w-[85%] bg-white border border-indigo-200 rounded-xl p-3 flex items-center gap-3">
                    <div className="flex-1 min-w-0">
                      <p className="font-bold text-slate-900 text-sm">{s.quantity} × {p.name}</p>
                      {s.why && <p dir="auto" className="text-xs text-slate-500">{s.why}</p>}
                    </div>
                    <button disabled={added[k]} onClick={() => { onAdd(s); setAdded((a) => ({ ...a, [k]: true })) }}
                      className="shrink-0 bg-slate-900 disabled:bg-emerald-600 text-white text-sm font-bold px-3 py-2 rounded-lg">
                      {added[k] ? '✓' : `${T.addSuggestion} ${money(p.price_cents * s.quantity)}`}
                    </button>
                  </div>
                )
              })}
            </div>
          ))}
          {busy && <div className="flex items-center gap-2 text-slate-400 text-sm"><Loader2 className="w-4 h-4 animate-spin" />…</div>}
          <div ref={endRef} />
        </div>
        <p className="px-4 pt-2 text-[11px] text-slate-400">{T.aiNote}</p>
        <form onSubmit={(e) => { e.preventDefault(); ask(text) }} className="p-3 flex items-center gap-2 border-t border-slate-100">
          <button type="button" onClick={toggleVoice} aria-label="voice"
            className={`p-3 rounded-xl ${recording ? 'bg-red-600 text-white animate-pulse' : 'bg-slate-100 text-slate-700'}`}>
            {recording ? <Square className="w-5 h-5" /> : <Mic className="w-5 h-5" />}
          </button>
          <input dir="auto" value={text} onChange={(e) => setText(e.target.value.slice(0, 400))} placeholder={recording ? T.listening : T.guideHint}
            className="flex-1 min-w-0 px-4 py-3 rounded-xl border border-slate-300 text-sm" />
          <button type="submit" disabled={busy || !text.trim()} aria-label={T.ask} className="p-3 rounded-xl bg-indigo-600 disabled:opacity-40 text-white">
            <Send className="w-5 h-5" />
          </button>
        </form>
      </div>
    </div>
  )
}
