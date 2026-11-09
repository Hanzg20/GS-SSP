import React, { useEffect, useState } from 'react'
import { X, Pencil, RefreshCw, Loader2, Check, Plus } from 'lucide-react'
import { AiCard } from './ai.jsx'

/*
 * The table's own menu (scan-assistant action "plan"): the door asks how many
 * and how spicy (optional), Beaver puts a first round together for them, and
 * in the cart says honestly whether it's enough -- or too much. Server-side,
 * allergies and "no spice" are enforced, never trusted to the model; nothing
 * reaches the cart without a tap.
 */

const money = (c) => '$' + (c / 100).toFixed(2)
const SIZES = [1, 2, 3, 4, 5, 6, 7, 8]

/** How many, how spicy: tap again to clear. Both optional. */
export function PartyPicker({ party, onChange, T, compact = false }) {
  const chip = (on) => `rounded-2xl font-black border-2 ${compact ? 'py-2 text-sm' : 'py-2.5'} ${on ? 'bg-ink text-white border-ink' : 'bg-white text-ink-soft border-slate-200'}`
  return (
    <div className="space-y-3">
      <div>
        <p className="text-sm font-black text-ink-soft mb-2">{T.partyTitle}</p>
        <div className="grid grid-cols-8 gap-1.5">
          {SIZES.map((n) => (
            <button key={n} onClick={() => onChange({ ...party, size: party.size === n ? null : n })} className={chip(party.size === n)}>
              {n === 8 ? '8+' : n}
            </button>
          ))}
        </div>
      </div>
      <div>
        <p className="text-sm font-black text-ink-soft mb-2">{T.partySpice}</p>
        <div className="grid grid-cols-3 gap-2">
          {['none', 'mild', 'any'].map((k) => (
            <button key={k} onClick={() => onChange({ ...party, spice: party.spice === k ? null : k })} className={chip(party.spice === k)}>{T.spice[k]}</button>
          ))}
        </div>
      </div>
    </div>
  )
}

/** One suggested dish with its own Add (options it needs open the dish sheet). */
function PickRow({ s, p, T, done, onAdd }) {
  return (
    <div className="py-2 flex items-center gap-3">
      <div className="flex-1 min-w-0">
        <p className="font-black text-ink text-[15px] leading-tight">{s.quantity} × {p.name}</p>
        {s.why && <p dir="auto" className="text-xs text-ink-soft mt-0.5">{s.why}</p>}
        {s.allergens_unknown && <span className="inline-block mt-1 text-[11px] font-bold text-ink-mute bg-slate-100 px-2 py-0.5 rounded-full">⚠ {T.noAllergenData}</span>}
      </div>
      <button disabled={done} onClick={onAdd}
        className="shrink-0 bg-gold-500 text-ink disabled:bg-sky-100 disabled:text-sky-800 text-sm font-black px-3 py-2 rounded-xl flex items-center gap-1">
        {done ? <Check className="w-4 h-4" /> : <><Plus className="w-4 h-4" strokeWidth={3} />{money(p.price_cents * s.quantity)}</>}
      </button>
    </div>
  )
}

const errorText = (T, r) => T.guideErrors[r.error] || T.guideErrors.failed

/**
 * "Beaver's picks for 4": a first round for this table, at the top of the menu.
 * Fetched once per party/allergy setting (kept for this tab), or on a tap when
 * the table has already ordered.
 */
