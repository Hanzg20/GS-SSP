import React, { useState } from 'react'
import { X, Mic, Square, Send, Plus, Minus, Loader2, Keyboard } from 'lucide-react'
import { AiCard, BeaverImg, useVoice } from './ai.jsx'
import { dishName } from './i18n.js'

const money = (c) => '$' + (c / 100).toFixed(2)

/**
 * The one Beaver (owner 2026-11-08: ordering and asking are the same Beaver, in this form -- the
 * big microphone and one card, not a chat). The customer says or types anything; scan-assistant
 * (action "chat") decides:
 *   - an order ("two beef noodles, one without onion") adds lines to the card: fix quantities,
 *     answer "which one?", pick options a dish needs; only "Add all" puts anything in the cart;
 *   - a question ("what isn't spicy?") puts the answer, and dishes to add, at the top of the card;
 *     the lines already on the card stay.
 * The last few exchanges go along, so "then two of those" works.
 */
export default function VoiceOrder({ token, products, T, askGuide, onClose, onAddAll, onPickOptions, onAdd }) {
  const [typing, setTyping] = useState(false)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(null)        // 'hearing' | 'thinking' | 'adding'
  const [heard, setHeard] = useState('')        // what the customer said, as Beaver understood the words
  const [result, setResult] = useState(null)    // { reply, language, lines, choices, missing, suggestions }
  const [history, setHistory] = useState([])    // the last exchanges, for "then two of those"
  const [added, setAdded] = useState({})        // suggestions already added
  const [error, setError] = useState('')
  const byId = Object.fromEntries(products.map((p) => [p.id, p]))

  const order = async (words) => {
    const said = words.trim()
    if (!said) return
    setHeard(said); setText(''); setError(''); setBusy('thinking')
    const r = await askGuide({ action: 'chat', token, text: said, history: history.slice(-6), browserLang: T.locale })
    setBusy(null)
    if (r.error) { setError(T.guideErrors[r.error] || T.guideErrors.failed); return }
    setHistory((h) => [...h, { role: 'user', content: said }, { role: 'assistant', content: r.reply }].slice(-6))
    // An order adds to what's on the card; a question answers on top and leaves the lines alone.
    const stamp = Date.now()
    const lines = (r.lines || []).filter((l) => byId[l.product_id]).map((l, i) => ({ ...l, key: `l${stamp}-${i}` }))
    setAdded({})
    setResult((prev) => ({
      reply: r.reply, language: r.language, intent: r.intent,
      lines: [...(prev?.lines || []), ...lines],
      choices: [...(prev?.choices || []), ...(r.choices || [])],
      missing: r.missing || [],
      suggestions: (r.suggestions || []).filter((x) => byId[x.product_id]),
    }))
  }

  const voice = useVoice(async (audio, mime) => {
    setError(''); setBusy('hearing')
    const r = await askGuide({ action: 'transcribe', token, audio, mime })
    if (r.text) { order(r.text); return }
    setBusy(null)
    setError(r.error ? (T.guideErrors[r.error] || T.guideErrors.failed) : T.voiceNothing)
  }, () => { setError(T.guideErrors.no_speech_service); setTyping(true) })

  const setLine = (key, patch) => setResult((r) => ({ ...r, lines: r.lines.map((l) => (l.key === key ? { ...l, ...patch } : l)).filter((l) => l.quantity > 0) }))
  const pickChoice = (ci, pid) => setResult((r) => ({
    ...r,
    choices: r.choices.filter((_, i) => i !== ci),
    lines: [...r.lines, { key: 'c' + Date.now(), product_id: pid, quantity: 1, option_ids: [], needs_options: (byId[pid]?.modifier_groups || []).some((g) => g.required) }],
  }))

  const unit = (l) => {
    const p = byId[l.product_id]
    const opts = (p?.modifier_groups || []).flatMap((g) => g.options).filter((o) => l.option_ids.includes(o.id))
    return { p, optNames: opts.map((o) => o.name), cents: (p?.price_cents || 0) + opts.reduce((s, o) => s + (o.price_cents || 0), 0) }
  }
  const ready = (result?.lines || []).filter((l) => !l.needs_options)
  const total = ready.reduce((s, l) => s + unit(l).cents * l.quantity, 0)

  const addAll = async () => {
    setBusy('adding')
    const done = await onAddAll(ready)
    setBusy(null)
    const left = result.lines.filter((l) => l.needs_options || !done.includes(l.key))
    if (!left.length && !result.choices.length) onClose(ready.length)
    else setResult((r) => ({ ...r, lines: left }))
  }

  const mood = voice.recording ? 'listening' : busy === 'hearing' || busy === 'thinking' ? 'thinking' : result ? 'done' : 'idle'
  return (
    <div className="fixed inset-0 z-50 bg-ink/60 flex items-end sm:items-center sm:justify-center" onClick={() => onClose(0)}>
      <div className="bg-paper w-full max-w-lg rounded-t-[28px] sm:rounded-[28px] max-h-[92vh] flex flex-col overflow-hidden shadow-lift" onClick={(e) => e.stopPropagation()}>
        <div className="px-5 pt-4 pb-2 flex items-center justify-between">
          <h2 className="font-black text-lg text-ink">{T.voiceTitle}</h2>
          <button onClick={() => onClose(0)} className="p-1 text-ink-mute" aria-label={T.close}><X className="w-6 h-6" /></button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 pb-4">
          {/* Before anything is heard: the big microphone and an example. */}
          {!result && !busy && !voice.recording && (
            <div className="flex flex-col items-center text-center pt-2">
              <BeaverImg mood={mood} className="w-28" bob />
              <p className="mt-2 font-black text-ink">{T.voiceHint}</p>
              <p dir="auto" className="mt-1 text-sm text-ink-mute">{T.voiceExample}</p>
            </div>
          )}
          {(voice.recording || busy === 'hearing' || busy === 'thinking') && (
            <div className="flex flex-col items-center text-center pt-4 pb-2">
              <BeaverImg mood={mood} className="w-28" bob />
              <p className="mt-2 font-black text-sky-700">{voice.recording ? T.listening : busy === 'hearing' ? T.voiceHearing : T.voiceThinking}</p>
              {busy === 'thinking' && heard && <p dir="auto" className="mt-1 text-sm text-ink-soft">“{heard}”</p>}
            </div>
          )}
          {error && <p className="mt-3 text-center text-sm font-bold text-red-600">{error}</p>}

          {result && (
            <div className="pt-5">
              <p dir="auto" className="text-xs text-ink-mute mb-3"><span className="font-black">{T.youSaid}</span> “{heard}”</p>
              <AiCard mood="done" source={T.fromMenu}>
                <p dir="auto" lang={result.language || undefined} className="text-sm font-bold text-ink whitespace-pre-wrap">{result.reply}</p>

                {/* A question's answer may come with dishes to add (one tap each). */}
                {result.suggestions.map((x) => {
                  const p = byId[x.product_id]
                  return (
                    <div key={x.product_id} className="mt-2 bg-paper rounded-xl p-3 flex items-center gap-3">
                      <div className="flex-1 min-w-0">
                        <p className="font-bold text-ink text-sm">{x.quantity} × {dishName(p)}</p>
                        {x.why && <p dir="auto" className="text-xs text-ink-mute">{x.why}</p>}
                      </div>
                      <button disabled={added[x.product_id]} onClick={async () => { if (await onAdd(x)) setAdded((a) => ({ ...a, [x.product_id]: true })) }}
                        className="shrink-0 bg-gold-500 text-ink disabled:bg-sky-100 disabled:text-sky-800 text-sm font-black px-3 py-2 rounded-lg">
                        {added[x.product_id] ? '✓' : `${T.addSuggestion} ${money(p.price_cents * x.quantity)}`}
                      </button>
                    </div>
                  )
                })}

                {result.lines.length > 0 && (
                  <div className="mt-3 divide-y divide-slate-100">
                    {result.lines.map((l) => {
                      const u = unit(l)
                      if (!u.p) return null
                      return (
                        <div key={l.key} className="py-2.5 flex items-center gap-3">
                          <div className="flex-1 min-w-0">
                            <p className="font-black text-ink text-[15px] leading-tight">{dishName(u.p)}</p>
                            {(u.optNames.length > 0 || l.note) && (
                              <p dir="auto" className="text-xs text-ink-soft mt-0.5">{[...u.optNames, l.note].filter(Boolean).join(' · ')}</p>
                            )}
                            {l.needs_options
                              ? <button onClick={() => onPickOptions(u.p, l, () => setLine(l.key, { quantity: 0 }))}
                                  className="mt-1 text-xs font-black text-white bg-sky-600 px-2.5 py-1 rounded-full">{T.pickOptions}</button>
                              : <p className="text-xs font-bold text-ink-mute mt-0.5">{money(u.cents * l.quantity)}</p>}
                          </div>
                          <div className="flex items-center gap-2 shrink-0">
                            <button onClick={() => setLine(l.key, { quantity: l.quantity - 1 })} aria-label="-" className="w-8 h-8 rounded-full bg-slate-100 flex items-center justify-center"><Minus className="w-4 h-4" /></button>
                            <span className="font-black w-4 text-center">{l.quantity}</span>
                            <button onClick={() => setLine(l.key, { quantity: Math.min(10, l.quantity + 1) })} aria-label="+" className="w-8 h-8 rounded-full bg-gold-500 text-ink flex items-center justify-center"><Plus className="w-4 h-4" strokeWidth={3} /></button>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                )}

                {/* "A cold dish": several fit, the customer picks; Beaver never guesses. */}
                {result.choices.map((c, ci) => (
                  <div key={ci} className="mt-3">
                    <p dir="auto" className="text-sm font-black text-sky-800">{T.whichOne(c.heard)}</p>
                    <div className="flex flex-wrap gap-2 mt-1.5">
                      {c.product_ids.map((pid) => byId[pid] && (
                        <button key={pid} onClick={() => pickChoice(ci, pid)}
                          className="px-3 py-1.5 rounded-full text-sm font-bold bg-sky-50 text-sky-800 border border-sky-200">
                          {dishName(byId[pid])} · {money(byId[pid].price_cents)}
                        </button>
                      ))}
                    </div>
                  </div>
                ))}

                {result.missing.length > 0 && (
                  <p dir="auto" className="mt-3 text-sm text-ink-soft"><span className="font-black">{T.notOnMenu}</span> {result.missing.join(zhSep(T))}</p>
                )}
                {result.intent === 'order' && result.lines.length === 0 && result.choices.length === 0 && (
                  <p className="mt-2 text-sm text-ink-mute">{T.voiceNoLines}</p>
                )}
              </AiCard>
            </div>
          )}
        </div>

        <div className="p-4 bg-white border-t border-slate-100 space-y-3">
          {result && ready.length > 0 && (
            <button onClick={addAll} disabled={!!busy} className="btn-gold w-full py-4 rounded-2xl text-lg flex items-center justify-center gap-2">
              {busy === 'adding' && <Loader2 className="w-5 h-5 animate-spin" />}{T.addAll(ready.reduce((s, l) => s + l.quantity, 0), money(total))}
            </button>
          )}
          {typing ? (
            <form onSubmit={(e) => { e.preventDefault(); order(text) }} className="flex items-center gap-2">
              <button type="button" onClick={() => setTyping(false)} aria-label="voice" className="p-3 rounded-xl bg-slate-100 text-ink"><Mic className="w-5 h-5" /></button>
              <input dir="auto" autoFocus value={text} onChange={(e) => setText(e.target.value.slice(0, 400))} placeholder={T.voiceExample}
                className="flex-1 min-w-0 px-4 py-3 rounded-xl border border-slate-300 text-sm" />
              <button type="submit" disabled={!!busy || !text.trim()} aria-label={T.ask} className="p-3 rounded-xl bg-sky-600 disabled:opacity-40 text-white"><Send className="w-5 h-5" /></button>
            </form>
          ) : (
            <div className="flex items-center justify-center gap-6">
              <button onClick={() => setTyping(true)} aria-label={T.typeInstead} className="w-12 h-12 rounded-full bg-slate-100 text-ink-soft flex items-center justify-center"><Keyboard className="w-5 h-5" /></button>
              <button onClick={voice.toggle} disabled={busy === 'hearing' || busy === 'thinking' || busy === 'adding'} aria-label={T.voiceTitle}
                className={`w-20 h-20 rounded-full flex items-center justify-center shadow-lift disabled:opacity-50 ${voice.recording ? 'bg-red-600 text-white mic-ring' : 'btn-gold'}`}>
                {voice.recording ? <Square className="w-8 h-8" /> : <Mic className="w-9 h-9" strokeWidth={2.5} />}
              </button>
              <span className="w-12 text-center text-[11px] font-bold text-ink-mute">{result ? T.sayMore : ''}</span>
            </div>
          )}
          <p className="text-center text-[11px] text-ink-mute">{voice.recording ? T.tapToStop : T.aiNote}</p>
        </div>
      </div>
    </div>
  )
}

const zhSep = (T) => (T.notOnMenu.match(/[一-鿿]/) ? '、' : ', ')
