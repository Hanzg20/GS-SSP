import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Plus, Minus, X, Check, Search, ShieldAlert, ChevronDown } from 'lucide-react'
import { AiCard, BeaverImg } from './ai.jsx'
import { T, LANG, ALLERGENS, DIETARY, joinList, dishName } from './i18n.js'
import { money } from './lib.js'

/*
 * Pieces both customer pages use: the table page (TableApp, Ourea Scan) and
 * online ordering (Arke). Dish rows and sheets, the allergy list, the menu's
 * search and category tabs, Beaver's screens, GoldSky's signature.
 */

/** The store's own name under a translated one, so staff and customer can point at the same dish. */
export const dishSubName = (p) => (dishName(p) !== p.name ? p.name : null)

/** Beaver, GoldSky's mascot: one picture per state (docs/mascot, web copies in public/beaver). */
export function Beaver({ mood = 'idle', className = 'w-24', bob = false }) {
  return <BeaverImg mood={mood} className={className} bob={bob} />
}

/**
 * A dish photo that never shows the browser's broken-image mark: if it can't
 * load (bad link, a phone's stale cache, no network) it gives way to [fallback].
 */
export function DishImg({ src, className, fallback = null, lazy = false }) {
  const [broken, setBroken] = useState(false)
  useEffect(() => setBroken(false), [src])
  if (!src || broken) return fallback
  return <img src={src} alt="" loading={lazy ? 'lazy' : undefined} onError={() => setBroken(true)} className={className} />
}

/** The customer's allergy list as words, against one dish: null = nothing to warn about. */
export function avoidWarning(p, avoid) {
  if (!avoid.length) return null
  if (!Array.isArray(p.allergens)) return { unknown: true }
  const hit = avoid.filter((a) => p.allergens.includes(a))
  return hit.length ? { hit } : null
}

/** Diet, spice and allergen badges; a dish with no allergen data says so. */
export function DishFacts({ p, compact = false }) {
  return (
    <div className="flex flex-wrap gap-1 mt-1.5">
      {(p.dietary || []).map((d) => <span key={d} className="text-[11px] font-bold bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded-full">{DIETARY[d] || d}</span>)}
      {p.spice_level > 0 && <span className="text-[11px] font-bold bg-red-50 text-red-700 px-2 py-0.5 rounded-full">{'🌶'.repeat(Math.min(p.spice_level, 5))}</span>}
      {!compact && (Array.isArray(p.allergens)
        ? p.allergens.length > 0 && <span className="text-[11px] font-bold bg-gold-50 text-gold-800 px-2 py-0.5 rounded-full">{T.contains}: {p.allergens.map((a) => ALLERGENS[a] || a).join(', ')}</span>
        : <span className="text-[11px] text-ink-mute px-1 py-0.5">{T.noAllergenData}</span>)}
    </div>
  )
}

/** Red label when the dish has something on the customer's list; grey when its allergens aren't recorded. */
export function AvoidLabel({ w }) {
  if (!w) return null
  if (w.unknown) return <span className="self-start inline-block mt-1.5 text-[11px] font-bold text-ink-mute bg-slate-100 px-2 py-0.5 rounded-full">⚠ {T.unknownAvoid}</span>
  return <span className="self-start inline-block mt-1.5 text-[11px] font-black text-white bg-red-600 px-2 py-0.5 rounded-full">⚠ {T.hasAvoid(joinList(w.hit.map((a) => ALLERGENS[a] || a)))}</span>
}

/** A sheet that slides up from the bottom (centred on a wide screen). */
export function Sheet({ onClose, children, tall = false }) {
  return (
    <div className="fixed inset-0 z-50 bg-ink/60 flex items-end sm:items-center sm:justify-center" onClick={onClose}>
      <div className={`bg-white w-full max-w-lg rounded-t-[28px] sm:rounded-[28px] ${tall ? 'max-h-[92vh]' : 'max-h-[88vh]'} flex flex-col overflow-hidden shadow-lift`}
        onClick={(e) => e.stopPropagation()}>
        {children}
      </div>
    </div>
  )
}

/**
 * A dish: photo, facts, then its options. Every dish opens here (tap the row);
 * pick-one groups act as radios, others as toggles up to max; required ones must
 * be answered before Add lights up.
 */