export function TablePicks({ token, products, party, avoid, T, askGuide, auto, onEditParty, onAdd }) {
  const key = `scan_picks_${token}_${party.size}_${party.spice}_${avoid.join(',')}`
  const [picks, setPicks] = useState(() => { try { return JSON.parse(sessionStorage.getItem(key) || 'null') } catch { return null } })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [added, setAddedState] = useState(() => { try { return JSON.parse(sessionStorage.getItem(key + '_added') || '{}') } catch { return {} } })
  // What this phone already added from the picks survives a reload, so nothing goes in twice.
  const setAdded = (f) => setAddedState((a) => {
    const next = typeof f === 'function' ? f(a) : f
    try { sessionStorage.setItem(key + '_added', JSON.stringify(next)) } catch { /* private mode: fine */ }
    return next
  })
  const [hidden, setHidden] = useState(false)
  const byId = Object.fromEntries(products.map((p) => [p.id, p]))

  const load = async () => {
    setBusy(true); setError('')
    const r = await askGuide({ action: 'plan', mode: 'start', token, party, avoid, browserLang: T.locale })
    setBusy(false)
    if (r.error) { setError(errorText(T, r)); return }
    const got = { reply: r.reply, language: r.language, suggestions: (r.suggestions || []).filter((s) => byId[s.product_id]) }
    setPicks(got); setAdded({})
    try { sessionStorage.setItem(key, JSON.stringify(got)) } catch { /* private mode: fine */ }
  }
  useEffect(() => {
    try {
      setPicks(JSON.parse(sessionStorage.getItem(key) || 'null'))
      setAddedState(JSON.parse(sessionStorage.getItem(key + '_added') || '{}'))
    } catch { setPicks(null); setAddedState({}) }
  }, [key])
  useEffect(() => { if (auto && !picks && !busy && !error) load() }, [auto, key]) // eslint-disable-line react-hooks/exhaustive-deps

  if (hidden) return null
  const who = [T.peopleN(party.size), party.spice ? T.spice[party.spice] : null].filter(Boolean).join(' · ')
  const left = (picks?.suggestions || []).filter((s) => !added[s.product_id])
  const addOne = async (s) => { if (await onAdd(s)) setAdded((a) => ({ ...a, [s.product_id]: true })) }
  const addAll = async () => { for (const s of left) await addOne(s) }

  // Not fetched yet (the table already ordered): a one-line invitation.
  if (!picks && !busy) {
    return (
      <AiCard className="mt-5" mood="clipboard">
        <div className="flex items-center gap-2">
          <button onClick={load} className="flex-1 text-start font-black text-ink">{T.picksBtn}<span className="block text-xs font-bold text-ink-mute">{who}</span></button>
          {error && <span className="text-xs font-bold text-red-600">{error}</span>}
          <button onClick={load} className="btn-gold px-4 py-2 rounded-xl text-sm">{error ? T.retry : T.picksGo}</button>
        </div>
      </AiCard>
    )
  }
  return (
    <AiCard className="mt-5" mood={busy ? 'thinking' : 'clipboard'} source={picks ? T.fromMenu : null}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-black text-ink">{T.picksTitle(party.size)}</p>
          <button onClick={onEditParty} className="text-xs font-bold text-sky-700 flex items-center gap-1">{who}<Pencil className="w-3 h-3" /></button>
        </div>
        <button onClick={() => setHidden(true)} aria-label={T.close} className="p-1 -me-1 text-ink-mute"><X className="w-5 h-5" /></button>
      </div>
      {busy && <p className="mt-3 text-sm font-bold text-sky-700 flex items-center gap-2"><Loader2 className="w-4 h-4 animate-spin" />{T.picksLoading}</p>}
      {error && <p className="mt-2 text-sm font-bold text-red-600">{error}</p>}
      {picks && !busy && (
        <>
          <p dir="auto" lang={picks.language || undefined} className="mt-2 text-sm text-ink-soft">{picks.reply}</p>
          <div className="mt-2 divide-y divide-slate-100">
            {picks.suggestions.map((s) => <PickRow key={s.product_id} s={s} p={byId[s.product_id]} T={T} done={!!added[s.product_id]} onAdd={() => addOne(s)} />)}
          </div>
          <div className="flex gap-2 mt-3">
            <button onClick={load} className="px-4 py-2.5 rounded-xl text-sm font-black bg-slate-100 text-ink-soft flex items-center gap-1"><RefreshCw className="w-4 h-4" />{T.picksAgain}</button>
            {left.length > 0 && (
              <button onClick={addAll} className="btn-gold flex-1 py-2.5 rounded-xl text-sm">
                {T.picksAll(money(left.reduce((sum, s) => sum + byId[s.product_id].price_cents * s.quantity, 0)))}
              </button>
            )}
          </div>
        </>
      )}
    </AiCard>
  )
}