export function DishSheet({ product, avoid, onAdd, onClose, start, onExplain }) {
  const groups = product.modifier_groups || []
  // [start]: a line Beaver heard (voice order) -- its options, note and quantity carry over.
  const [picked, setPicked] = useState(() => Object.fromEntries(groups.map((g) => [g.id, g.options.filter((o) => (start?.option_ids || []).includes(o.id)).map((o) => o.id)])))
  const [note, setNote] = useState(start?.note || '')
  const [qty, setQty] = useState(start?.quantity || 1)
  const toggle = (g, o) => setPicked((p) => {
    const now = p[g.id] || []
    let next
    if (now.includes(o.id)) next = g.required && g.max_select === 1 ? now : now.filter((x) => x !== o.id)
    else if (g.max_select === 1) next = [o.id]
    else if (g.max_select && now.length >= g.max_select) next = now
    else next = [...now, o.id]
    return { ...p, [g.id]: next }
  })
  const missing = groups.filter((g) => g.required && !(picked[g.id] || []).length)
  const chosen = groups.flatMap((g) => g.options.filter((o) => (picked[g.id] || []).includes(o.id)))
  const unit = product.price_cents + chosen.reduce((s, o) => s + (o.price_cents || 0), 0)
  const sub = dishSubName(product)
  // "Beaver, what is this dish?" -- in the page's language, kept on the server per dish + language.
  // [onExplain] asks the guide with the page's own credential (table QR / store link); none = no button.
  const [explained, setExplained] = useState(null)
  const [explaining, setExplaining] = useState(false)
  const explain = async () => {
    setExplaining(true)
    const r = await onExplain(product)
    setExplaining(false)
    setExplained(r.error ? { error: T.guideErrors[r.error] || T.guideErrors.failed } : r)
  }
  return (
    <Sheet onClose={onClose} tall>
      <div className="overflow-y-auto flex-1">
        {product.image_url ? (
          <div className="relative">
            <DishImg src={product.image_url} className="w-full aspect-[4/3] object-cover bg-slate-100" fallback={<div className="h-14" />} />
            <button onClick={onClose} aria-label={T.close} className="absolute top-3 start-3 bg-white/90 rounded-full p-2 shadow"><X className="w-5 h-5" /></button>
          </div>
        ) : (
          <div className="flex justify-end px-3 pt-3"><button onClick={onClose} aria-label={T.close} className="p-2 text-ink-mute"><X className="w-6 h-6" /></button></div>
        )}
        <div className="px-5 pt-4 pb-2">
          <h2 className="font-black text-2xl text-ink leading-tight">{dishName(product)}</h2>
          {sub && <p className="text-sm text-ink-mute font-medium">{sub}</p>}
          <p className="text-gold-700 font-black text-lg mt-1">{money(product.price_cents)}</p>
          <DishFacts p={product} />
          {/* Unknown allergens are already said by DishFacts here; only a real hit adds a label. */}
          <AvoidLabel w={avoidWarning(product, avoid)?.hit ? avoidWarning(product, avoid) : null} />
          {product.description && <p className="text-[15px] text-ink-soft mt-3">{product.description}</p>}
          {product.ingredients && <p className="text-sm text-ink-mute mt-2"><span className="font-bold">{T.ingredients}:</span> {product.ingredients}</p>}
          {product.guide && <p className="text-sm text-sky-800 bg-sky-50 rounded-xl px-3 py-2 mt-3">💡 {product.guide}</p>}
          {onExplain && !explained?.note && (
            <button onClick={explain} disabled={explaining}
              className="mt-3 flex items-center gap-2 text-sm font-black text-sky-700 bg-sky-50 rounded-full ps-1 pe-3 py-1">
              <span className="w-7 h-7 rounded-full bg-white flex items-end justify-center overflow-hidden"><BeaverImg mood={explaining ? 'thinking' : 'idle'} className="w-6 translate-y-0.5" /></span>
              {explaining ? T.explaining : T.explain}
            </button>
          )}
          {explained?.error && <p className="mt-2 text-sm font-bold text-red-600">{explained.error}</p>}
          {explained?.note && (
            <AiCard className="mt-5" source={T.fromMenu}>
              {explained.name && explained.name !== dishName(product) && <p dir="auto" className="font-black text-ink">{explained.name}</p>}
              <p dir="auto" lang={LANG} className="text-sm text-ink-soft mt-1 leading-relaxed">{explained.note}</p>
            </AiCard>
          )}
        </div>
        <div className="px-5 space-y-5 pb-4 pt-2">
          {groups.map((g) => {
            const need = missing.includes(g)
            return (
              <div key={g.id}>
                <div className="flex justify-between items-baseline mb-2">
                  <h3 className="font-black text-ink">{g.name}</h3>
                  <span className={`text-xs font-black px-2 py-0.5 rounded-full ${need ? 'bg-red-50 text-red-600' : g.required ? 'bg-slate-100 text-ink-mute' : 'text-ink-mute'}`}>
                    {g.required ? `${T.required}${g.max_select === 1 ? ' · ' + T.pickOne : ''}` : g.max_select === 1 ? T.pickOne : g.max_select ? T.upTo(g.max_select) : T.optional}
                  </span>
                </div>
                <div className="flex flex-wrap gap-2">
                  {g.options.map((o) => {
                    const on = (picked[g.id] || []).includes(o.id)
                    return (
                      <button key={o.id} onClick={() => toggle(g, o)}
                        className={`px-4 py-2.5 rounded-2xl text-sm font-bold border-2 transition-all ${on ? 'bg-gold-50 text-ink border-gold-500' : 'bg-white text-ink-soft border-slate-200'}`}>
                        {on && <Check className="w-4 h-4 inline -mt-0.5 me-1 text-gold-700" />}{o.name}{o.price_cents ? ` +${money(o.price_cents)}` : ''}
                      </button>
                    )
                  })}
                </div>
              </div>
            )
          })}
          <div>
            <h3 className="font-black text-ink mb-2">{T.itemNote}</h3>
            <input value={note} onChange={(e) => setNote(e.target.value.slice(0, 120))} placeholder={T.notePh}
              className="w-full px-4 py-3 rounded-2xl bg-paper border border-slate-200 focus:border-gold-500 outline-none" />
          </div>
        </div>
      </div>
      <div className="p-4 border-t border-slate-100 flex items-center gap-3">
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={() => setQty((q) => Math.max(1, q - 1))} className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center" aria-label="-"><Minus className="w-4 h-4" /></button>
          <span className="font-black w-5 text-center text-lg">{qty}</span>
          <button onClick={() => setQty((q) => Math.min(20, q + 1))} className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center" aria-label="+"><Plus className="w-4 h-4" /></button>
        </div>
        <button disabled={missing.length > 0 || product.sold_out}
          onClick={() => onAdd({ optionIds: chosen.map((o) => o.id), optionNames: chosen.map((o) => o.name), unit, note: note.trim() }, qty)}
          className="btn-gold flex-1 py-4 rounded-2xl text-lg">
          {product.sold_out ? T.soldOut : `${T.add} · ${money(unit * qty)}`}
        </button>
      </div>
    </Sheet>
  )
}

/** The customer's allergy list (kept on this phone only). */
export function AvoidSheet({ avoid, setAvoid, onClose }) {
  const flip = (a) => setAvoid(avoid.includes(a) ? avoid.filter((x) => x !== a) : [...avoid, a])
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-5">
        <h2 className="font-black text-2xl text-ink">{T.avoidTitle}</h2>
        <p className="text-sm text-ink-mute mt-1">{T.avoidHint}</p>
        <div className="flex flex-wrap gap-2 mt-4">
          {Object.entries(ALLERGENS).map(([code, label]) => {
            const on = avoid.includes(code)
            return (
              <button key={code} onClick={() => flip(code)}
                className={`px-4 py-2.5 rounded-2xl text-sm font-bold border-2 ${on ? 'bg-red-50 border-red-500 text-red-700' : 'bg-white border-slate-200 text-ink-soft'}`}>
                {on && <Check className="w-4 h-4 inline -mt-0.5 me-1" />}{label}
              </button>
            )
          })}
        </div>
        <div className="flex gap-3 mt-6">
          <button onClick={() => setAvoid([])} className="px-5 py-3.5 rounded-2xl font-bold text-ink-soft bg-slate-100">{T.avoidClear}</button>
          <button onClick={onClose} className="btn-gold flex-1 py-3.5 rounded-2xl">{T.avoidDone}</button>
        </div>
      </div>
    </Sheet>
  )
}