/**
 * In the cart: "is this enough?" Beaver looks at what the table already has
 * plus the cart, and says enough / a bit short / too much. Suggestions only
 * when short. When the cart changes afterwards, the card offers to look again.
 */
export function EnoughCheck({ token, products, party, setParty, avoid, cart, ordered, rev, T, askGuide, onAdd }) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [askSize, setAskSize] = useState(false)
  const [added, setAdded] = useState({})
  const byId = Object.fromEntries(products.map((p) => [p.id, p]))

  const check = async (size = party.size) => {
    if (!size) { setAskSize(true); return }
    setAskSize(false); setBusy(true); setError('')
    const r = await askGuide({ action: 'plan', mode: 'enough', token, party: { ...party, size }, avoid, cart, ordered, browserLang: T.locale })
    setBusy(false)
    if (r.error) { setError(errorText(T, r)); return }
    setAdded({})
    setResult({ ...r, rev, suggestions: (r.suggestions || []).filter((s) => byId[s.product_id]) })
  }

  if (!result) {
    return (
      <div className="pt-3">
        {askSize ? (
          <AiCard mood="clipboard">
            <p className="font-black text-ink text-sm mb-2">{T.enoughNeedSize}</p>
            <div className="grid grid-cols-8 gap-1.5">
              {SIZES.map((n) => (
                <button key={n} onClick={() => { setParty({ ...party, size: n }); check(n) }}
                  className="py-2 rounded-xl text-sm font-black border-2 bg-white text-ink-soft border-slate-200">{n === 8 ? '8+' : n}</button>
              ))}
            </div>
          </AiCard>
        ) : (
          <button onClick={() => check()} disabled={busy}
            className="ai-card w-full !p-3 !pt-3 flex items-center gap-3 text-start">
            <span className="w-10 h-10 shrink-0 rounded-xl bg-gold-50 flex items-end justify-center overflow-hidden"><img src={`/beaver/${busy ? 'thinking' : 'clipboard'}.webp`} alt="" className="w-9 translate-y-1" /></span>
            <span className="flex-1 font-black text-ink text-sm">{busy ? T.enoughThinking : T.enoughBtn}</span>
            {busy && <Loader2 className="w-4 h-4 animate-spin text-sky-700" />}
          </button>
        )}
        {error && <p className="mt-2 text-sm font-bold text-red-600">{error}</p>}
      </div>
    )
  }
  const tone = { enough: 'bg-sky-100 text-sky-800', more: 'bg-gold-100 text-gold-800', less: 'bg-red-50 text-red-700' }[result.verdict] || 'bg-slate-100 text-ink-soft'
  return (
    <div className="pt-4">
      <AiCard mood={result.verdict === 'enough' ? 'done' : 'clipboard'} source={T.fromMenu}>
        <div className="flex items-center justify-between gap-2">
          {result.verdict ? <span className={`inline-block text-xs font-black px-2.5 py-1 rounded-full ${tone}`}>{T.verdict[result.verdict]}</span> : <span />}
          {result.rev !== rev && (
            <button onClick={() => check()} disabled={busy} className="text-xs font-black text-sky-700 flex items-center gap-1">
              {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}{T.enoughAgain}
            </button>
          )}
        </div>
        <p dir="auto" lang={result.language || undefined} className="mt-2 text-sm font-bold text-ink">{result.reply}</p>
        {result.suggestions.length > 0 && (
          <div className="mt-1 divide-y divide-slate-100">
            {result.suggestions.map((s) => (
              <PickRow key={s.product_id} s={s} p={byId[s.product_id]} T={T} done={!!added[s.product_id]}
                onAdd={async () => { if (await onAdd(s)) setAdded((a) => ({ ...a, [s.product_id]: true })) }} />
            ))}
          </div>
        )}
      </AiCard>
    </div>
  )
}