/** Full-screen message with Beaver: bad code, offline, no token, loading. */
export function BeaverScreen({ mood, text, action }) {
  return (
    <div className="relative min-h-screen flex flex-col items-center justify-center p-8 text-center bg-paper">
      <Beaver mood={mood} className="w-36" bob={mood === 'blink'} />
      <p className="mt-5 text-lg font-black text-ink max-w-xs">{text}</p>
      {action}
      <Signature className="absolute bottom-6" />
    </div>
  )
}

/** GoldSky's signature at the foot of every page: the store leads, we sign. */
export function Signature({ className = '', product = 'Ourea' }) {
  return (
    <div className={`flex items-center justify-center gap-1.5 text-[11px] text-ink-mute ${className}`}>
      <span>{T.poweredBy}</span>
      <span className="font-black tracking-tight gs-gradient-text text-[12px]">GoldSky {product}</span>
    </div>
  )
}

/** Every category at once, to jump straight to one. */
export function CategorySheet({ sections, active, onPick, onClose }) {
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-6">
        <div className="flex items-center justify-between mb-3">
          <h2 className="font-black text-xl text-ink">{T.allCategories}</h2>
          <button onClick={onClose} aria-label={T.close} className="p-1 text-ink-mute"><X className="w-6 h-6" /></button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {sections.map(({ c, items }) => (
            <button key={c} onClick={() => onPick(c)}
              className={`text-start px-4 py-3 rounded-2xl border-2 font-black ${active === c ? 'border-gold-500 bg-gold-50 text-ink' : 'border-slate-200 text-ink-soft'}`}>
              {c}<span className="block text-xs font-bold text-ink-mute">{T.items(items.length)}</span>
            </button>
          ))}
        </div>
      </div>
    </Sheet>
  )
}

// ---------------------------------------------------------------- the menu: search, category tabs, dish rows

/** The menu's categories (store order) and the sections that match the search. */
export function useMenuSections(products, query) {
  const categories = useMemo(() => products ? [...new Set(products.map((p) => p.category || '—'))] : [], [products])
  const q = query.trim().toLowerCase()
  const sections = useMemo(() => {
    if (!products) return []
    const matches = (p) => !q || [p.name, dishName(p), p.description, p.ingredients].some((x) => (x || '').toLowerCase().includes(q))
    return categories.map((c) => ({ c, items: products.filter((p) => (p.category || '—') === c && matches(p)) })).filter((s) => s.items.length)
  }, [products, categories, q])
  return { categories, sections, q }
}

/** Category tabs that follow the scroll (the section nearest the sticky header is active), and jump-to. */
export function useCategoryTabs(sections, enabled = true) {
  const [activeCat, setActiveCat] = useState(null)
  const sectionRefs = useRef({})
  const tabsRef = useRef(null)
  const headerRef = useRef(null)
  // Where the sticky header ends: a section counts as reached when it comes up to there.
  const headerBottom = () => (headerRef.current?.getBoundingClientRect().bottom || 140) + 12
  useEffect(() => {
    if (!sections.length || !enabled) return
    const onScroll = () => {
      let cur = sections[0].c
      for (const s of sections) {
        const el = sectionRefs.current[s.c]
        if (el && el.getBoundingClientRect().top <= headerBottom() + 4) cur = s.c
      }
      setActiveCat(cur)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [sections, enabled])
  useEffect(() => {
    const tab = tabsRef.current?.querySelector(`[data-cat="${CSS.escape(activeCat || '')}"]`)
    tab?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
  }, [activeCat])
  const jumpTo = (c) => {
    const el = sectionRefs.current[c]
    if (el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - headerBottom(), behavior: 'smooth' })
  }
  return { activeCat, sectionRefs, tabsRef, headerRef, jumpTo }
}

/** Search box + the allergy filter button + the category tabs, for the sticky header. */
export function MenuFilters({ query, setQuery, avoid, onAvoid, categories, sections, nav, q, onAllCategories }) {
  return (
    <>
      <div className="max-w-3xl mx-auto px-4 pb-2 flex gap-2">
        <label className="flex-1 flex items-center gap-2 bg-paper rounded-2xl px-3 py-2 border border-slate-200 focus-within:border-gold-500">
          <Search className="w-4 h-4 text-ink-mute shrink-0" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={T.search} className="bg-transparent outline-none w-full text-[15px]" />
          {query && <button onClick={() => setQuery('')} aria-label={T.close}><X className="w-4 h-4 text-ink-mute" /></button>}
        </label>
        <button onClick={onAvoid}
          className={`shrink-0 px-3 rounded-2xl text-sm font-black border flex items-center gap-1 ${avoid.length ? 'bg-red-50 border-red-300 text-red-700' : 'bg-white border-slate-200 text-ink-soft'}`}>
          <ShieldAlert className="w-4 h-4" />{T.avoid}{avoid.length ? ` ${avoid.length}` : ''}
        </button>
      </div>
      {categories.length > 1 && !q && (
        <div className="max-w-3xl mx-auto ps-4 pb-2.5 flex items-center gap-2">
          <nav ref={nav.tabsRef} className="flex-1 overflow-x-auto no-scrollbar flex gap-2">
            {sections.map(({ c }) => (
              <button key={c} data-cat={c} onClick={() => nav.jumpTo(c)}
                className={`px-4 py-1.5 rounded-full text-sm font-black whitespace-nowrap transition-colors ${nav.activeCat === c ? 'bg-ink text-white' : 'bg-slate-100 text-ink-soft'}`}>
                {c}
              </button>
            ))}
          </nav>
          <button onClick={onAllCategories} aria-label={T.allCategories}
            className="shrink-0 me-4 w-9 h-9 rounded-full bg-white border border-slate-200 flex items-center justify-center shadow-sm"><ChevronDown className="w-5 h-5" /></button>
        </div>
      )}
    </>
  )
}

/** One dish in the menu list: photo, names, facts, price, and the + button (opens the sheet when it has options). */
export function DishRow({ p, avoid, here = 0, badge = null, onOpen, onQuickAdd }) {
  const w = avoidWarning(p, avoid)
  const sub = dishSubName(p)
  return (
    <div role="button" tabIndex={0} onClick={() => onOpen(p)}
      className={`flex gap-3 p-3 active:bg-slate-50 ${p.sold_out ? 'opacity-50' : ''}`}>
      <DishImg src={p.image_url} lazy className="w-24 h-24 rounded-2xl object-cover bg-slate-100 shrink-0" />
      <div className="min-w-0 flex-1 flex flex-col">
        <h3 className="font-black text-ink leading-snug">{dishName(p)}</h3>
        {sub && <p className="text-xs text-ink-mute">{sub}</p>}
        {p.description && <p className="text-xs text-ink-mute mt-0.5 line-clamp-2">{p.description}</p>}
        <DishFacts p={p} compact />
        <AvoidLabel w={w} />
        <div className="flex items-end justify-between mt-auto pt-2">
          <div className="flex items-center gap-2">
            <span className="font-black text-gold-700 text-[17px]">{money(p.price_cents)}{p.modifier_groups?.length ? <sup className="text-xs">+</sup> : null}</span>
            {badge}
          </div>
          {p.sold_out ? <span className="text-xs font-black text-ink-mute">{T.soldOutShort}</span> : (
            <button onClick={(e) => { e.stopPropagation(); p.modifier_groups?.length ? onOpen(p) : onQuickAdd(p) }}
              className="relative w-9 h-9 rounded-full bg-gradient-to-br from-gold-400 to-gold-600 text-ink flex items-center justify-center shadow-[0_4px_12px_rgba(255,159,0,.35)] active:scale-90 transition-transform" aria-label={T.add}>
              <Plus className="w-5 h-5" strokeWidth={3} />
              {here > 0 && <span className="absolute -top-1.5 -end-1.5 bg-ink text-white text-[10px] font-black min-w-[18px] h-[18px] px-1 rounded-full flex items-center justify-center">{here}</span>}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

/** The menu's sections, each a card of dish rows (rendered by [row]). */
export function MenuSections({ sections, nav, row }) {
  return sections.map(({ c, items }) => (
    <section key={c} ref={(el) => { nav.sectionRefs.current[c] = el }} className="pt-5">
      <h2 className="flex items-center gap-2 font-black text-lg text-ink mb-2">
        <span className="w-1.5 h-5 rounded-full bg-gradient-to-b from-gold-400 to-sky-500" />{c}
      </h2>
      <div className="bg-white rounded-3xl shadow-card divide-y divide-slate-100 overflow-hidden">
        {items.map(row)}
      </div>
    </section>
  ))
}
